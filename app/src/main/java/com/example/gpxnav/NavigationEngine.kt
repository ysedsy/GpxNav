package com.example.gpxnav

import kotlin.math.abs
import kotlin.math.roundToInt

enum class ManeuverType { STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN }

data class Maneuver(
    val pointIndex: Int,
    val distanceAlongRoute: Double,
    val turnAngleDegrees: Double,
    val type: ManeuverType
)

/**
 * A GPX route reduced to what live navigation needs: cumulative distance along the track,
 * and a maneuver list detected purely from geometry (bearing changes), no external routing service.
 */
class Route(val points: List<RoutePoint>) {
    val cumulativeDistances: DoubleArray
    val totalDistance: Double
    val maneuvers: List<Maneuver>

    init {
        require(points.size >= 2) { "Route needs at least 2 points" }
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + GeoMath.haversineMeters(
                points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon
            )
        }
        cumulativeDistances = cum
        totalDistance = cum.last()
        maneuvers = buildManeuvers()
    }

    private fun buildManeuvers(): List<Maneuver> {
        val refLat = points[points.size / 2].lat
        val proj = GeoMath.LocalProjection(refLat)
        val retained = simplify(proj, SIMPLIFY_EPSILON_METERS)
        val result = mutableListOf<Maneuver>()
        for (i in 1 until retained.size - 1) {
            val prevIdx = retained[i - 1]
            val curIdx = retained[i]
            val nextIdx = retained[i + 1]
            val bearingIn = GeoMath.bearingDegrees(
                points[prevIdx].lat, points[prevIdx].lon, points[curIdx].lat, points[curIdx].lon
            )
            val bearingOut = GeoMath.bearingDegrees(
                points[curIdx].lat, points[curIdx].lon, points[nextIdx].lat, points[nextIdx].lon
            )
            val delta = GeoMath.angleDiffSigned(bearingIn, bearingOut)
            val type = classify(delta)
            if (type != ManeuverType.STRAIGHT) {
                result.add(Maneuver(curIdx, cumulativeDistances[curIdx], delta, type))
            }
        }
        return result
    }

    /** Index idx such that cumulativeDistances[idx] <= distance <= cumulativeDistances[idx+1]; shared by
     *  [pointAtDistance] and by seeding [RouteLocator]/[NavigationSession] at a persisted resume position. */
    fun segmentIndexAtDistance(distance: Double): Int {
        val d = distance.coerceIn(0.0, totalDistance)
        var lo = 0
        var hi = cumulativeDistances.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulativeDistances[mid] <= d) lo = mid else hi = mid - 1
        }
        return lo.coerceAtMost(points.size - 2)
    }

    /** Interpolated point at a given cumulative distance along the route; used to generate candidate
     *  rejoin points when off-route without needing a raw point index. */
    fun pointAtDistance(distance: Double): RoutePoint {
        val d = distance.coerceIn(0.0, totalDistance)
        val idx = segmentIndexAtDistance(d)
        val segStart = cumulativeDistances[idx]
        val segEnd = cumulativeDistances[idx + 1]
        val t = if (segEnd > segStart) ((d - segStart) / (segEnd - segStart)).coerceIn(0.0, 1.0) else 0.0
        val a = points[idx]
        val b = points[idx + 1]
        return RoutePoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon))
    }

    private fun classify(delta: Double): ManeuverType {
        val a = abs(delta)
        return when {
            a < 20.0 -> ManeuverType.STRAIGHT
            a < 45.0 -> if (delta > 0) ManeuverType.SLIGHT_RIGHT else ManeuverType.SLIGHT_LEFT
            a < 120.0 -> if (delta > 0) ManeuverType.RIGHT else ManeuverType.LEFT
            a < 160.0 -> if (delta > 0) ManeuverType.SHARP_RIGHT else ManeuverType.SHARP_LEFT
            else -> ManeuverType.U_TURN
        }
    }

    /** Douglas-Peucker simplification; returns the retained ORIGINAL indices (always includes first & last). */
    private fun simplify(proj: GeoMath.LocalProjection, epsilonMeters: Double): List<Int> {
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        simplifyRange(proj, 0, points.size - 1, epsilonMeters, keep)
        return keep.indices.filter { keep[it] }
    }

    private fun simplifyRange(
        proj: GeoMath.LocalProjection, startIdx: Int, endIdx: Int, epsilon: Double, keep: BooleanArray
    ) {
        if (endIdx <= startIdx + 1) return
        var maxDist = -1.0
        var maxIdx = -1
        for (i in startIdx + 1 until endIdx) {
            val (dist, _) = GeoMath.distanceToSegmentMeters(
                proj,
                points[i].lat, points[i].lon,
                points[startIdx].lat, points[startIdx].lon,
                points[endIdx].lat, points[endIdx].lon
            )
            if (dist > maxDist) {
                maxDist = dist
                maxIdx = i
            }
        }
        if (maxDist > epsilon) {
            keep[maxIdx] = true
            simplifyRange(proj, startIdx, maxIdx, epsilon, keep)
            simplifyRange(proj, maxIdx, endIdx, epsilon, keep)
        }
    }

    companion object {
        private const val SIMPLIFY_EPSILON_METERS = 12.0
    }
}

