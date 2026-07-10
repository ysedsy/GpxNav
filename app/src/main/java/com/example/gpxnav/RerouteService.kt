package com.example.gpxnav

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class RerouteResult(val rejoinDistanceAlongRoute: Double, val roadPoints: List<RoutePoint>)

/**
 * Best-effort road-based path back to the recorded route, via the public OSRM demo routing server.
 * The rest of this app deliberately has no road-network router (see NavigationEngine) since it just
 * follows a recorded GPX track, but a straight-line bearing back to that track can point through
 * buildings, fields, or rivers, so getting off-route guidance right needs actual street topology.
 *
 * The rejoin point isn't just "wherever on the track is geometrically closest" - that point can be
 * across a river or behind a highway median, cheap to reach as the crow flies but expensive by road.
 * Instead this samples candidate rejoin points along the track and picks whichever minimizes
 * (street distance from here to the candidate) + (remaining track distance from the candidate to the
 * destination), using OSRM's Table service to get all the candidate distances in one request.
 *
 * Best-effort throughout: if any request fails (offline, demo server rate-limited, etc.) callers fall
 * back to a straight line back to the route.
 */
object RerouteService {
    private const val OSRM_ROUTE_URL = "https://router.project-osrm.org/route/v1/driving"
    private const val OSRM_TABLE_URL = "https://router.project-osrm.org/table/v1/driving"

    private const val BACK_WINDOW_METERS = 300.0
    private const val FORWARD_WINDOW_METERS = 20_000.0
    private const val MAX_CANDIDATES = 40
    private const val MIN_SAMPLE_INTERVAL_METERS = 250.0

    /** Picks the rejoin point that minimizes street-distance-there plus remaining-track-distance-from-there,
     *  then fetches the actual road path to it. Falls back to routing straight at [nearestDistanceAlongRoute]
     *  if the Table lookup fails, and returns null only if even the road route itself can't be fetched. */
    fun fetchReroute(route: Route, fromLat: Double, fromLon: Double, nearestDistanceAlongRoute: Double): RerouteResult? {
        val candidateDistances = sampleCandidates(route, nearestDistanceAlongRoute)
        val candidatePoints = candidateDistances.map { route.pointAtDistance(it) }

        val chosenIndex = try {
            val streetDistances = fetchTableDistances(fromLat, fromLon, candidatePoints)
            bestCandidateIndex(route, candidateDistances, streetDistances)
        } catch (e: Exception) {
            Log.w("RerouteService", "OSRM table query failed: ${e.message}")
            null
        }

        val targetIndex = chosenIndex ?: candidateDistances.indices.minByOrNull {
            kotlin.math.abs(candidateDistances[it] - nearestDistanceAlongRoute)
        } ?: return null
        val target = candidatePoints[targetIndex]

        val road = fetchRoadRoute(fromLat, fromLon, target.lat, target.lon) ?: return null
        return RerouteResult(candidateDistances[targetIndex], road)
    }

    /** Distances along the route (in [0, route.totalDistance]) to evaluate as rejoin candidates: a small
     *  window behind the geometrically nearest point (GPS/hysteresis noise) and a much larger window ahead
     *  of it (where a road-cheaper rejoin is actually likely to be), capped so the OSRM table request stays small. */
    private fun sampleCandidates(route: Route, nearestDistanceAlongRoute: Double): List<Double> {
        val start = (nearestDistanceAlongRoute - BACK_WINDOW_METERS).coerceAtLeast(0.0)
        val end = (nearestDistanceAlongRoute + FORWARD_WINDOW_METERS).coerceAtMost(route.totalDistance)
        if (end <= start) return listOf(nearestDistanceAlongRoute.coerceIn(0.0, route.totalDistance))

        val span = end - start
        val step = (span / MAX_CANDIDATES).coerceAtLeast(MIN_SAMPLE_INTERVAL_METERS)
        val result = mutableListOf<Double>()
        var d = start
        while (d < end) {
            result.add(d)
            d += step
        }
        result.add(end)
        return result
    }

    private fun bestCandidateIndex(route: Route, candidateDistances: List<Double>, streetDistances: List<Double?>): Int? {
        var bestIndex: Int? = null
        var bestCost = Double.MAX_VALUE
        for (i in candidateDistances.indices) {
            val streetDist = streetDistances.getOrNull(i) ?: continue
            val remaining = route.totalDistance - candidateDistances[i]
            val cost = streetDist + remaining
            if (cost < bestCost) {
                bestCost = cost
                bestIndex = i
            }
        }
        return bestIndex
    }

    /** One-source-to-many-destinations street-distance matrix so evaluating N candidates costs a single request. */
    private fun fetchTableDistances(fromLat: Double, fromLon: Double, points: List<RoutePoint>): List<Double?> {
        val coords = buildString {
            append("$fromLon,$fromLat")
            points.forEach { append(";${it.lon},${it.lat}") }
        }
        val destinations = (1..points.size).joinToString(";")
        val url = "$OSRM_TABLE_URL/$coords?sources=0&destinations=$destinations&annotations=distance"
        val json = get(url)
        if (json.optString("code") != "Ok") throw IOException("OSRM table code=${json.optString("code")}")
        val row = json.optJSONArray("distances")?.optJSONArray(0) ?: throw IOException("No distances in response")
        return (0 until row.length()).map { i -> if (row.isNull(i)) null else row.getDouble(i) }
    }

    private fun fetchRoadRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<RoutePoint>? {
        return try {
            val url = "$OSRM_ROUTE_URL/$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson"
            val json = get(url)
            if (json.optString("code") != "Ok") return null
            val routes = json.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val coords = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
            val points = (0 until coords.length()).map { i ->
                val pair = coords.getJSONArray(i)
                RoutePoint(lat = pair.getDouble(1), lon = pair.getDouble(0))
            }
            points.takeIf { it.size >= 2 }
        } catch (e: Exception) {
            Log.w("RerouteService", "OSRM route query failed: ${e.message}")
            null
        }
    }

    private fun get(urlString: String): JSONObject {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
