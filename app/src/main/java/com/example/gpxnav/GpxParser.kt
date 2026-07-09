package com.example.gpxnav

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

data class RoutePoint(val lat: Double, val lon: Double)

/** Minimal GPX reader: no external XML library, just android.util.Xml. */
object GpxParser {

    /** Reads trkpt (preferred, e.g. Kurviger track logs), falling back to rtept, then wpt. */
    fun parsePoints(gpxText: String): List<RoutePoint> {
        val trk = mutableListOf<RoutePoint>()
        val rte = mutableListOf<RoutePoint>()
        val wpt = mutableListOf<RoutePoint>()

        val parser = Xml.newPullParser()
        parser.setInput(gpxText.reader())

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val target = when (parser.name) {
                    "trkpt" -> trk
                    "rtept" -> rte
                    "wpt" -> wpt
                    else -> null
                }
                if (target != null) {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) target.add(RoutePoint(lat, lon))
                }
            }
            event = parser.next()
        }

        return when {
            trk.size >= 2 -> trk
            rte.size >= 2 -> rte
            wpt.size >= 2 -> wpt
            else -> throw IllegalArgumentException("No usable track, route, or waypoints found in this GPX file.")
        }
    }
}