data class LocateResult(
    val distanceAlongRoute: Double,
    val offRouteMeters: Double,
    val segmentIndex: Int,
    val nearestLat: Double,
    val nearestLon: Double
)

/** Projects live GPS fixes onto the route, tracking progress with a forward-biased search window so it stays cheap on long routes. */
class RouteLocator(private val route: Route, initialSegmentIndex: Int = 0) {
    private val proj = GeoMath.LocalProjection(route.points[route.points.size / 2].lat)
    private var lastSegmentIndex = initialSegmentIndex.coerceIn(0, route.points.size - 2)

    fun locate(lat: Double, lon: Double): LocateResult {
        val n = route.points.size
        var bestDist = Double.MAX_VALUE
        var bestT = 0.0
        var bestIdx = lastSegmentIndex

        val searchStart = (lastSegmentIndex - BACKWARD_WINDOW).coerceIn(0, n - 2)
        val searchEnd = (lastSegmentIndex + FORWARD_WINDOW).coerceIn(0, n - 2)
        for (i in searchStart..searchEnd) {
            val (dist, t) = GeoMath.distanceToSegmentMeters(
                proj, lat, lon,
                route.points[i].lat, route.points[i].lon,
                route.points[i + 1].lat, route.points[i + 1].lon
            )
            if (dist < bestDist) {
                bestDist = dist; bestT = t; bestIdx = i
            }
        }

        // Recovery: windowed search missed (e.g. app just started, or a big GPS jump) -> scan the whole route once.
        if (bestDist > RECOVERY_THRESHOLD_METERS) {
            for (i in 0 until n - 1) {
                val (dist, t) = GeoMath.distanceToSegmentMeters(
                    proj, lat, lon,
                    route.points[i].lat, route.points[i].lon,
                    route.points[i + 1].lat, route.points[i + 1].lon
                )
                if (dist < bestDist) {
                    bestDist = dist; bestT = t; bestIdx = i
                }
            }
        }

        lastSegmentIndex = bestIdx
        val distAlong = route.cumulativeDistances[bestIdx] +
            bestT * (route.cumulativeDistances[bestIdx + 1] - route.cumulativeDistances[bestIdx])
        val a = route.points[bestIdx]
        val b = route.points[bestIdx + 1]
        val nearestLat = a.lat + bestT * (b.lat - a.lat)
        val nearestLon = a.lon + bestT * (b.lon - a.lon)
        return LocateResult(distAlong, bestDist, bestIdx, nearestLat, nearestLon)
    }

    companion object {
        private const val BACKWARD_WINDOW = 50
        private const val FORWARD_WINDOW = 400
        private const val RECOVERY_THRESHOLD_METERS = 150.0
    }
}

data class NavUpdate(
    val offRouteMeters: Double,
    val isOffRoute: Boolean,
    val distanceAlongRoute: Double,
    val nearestLat: Double,
    val nearestLon: Double,
    val maneuverType: ManeuverType,
    val turnAngleDegrees: Double,
    val distanceToEvent: Double,
    val distanceToDestinationMeters: Double,
    val isFinalLeg: Boolean,
    val segmentIndex: Int,
    val maneuverIndex: Int
)

/** Ties a live GPS stream to a Route: which maneuver is next, and how far away it is.
 *  There's no road-network router here for the recorded track itself, so [NavUpdate.isOffRoute] just carries
 *  deviation state (with hysteresis, so it doesn't flicker right at the threshold) plus the geometrically
 *  nearest point on the track, which candidate off-route routing (elsewhere) uses as a search anchor.
 *  Once that routing picks an actual rejoin point, [applyRerouteTarget] jumps the maneuver pointer straight
 *  there rather than waiting for GPS to physically arrive, so skipped checkpoints stop showing immediately.
 *
 *  [initialDistanceAlongRoute] seeds both the locate search window and the maneuver pointer at a resumed
 *  progress instead of the route start - used when the app restarts mid-ride and reloads persisted progress,
 *  so the first fix doesn't need a full recovery scan and doesn't re-announce already-passed checkpoints. */
