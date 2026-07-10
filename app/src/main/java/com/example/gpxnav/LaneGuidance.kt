package com.example.gpxnav

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class LaneDirection {
    SHARP_LEFT, LEFT, SLIGHT_LEFT, THROUGH, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, MERGE_LEFT, MERGE_RIGHT, REVERSE, NONE
}

data class LaneInfo(val lanes: List<Set<LaneDirection>>)

/**
 * Best-effort OSM turn-lane lookup (`turn:lanes` / `turn:lanes:forward` / `turn:lanes:backward`) for the
 * maneuvers of a loaded route, via the public Overpass API. GPX tracks carry no lane data themselves, so this
 * is the only source for it; most rural/curvy roads simply aren't tagged, in which case a maneuver just gets
 * no entry and the lane row stays hidden. Runs once per loaded route (not per GPS fix) on a background thread.
 */
object LaneGuidance {
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val SEARCH_RADIUS_METERS = 25
    private const val MATCH_THRESHOLD_METERS = 30.0
    private const val MAX_MANEUVERS_QUERIED = 400

    private class Way(val lats: DoubleArray, val lons: DoubleArray, val tags: Map<String, String>)

    /** Maps maneuver index (into route.maneuvers) -> resolved LaneInfo, only where a tagged way was found nearby. */
    fun fetch(route: Route): Map<Int, LaneInfo> {
        val maneuvers = route.maneuvers.take(MAX_MANEUVERS_QUERIED)
        if (maneuvers.isEmpty()) return emptyMap()

        val json = try {
            queryOverpass(buildQuery(route, maneuvers))
        } catch (e: Exception) {
            Log.w("LaneGuidance", "Overpass query failed: ${e.message}")
            return emptyMap()
        }
        return matchManeuvers(route, maneuvers, json)
    }

    private fun buildQuery(route: Route, maneuvers: List<Maneuver>): String {
        val sb = StringBuilder("[out:json][timeout:25];(")
        for (m in maneuvers) {
            val p = route.points[m.pointIndex]
            // Regex-on-key match pulls turn:lanes, turn:lanes:forward and turn:lanes:backward in one clause.
            sb.append("way(around:$SEARCH_RADIUS_METERS,${p.lat},${p.lon})[~\"^turn:lanes\"~\".\"];")
        }
        sb.append(");out geom;")
        return sb.toString()
    }

    private fun queryOverpass(query: String): JSONObject {
        val conn = URL(OVERPASS_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.outputStream.use { it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun matchManeuvers(route: Route, maneuvers: List<Maneuver>, json: JSONObject): Map<Int, LaneInfo> {
        val elements = json.optJSONArray("elements") ?: return emptyMap()
        val ways = mutableListOf<Way>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val geom = el.optJSONArray("geometry") ?: continue
            val tagsObj = el.optJSONObject("tags") ?: continue
            val lats = DoubleArray(geom.length())
            val lons = DoubleArray(geom.length())
            for (j in 0 until geom.length()) {
                val pt = geom.getJSONObject(j)
                lats[j] = pt.getDouble("lat")
                lons[j] = pt.getDouble("lon")
            }
            val tags = mutableMapOf<String, String>()
            tagsObj.keys().forEach { k -> tags[k] = tagsObj.getString(k) }
            ways.add(Way(lats, lons, tags))
        }
        if (ways.isEmpty()) return emptyMap()

        val result = mutableMapOf<Int, LaneInfo>()
        maneuvers.forEachIndexed { idx, m ->
            val p = route.points[m.pointIndex]
            val proj = GeoMath.LocalProjection(p.lat)
            var bestWay: Way? = null
            var bestDist = Double.MAX_VALUE
            for (way in ways) {
                for (j in 0 until way.lats.size - 1) {
                    val (dist, _) = GeoMath.distanceToSegmentMeters(
                        proj, p.lat, p.lon, way.lats[j], way.lons[j], way.lats[j + 1], way.lons[j + 1]
                    )
                    if (dist < bestDist) {
                        bestDist = dist
                        bestWay = way
                    }
                }
            }
            // Doesn't verify the way's node order vs. direction of travel; forward/backward is an approximation.
            val tag = bestWay?.tags?.let { it["turn:lanes:forward"] ?: it["turn:lanes"] ?: it["turn:lanes:backward"] }
            if (bestWay != null && bestDist <= MATCH_THRESHOLD_METERS && tag != null) {
                result[idx] = LaneInfo(parseLanesTag(tag))
            }
        }
        return result
    }

    private fun parseLanesTag(tag: String): List<Set<LaneDirection>> =
        tag.split("|").map { laneStr ->
            laneStr.split(";").mapNotNull { toDirection(it) }.toSet().ifEmpty { setOf(LaneDirection.NONE) }
        }

    private fun toDirection(s: String): LaneDirection? = when (s.trim()) {
        "sharp_left" -> LaneDirection.SHARP_LEFT
        "left" -> LaneDirection.LEFT
        "slight_left" -> LaneDirection.SLIGHT_LEFT
        "through" -> LaneDirection.THROUGH
        "slight_right" -> LaneDirection.SLIGHT_RIGHT
        "right" -> LaneDirection.RIGHT
        "sharp_right" -> LaneDirection.SHARP_RIGHT
        "merge_to_left" -> LaneDirection.MERGE_LEFT
        "merge_to_right" -> LaneDirection.MERGE_RIGHT
        "reverse" -> LaneDirection.REVERSE
        "none", "" -> LaneDirection.NONE
        else -> null
    }

    /** Whether a lane tagged with [direction] is a valid choice for the upcoming [type] maneuver. */
    fun matchesManeuver(direction: LaneDirection, type: ManeuverType): Boolean = when (type) {
        ManeuverType.STRAIGHT -> direction == LaneDirection.THROUGH
        ManeuverType.SLIGHT_LEFT -> direction == LaneDirection.SLIGHT_LEFT || direction == LaneDirection.LEFT
        ManeuverType.LEFT -> direction == LaneDirection.LEFT
        ManeuverType.SHARP_LEFT -> direction == LaneDirection.SHARP_LEFT || direction == LaneDirection.LEFT
        ManeuverType.SLIGHT_RIGHT -> direction == LaneDirection.SLIGHT_RIGHT || direction == LaneDirection.RIGHT
        ManeuverType.RIGHT -> direction == LaneDirection.RIGHT
        ManeuverType.SHARP_RIGHT -> direction == LaneDirection.SHARP_RIGHT || direction == LaneDirection.RIGHT
        ManeuverType.U_TURN -> direction == LaneDirection.REVERSE
    }

    /** Glyph shown per lane; multi-direction lanes (e.g. "through;right") concatenate their glyphs. */
    fun glyphFor(directions: Set<LaneDirection>): String {
        val glyphs = directions.mapNotNull {
            when (it) {
                LaneDirection.SHARP_LEFT -> "↙"
                LaneDirection.LEFT -> "←"
                LaneDirection.SLIGHT_LEFT -> "↖"
                LaneDirection.THROUGH -> "↑"
                LaneDirection.SLIGHT_RIGHT -> "↗"
                LaneDirection.RIGHT -> "→"
                LaneDirection.SHARP_RIGHT -> "↘"
                LaneDirection.MERGE_LEFT -> "↖"
                LaneDirection.MERGE_RIGHT -> "↗"
                LaneDirection.REVERSE -> "↶"
                LaneDirection.NONE -> null
            }
        }.distinct()
        return if (glyphs.isEmpty()) "↑" else glyphs.joinToString("")
    }
}
