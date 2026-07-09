package com.example.gpxnav

import kotlin.math.abs

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

data class LocateResult(val distanceAlongRoute: Double, val offRouteMeters: Double, val segmentIndex: Int)

/** Projects live GPS fixes onto the route, tracking progress with a forward-biased search window so it stays cheap on long routes. */
class RouteLocator(private val route: Route) {
    private val proj = GeoMath.LocalProjection(route.points[route.points.size / 2].lat)
    private var lastSegmentIndex = 0

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
        return LocateResult(distAlong, bestDist, bestIdx)
    }

    companion object {
        private const val BACKWARD_WINDOW = 50
        private const val FORWARD_WINDOW = 400
        private const val RECOVERY_THRESHOLD_METERS = 150.0
    }
}

data class NavUpdate(
    val offRouteMeters: Double,
    val maneuverType: ManeuverType,
    val turnAngleDegrees: Double,
    val distanceToEvent: Double,
    val isFinalLeg: Boolean
)

/** Ties a live GPS stream to a Route: which maneuver is next, and how far away it is. */
class NavigationSession(val route: Route) {
    private val locator = RouteLocator(route)
    private var maneuverPointer = 0

    fun update(lat: Double, lon: Double): NavUpdate {
        val loc = locator.locate(lat, lon)
        while (maneuverPointer < route.maneuvers.size &&
            route.maneuvers[maneuverPointer].distanceAlongRoute < loc.distanceAlongRoute - HYSTERESIS_METERS
        ) {
            maneuverPointer++
        }
        val next = route.maneuvers.getOrNull(maneuverPointer)
        return if (next != null) {
            NavUpdate(
                offRouteMeters = loc.offRouteMeters,
                maneuverType = next.type,
                turnAngleDegrees = next.turnAngleDegrees,
                distanceToEvent = (next.distanceAlongRoute - loc.distanceAlongRoute).coerceAtLeast(0.0),
                isFinalLeg = false
            )
        } else {
            NavUpdate(
                offRouteMeters = loc.offRouteMeters,
                maneuverType = ManeuverType.STRAIGHT,
                turnAngleDegrees = 0.0,
                distanceToEvent = (route.totalDistance - loc.distanceAlongRoute).coerceAtLeast(0.0),
                isFinalLeg = true
            )
        }
    }

    companion object {
        private const val HYSTERESIS_METERS = 8.0
    }
}