class NavigationSession(val route: Route, initialDistanceAlongRoute: Double = 0.0) {
    private val locator = RouteLocator(route, route.segmentIndexAtDistance(initialDistanceAlongRoute))
    private var maneuverPointer = 0
    private var offRoute = false

    init {
        skipManeuversBefore(initialDistanceAlongRoute.coerceIn(0.0, route.totalDistance))
    }

    /** Skips forward past every checkpoint before [distanceAlongRoute]; the pointer only ever moves
     *  forward, so this is safe to call repeatedly as rerouting refines its choice of rejoin point. */
    fun applyRerouteTarget(distanceAlongRoute: Double) {
        skipManeuversBefore(distanceAlongRoute)
    }

    private fun skipManeuversBefore(distanceAlongRoute: Double) {
        while (maneuverPointer < route.maneuvers.size &&
            route.maneuvers[maneuverPointer].distanceAlongRoute < distanceAlongRoute
        ) {
            maneuverPointer++
        }
    }

    fun update(lat: Double, lon: Double): NavUpdate {
        val loc = locator.locate(lat, lon)
        offRoute = if (offRoute) loc.offRouteMeters > OFF_ROUTE_EXIT_METERS else loc.offRouteMeters > OFF_ROUTE_ENTER_METERS
        skipManeuversBefore(loc.distanceAlongRoute - HYSTERESIS_METERS)
        val destination = route.points.last()
        val distanceToDestination = GeoMath.haversineMeters(lat, lon, destination.lat, destination.lon)
        val next = route.maneuvers.getOrNull(maneuverPointer)
        return if (next != null) {
            NavUpdate(
                offRouteMeters = loc.offRouteMeters,
                isOffRoute = offRoute,
                distanceAlongRoute = loc.distanceAlongRoute,
                nearestLat = loc.nearestLat,
                nearestLon = loc.nearestLon,
                maneuverType = next.type,
                turnAngleDegrees = next.turnAngleDegrees,
                distanceToEvent = (next.distanceAlongRoute - loc.distanceAlongRoute).coerceAtLeast(0.0),
                distanceToDestinationMeters = distanceToDestination,
                isFinalLeg = false,
                segmentIndex = loc.segmentIndex,
                maneuverIndex = maneuverPointer
            )
        } else {
            NavUpdate(
                offRouteMeters = loc.offRouteMeters,
                isOffRoute = offRoute,
                distanceAlongRoute = loc.distanceAlongRoute,
                nearestLat = loc.nearestLat,
                nearestLon = loc.nearestLon,
                maneuverType = ManeuverType.STRAIGHT,
                turnAngleDegrees = 0.0,
                distanceToEvent = (route.totalDistance - loc.distanceAlongRoute).coerceAtLeast(0.0),
                distanceToDestinationMeters = distanceToDestination,
                isFinalLeg = true,
                segmentIndex = loc.segmentIndex,
                maneuverIndex = maneuverPointer
            )
        }
    }

    companion object {
        private const val HYSTERESIS_METERS = 8.0
        private const val OFF_ROUTE_ENTER_METERS = 60.0
        private const val OFF_ROUTE_EXIT_METERS = 30.0
    }
}

/** Turn-by-turn instruction text for a maneuver; shared between the in-app banner and the foreground
 *  navigation service's notification so the two never drift out of sync with each other. */
fun instructionText(update: NavUpdate): String {
    if (update.isFinalLeg) return "Continue to destination"
    return when (update.maneuverType) {
        ManeuverType.SLIGHT_LEFT -> "Slight left"
        ManeuverType.LEFT -> "Turn left"
        ManeuverType.SHARP_LEFT -> "Sharp left"
        ManeuverType.SLIGHT_RIGHT -> "Slight right"
        ManeuverType.RIGHT -> "Turn right"
        ManeuverType.SHARP_RIGHT -> "Sharp right"
        ManeuverType.U_TURN -> "Make a U-turn"
        ManeuverType.STRAIGHT -> "Continue straight"
    }
}

fun formatDistance(meters: Double): String = when {
    meters >= 1000 -> "%.1f km".format(meters / 1000.0)
    meters >= 100 -> "${(meters / 50.0).roundToInt() * 50} m"
    else -> "${(meters / 10.0).roundToInt() * 10} m"
}
