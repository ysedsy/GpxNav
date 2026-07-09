package com.example.gpxnav

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Local, dependency-free geographic math: distances, bearings, and a flat-earth projection good enough for route-scale (a few hundred km) computations. */
object GeoMath {
    private const val EARTH_RADIUS_M = 6371000.0

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed turn from bearing [from] to [to], in (-180, 180]. Positive = clockwise / right turn. */
    fun angleDiffSigned(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Equirectangular projection around a reference latitude; accurate enough for a single route's extent. */
    class LocalProjection(refLat: Double) {
        private val metersPerDegLat = 111_320.0
        private val metersPerDegLon = 111_320.0 * cos(refLat * PI / 180.0)

        fun toXY(lat: Double, lon: Double): DoubleArray =
            doubleArrayOf(lon * metersPerDegLon, lat * metersPerDegLat)
    }

    /** Perpendicular distance (meters) from point p to segment a-b, and the projection parameter t in [0,1]. */
    fun distanceToSegmentMeters(
        proj: LocalProjection,
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Pair<Double, Double> {
        val p = proj.toXY(pLat, pLon)
        val a = proj.toXY(aLat, aLon)
        val b = proj.toXY(bLat, bLon)
        val abx = b[0] - a[0]
        val aby = b[1] - a[1]
        val lenSq = abx * abx + aby * aby
        val t = if (lenSq < 1e-9) 0.0 else (((p[0] - a[0]) * abx + (p[1] - a[1]) * aby) / lenSq).coerceIn(0.0, 1.0)
        val projX = a[0] + t * abx
        val projY = a[1] + t * aby
        val dist = hypot(p[0] - projX, p[1] - projY)
        return dist to t
    }
}
