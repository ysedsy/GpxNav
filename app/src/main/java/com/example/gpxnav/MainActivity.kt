package com.example.gpxnav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.DashPathEffect
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.gpxnav.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var navProvider: GpsMyLocationProvider? = null
    private var navSession: NavigationSession? = null
    private var followMode = true

    private var routePoints: List<RoutePoint>? = null
    private var traveledPolyline: Polyline? = null
    private var remainingPolyline: Polyline? = null
    private var laneInfoByManeuver: Map<Int, LaneInfo> = emptyMap()
    private var smoothedBearing: Float? = null
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private var rerouteOverlay: Polyline? = null
    private var rerouteRoadPoints: List<RoutePoint>? = null
    private var rerouteFetchInFlight = false
    private var lastRerouteFetchAtMs = 0L
    private var lastRerouteOriginLat = 0.0
    private var lastRerouteOriginLon = 0.0

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) enableLocation()
        else Toast.makeText(this, "Location permission needed", Toast.LENGTH_LONG).show()
    }

    private val openGpxLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { loadGpx(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid requires a User-Agent per OSM tile usage policy.
        Configuration.getInstance().userAgentValue = packageName
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.map.setTileSource(TileSourceFactory.MAPNIK)
        b.map.setMultiTouchControls(true)
        b.map.controller.setZoom(6.0)
        b.map.controller.setCenter(GeoPoint(51.16, 10.45)) // roughly the center of Germany

        b.btnLoadGpx.setOnClickListener {
            if (hasLocationPermission()) openGpxLauncher.launch(arrayOf("*/*"))
            else permLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }

        b.btnFollow.setOnClickListener {
            followMode = !followMode
            b.btnFollow.alpha = if (followMode) 1.0f else 0.4f
            if (!followMode) {
                smoothedBearing = null
                b.map.setMapOrientation(0f)
                b.map.invalidate()
            }
        }

        if (hasLocationPermission()) enableLocation()

        restoreLastGpx()
    }

    /** Reloads whichever GPX was open at last shutdown, if the SAF grant to it is still valid. */
    private fun restoreLastGpx() {
        val uriString = prefs.getString(KEY_LAST_GPX_URI, null) ?: return
        loadGpx(Uri.parse(uriString), isRestore = true)
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun enableLocation() {
        if (myLocationOverlay == null) {
            val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), b.map)
            overlay.enableMyLocation()
            b.map.overlays.add(overlay)
            myLocationOverlay = overlay
        }
        if (navProvider == null) {
            val provider = GpsMyLocationProvider(this)
            provider.setLocationUpdateMinTime(1000L)
            provider.setLocationUpdateMinDistance(2f)
            provider.startLocationProvider(object : IMyLocationConsumer {
                override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
                    location?.let { onLocationUpdate(it) }
                }
            })
            navProvider = provider
        }
    }

    /** Reading + parsing runs off the main thread: the URI may point at a cloud-backed provider
     *  (Drive, Dropbox, ...) whose openInputStream() can block on network I/O, and doing that
     *  synchronously in onCreate (for the last-loaded-GPX restore) would stall app startup. */
    private fun loadGpx(uri: Uri, isRestore: Boolean = false) {
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: throw IllegalArgumentException("Could not read file.")
                val points = GpxParser.parsePoints(text)
                val route = Route(points)
                if (!isRestore) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: SecurityException) { /* provider doesn't support persistable grants; won't survive restart */ }
                }
                runOnUiThread { onGpxLoaded(uri, points, route) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isRestore) {
                        prefs.edit().remove(KEY_LAST_GPX_URI).apply()
                    } else {
                        Toast.makeText(this, "GPX error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun onGpxLoaded(uri: Uri, points: List<RoutePoint>, route: Route) {
        navSession = NavigationSession(route)
        routePoints = points
        laneInfoByManeuver = emptyMap()
        smoothedBearing = null
        clearReroute()

        traveledPolyline?.let { b.map.overlays.remove(it) }
        remainingPolyline?.let { b.map.overlays.remove(it) }
        val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
        val traveled = Polyline().apply {
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.route_traveled)
            outlinePaint.strokeWidth = 10f
        }
        val remaining = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.nav_accent)
            outlinePaint.strokeWidth = 10f
        }
        b.map.overlays.add(traveled)
        b.map.overlays.add(remaining)
        traveledPolyline = traveled
        remainingPolyline = remaining

        b.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geoPoints), true, 100)
        b.map.invalidate()

        b.navBanner.visibility = View.VISIBLE
        b.navBanner.setBackgroundResource(R.drawable.banner_bg)
        b.tvInstruction.text = "Route loaded"
        b.tvDistance.text = "${"%.1f".format(route.totalDistance / 1000.0)} km, ${route.maneuvers.size} turns"
        b.laneRow.visibility = View.GONE
        b.laneRow.removeAllViews()

        prefs.edit().putString(KEY_LAST_GPX_URI, uri.toString()).apply()

        Toast.makeText(this, "Loaded ${points.size} points", Toast.LENGTH_SHORT).show()

        Thread {
            val lanes = try { LaneGuidance.fetch(route) } catch (e: Exception) { emptyMap() }
            runOnUiThread {
                if (navSession?.route === route) laneInfoByManeuver = lanes
            }
        }.start()
    }

    private fun onLocationUpdate(location: Location) {
        val session = navSession ?: return
        val update = session.update(location.latitude, location.longitude)

        updateRouteSplit(location.latitude, location.longitude, update.segmentIndex)

        if (update.isOffRoute) {
            b.navBanner.setBackgroundResource(R.drawable.banner_bg_offroute)
            b.tvInstruction.text = "Off route"
            b.tvDistance.text = if (rerouteRoadPoints != null)
                "Follow path back · ${formatDistance(update.offRouteMeters)}"
            else
                "Head back · ${formatDistance(update.offRouteMeters)}"
            maybeFetchReroute(location, update)
            val bearingToRoute = rerouteBearing(location, update)
            val heading = if (location.hasBearing() && location.speed >= MIN_SPEED_FOR_BEARING_MPS)
                location.bearing else smoothedBearing ?: 0f
            b.ivArrow.setImageResource(R.drawable.ic_arrow_straight)
            b.ivArrow.rotation = ((bearingToRoute - heading + 360.0) % 360.0).toFloat()
        } else {
            if (rerouteRoadPoints != null) clearReroute()
            b.navBanner.setBackgroundResource(R.drawable.banner_bg)
            b.tvInstruction.text = instructionText(update)
            b.tvDistance.text = if (update.isFinalLeg && update.distanceToEvent < ARRIVED_THRESHOLD_METERS)
                "Arrived"
            else
                "in ${formatDistance(update.distanceToEvent)}"

            if (update.maneuverType == ManeuverType.U_TURN) {
                b.ivArrow.setImageResource(R.drawable.ic_arrow_uturn)
                b.ivArrow.rotation = 0f
            } else {
                b.ivArrow.setImageResource(R.drawable.ic_arrow_straight)
                b.ivArrow.rotation = update.turnAngleDegrees.toFloat()
            }
        }
        b.navBanner.visibility = View.VISIBLE
        updateLaneRow(update)
        updateCamera(location, update)
    }

    /** Splits the route polyline at the live GPS fix: the point nearest to us on the recorded track is
     *  effectively replaced by our actual position, so the traveled/remaining seam never shows a gap. */
    private fun updateRouteSplit(currentLat: Double, currentLon: Double, segmentIndex: Int) {
        val points = routePoints ?: return
        val traveled = traveledPolyline ?: return
        val remaining = remainingPolyline ?: return
        val current = GeoPoint(currentLat, currentLon)

        val traveledPts = ArrayList<GeoPoint>(segmentIndex + 2)
        for (i in 0..segmentIndex) traveledPts.add(GeoPoint(points[i].lat, points[i].lon))
        traveledPts.add(current)
        traveled.setPoints(traveledPts)

        val remainingPts = ArrayList<GeoPoint>(points.size - segmentIndex + 1)
        remainingPts.add(current)
        for (i in (segmentIndex + 1) until points.size) remainingPts.add(GeoPoint(points[i].lat, points[i].lon))
        remaining.setPoints(remainingPts)

        b.map.invalidate()
    }

    /** Kicks off a background OSRM lookup for a road path from here back to the route, throttled so a
     *  rider drifting off-route for a while doesn't hammer the public routing server every GPS fix.
     *  The rejoin point is chosen by street-distance-there + remaining-track-distance-from-there, not by
     *  which point is geometrically closest, so it can land well ahead of [update.nearestLat]/[nearestLon]
     *  if that's cheaper overall; once chosen, checkpoints before it are skipped immediately. */
    private fun maybeFetchReroute(location: Location, update: NavUpdate) {
        if (rerouteFetchInFlight) return
        val now = System.currentTimeMillis()
        if (now - lastRerouteFetchAtMs < REROUTE_MIN_INTERVAL_MS) return
        val movedFar = GeoMath.haversineMeters(
            location.latitude, location.longitude, lastRerouteOriginLat, lastRerouteOriginLon
        ) > REROUTE_REFETCH_DISTANCE_METERS
        if (rerouteRoadPoints != null && !movedFar) return

        val session = navSession ?: return
        rerouteFetchInFlight = true
        lastRerouteFetchAtMs = now
        lastRerouteOriginLat = location.latitude
        lastRerouteOriginLon = location.longitude
        val route = session.route
        val fromLat = location.latitude
        val fromLon = location.longitude
        val nearestDistanceAlongRoute = update.distanceAlongRoute
        Thread {
            val result = RerouteService.fetchReroute(route, fromLat, fromLon, nearestDistanceAlongRoute)
            runOnUiThread {
                rerouteFetchInFlight = false
                if (navSession === session && result != null) {
                    rerouteRoadPoints = result.roadPoints
                    session.applyRerouteTarget(result.rejoinDistanceAlongRoute)
                    drawRerouteOverlay()
                }
            }
        }.start()
    }

    private fun drawRerouteOverlay() {
        rerouteOverlay?.let { b.map.overlays.remove(it) }
        rerouteOverlay = null
        val points = rerouteRoadPoints ?: return
        val overlay = Polyline().apply {
            setPoints(points.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.reroute_path)
            outlinePaint.strokeWidth = 8f
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 16f), 0f)
        }
        b.map.overlays.add(overlay)
        rerouteOverlay = overlay
        b.map.invalidate()
    }

    private fun clearReroute() {
        rerouteOverlay?.let { b.map.overlays.remove(it) }
        rerouteOverlay = null
        rerouteRoadPoints = null
        lastRerouteFetchAtMs = 0L
        b.map.invalidate()
    }

    /** Bearing to head right now while off-route: along the fetched road path once we have one
     *  (aimed a little ahead so the arrow reflects the next street to take, not just the snapped start
     *  point), falling back to a straight line to the route if OSRM hasn't answered yet. */
    private fun rerouteBearing(location: Location, update: NavUpdate): Double {
        val road = rerouteRoadPoints
        if (road != null && road.size >= 2) {
            val target = road.firstOrNull {
                GeoMath.haversineMeters(location.latitude, location.longitude, it.lat, it.lon) > MIN_REROUTE_AIM_METERS
            } ?: road.last()
            return GeoMath.bearingDegrees(location.latitude, location.longitude, target.lat, target.lon)
        }
        return GeoMath.bearingDegrees(location.latitude, location.longitude, update.nearestLat, update.nearestLon)
    }

    /** Google Maps-style follow camera: recenters + eases the zoom in on approach to the next maneuver,
     *  and rotates the map to heading-up while riding. */
    private fun updateCamera(location: Location, update: NavUpdate) {
        if (!followMode) return

        val current = GeoPoint(location.latitude, location.longitude)
        b.map.controller.animateTo(current, approachZoom(update), CAMERA_ANIMATION_MS)

        if (location.hasBearing() && location.speed >= MIN_SPEED_FOR_BEARING_MPS) {
            val target = location.bearing
            val smoothed = smoothedBearing?.let { smoothAngle(it, target, BEARING_SMOOTHING_ALPHA) } ?: target
            smoothedBearing = smoothed
            b.map.setMapOrientation(-smoothed)
        }
    }

    private fun approachZoom(update: NavUpdate): Double {
        if (update.isFinalLeg) return BASE_ZOOM
        val d = update.distanceToEvent
        val t = when {
            d >= ZOOM_APPROACH_START_METERS -> 0.0
            d <= ZOOM_APPROACH_FULL_METERS -> 1.0
            else -> 1.0 - (d - ZOOM_APPROACH_FULL_METERS) / (ZOOM_APPROACH_START_METERS - ZOOM_APPROACH_FULL_METERS)
        }
        return BASE_ZOOM + t * (APPROACH_ZOOM - BASE_ZOOM)
    }

    private fun smoothAngle(prev: Float, target: Float, alpha: Float): Float {
        val diff = (target - prev + 540f) % 360f - 180f
        return (prev + diff * alpha + 360f) % 360f
    }

    private fun updateLaneRow(update: NavUpdate) {
        val info = if (update.isFinalLeg || update.isOffRoute) null else laneInfoByManeuver[update.maneuverIndex]
        if (info == null) {
            b.laneRow.visibility = View.GONE
            b.laneRow.removeAllViews()
            return
        }
        b.laneRow.removeAllViews()
        for (lane in info.lanes) {
            val active = lane.any { LaneGuidance.matchesManeuver(it, update.maneuverType) }
            val tv = TextView(this).apply {
                text = LaneGuidance.glyphFor(lane)
                textSize = 26f
                setTextColor(
                    ContextCompat.getColor(this@MainActivity, if (active) R.color.lane_active else R.color.lane_inactive)
                )
                setPadding(dp(10), dp(4), dp(10), dp(4))
                gravity = Gravity.CENTER
            }
            b.laneRow.addView(tv)
        }
        b.laneRow.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun instructionText(update: NavUpdate): String {
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

    private fun formatDistance(meters: Double): String = when {
        meters >= 1000 -> "%.1f km".format(meters / 1000.0)
        meters >= 100 -> "${(meters / 50.0).roundToInt() * 50} m"
        else -> "${(meters / 10.0).roundToInt() * 10} m"
    }

    override fun onResume() {
        super.onResume()
        b.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        b.map.onPause()
    }

    override fun onDestroy() {
        navProvider?.stopLocationProvider()
        myLocationOverlay?.disableMyLocation()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "gpxnav"
        private const val KEY_LAST_GPX_URI = "last_gpx_uri"
        private const val ARRIVED_THRESHOLD_METERS = 30.0
        private const val BASE_ZOOM = 17.0
        private const val APPROACH_ZOOM = 18.7
        private const val ZOOM_APPROACH_START_METERS = 150.0
        private const val ZOOM_APPROACH_FULL_METERS = 40.0
        private const val CAMERA_ANIMATION_MS = 800L
        private const val MIN_SPEED_FOR_BEARING_MPS = 1.0f
        private const val BEARING_SMOOTHING_ALPHA = 0.35f
        private const val REROUTE_MIN_INTERVAL_MS = 8000L
        private const val REROUTE_REFETCH_DISTANCE_METERS = 30.0
        private const val MIN_REROUTE_AIM_METERS = 15.0
    }
}
