package com.example.gpxnav

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.example.gpxnav.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import kotlin.math.roundToInt

/**
 * Thin UI shell: all live GPS/NavigationSession work happens in [NavigationService], which keeps running
 * with the screen off or the app backgrounded. This class only renders whatever [NavigationState] the
 * service last posted, plus the one-off concerns that are genuinely UI (map, file picker, permissions).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val relayProvider = RelayLocationProvider()
    private var followMode = true
    private var smoothedBearing: Float? = null
    private val prefs by lazy { getSharedPreferences(NavigationService.PREFS_NAME, MODE_PRIVATE) }

    private var traveledPolyline: Polyline? = null
    private var remainingPolyline: Polyline? = null
    private var polylineRoutePoints: List<RoutePoint>? = null

    private var rerouteOverlay: Polyline? = null
    private var lastRerouteRoadPoints: List<RoutePoint>? = null

    private var navService: NavigationService? = null
    private var isBound = false
    private var pendingRoute: Triple<Route, List<RoutePoint>, Double>? = null

    private val stateObserver = Observer<NavigationState> { renderState(it) }
    private val eventObserver = Observer<Event<NavigationEvent>> { handleEvent(it) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as NavigationService.LocalBinder).service
            navService = service
            isBound = true
            service.state.observe(this@MainActivity, stateObserver)
            service.events.observe(this@MainActivity, eventObserver)
            pendingRoute?.let { (route, points, progress) -> service.startNavigation(route, points, progress) }
            pendingRoute = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            navService = null
            isBound = false
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            enableLocation()
            // Only call site is the upload FAB; a first-time grant should open the picker right away
            // rather than making the user tap Load GPX a second time.
            openGpxLauncher.launch(arrayOf("*/*"))
        } else {
            Toast.makeText(this, "Location permission needed", Toast.LENGTH_LONG).show()
        }
        // Notification permission denial is non-fatal: navigation still runs, just without a visible notification.
    }

    private val openGpxLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { loadGpxFromUri(it) } }

    private val lastRouteFile by lazy { File(filesDir, "last_route.gpx") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid requires a User-Agent per OSM tile usage policy.
        Configuration.getInstance().userAgentValue = packageName

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.map.setTileSource(TileSourceFactory.MAPNIK)
        b.map.setMultiTouchControls(true)
        applyMapNightFilter()
        b.map.controller.setZoom(6.0)
        b.map.controller.setCenter(GeoPoint(51.16, 10.45)) // roughly the center of Germany

        b.btnLoadGpx.setOnClickListener {
            if (hasLocationPermissions()) openGpxLauncher.launch(arrayOf("*/*"))
            else permLauncher.launch(requiredPermissions())
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

        b.btnStopNav.setOnClickListener { navService?.stopNavigation() }

        if (hasLocationPermission()) enableLocation()

        restoreLastRoute()
    }

    override fun onStart() {
        super.onStart()
        if (!isBound && prefs.getBoolean(NavigationService.KEY_NAV_ACTIVE, false)) {
            bindNavigationService()
        }
    }

    override fun onStop() {
        super.onStop()
        // Unbinding must not stop navigation: NavigationService was also *started* (startForegroundService),
        // so it keeps running GPS + the notification with nobody bound to it. We do stop forcing the screen
        // on though, since the whole point of that flag is to keep a *visible* activity's screen lit.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            navService = null
        }
    }

    private fun bindNavigationService() {
        if (isBound) {
            pendingRoute?.let { (route, points, progress) -> navService?.startNavigation(route, points, progress) }
            pendingRoute = null
            return
        }
        bindService(Intent(this, NavigationService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /** Starts (or restarts, for a newly loaded route) the foreground service and hands it the parsed
     *  route once bound. Also where the one-time battery-optimization prompt is triggered, since this is
     *  the actual moment navigation begins. [initialDistanceAlongRoute] resumes progress after a restart. */
    private fun beginNavigation(route: Route, points: List<RoutePoint>, initialDistanceAlongRoute: Double) {
        pendingRoute = Triple(route, points, initialDistanceAlongRoute)
        ContextCompat.startForegroundService(this, Intent(this, NavigationService::class.java))
        bindNavigationService()
        maybePromptBatteryOptimization()
    }

    /** FLAG_KEEP_SCREEN_ON only while navigation is actually active and this Activity is visible: renderState
     *  only fires while bound (i.e. between onStart/onStop), so this and the onStop clear above cover both
     *  "navigation stopped" and "activity no longer visible" without needing to track them separately. */
    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Requested once per install, ever - regardless of whether the user allows or declines: if they
     *  decline, we must not nag again, and if they allow, isIgnoringBatteryOptimizations() will already
     *  be true next time so there's nothing to ask. */
    private fun maybePromptBatteryOptimization() {
        if (prefs.getBoolean(KEY_BATTERY_OPT_PROMPTED, false)) return
        prefs.edit().putBoolean(KEY_BATTERY_OPT_PROMPTED, true).apply()
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            // OEM without this settings screen; nothing more we can do.
        }
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        return perms.toTypedArray()
    }

    /** Gates opening the GPX picker: location is what navigation actually needs. Deliberately excludes
     *  POST_NOTIFICATIONS - that one's requested alongside it (see [requiredPermissions]) but its denial is
     *  non-fatal (navigation still runs, just without a visible notification), and on API 33+ a permanently
     *  denied notification permission would otherwise make [requiredPermissions] un-grantable forever,
     *  silently stranding this gate with no dialog left to show. */
    private fun hasLocationPermissions() = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
    ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /** The blue dot: fed by [relayProvider], which NavigationService's location fixes are pushed into,
     *  rather than a second independent GpsMyLocationProvider polling GPS redundantly in the Activity. */
    private fun enableLocation() {
        if (myLocationOverlay == null) {
            val overlay = MyLocationNewOverlay(relayProvider, b.map)
            overlay.enableMyLocation()
            b.map.overlays.add(overlay)
            myLocationOverlay = overlay
        }
    }

    /** Follows the system day/night setting for the map tiles themselves - osmdroid always renders the
     *  (light) OSM tile set, so dark mode needs a color filter rather than a different tile source. Called
     *  from onCreate; a system theme change recreates the Activity (default DayNight behavior, same as a
     *  rotation), and this re-picks the right filter on the way back up while NavigationService keeps
     *  running underneath, so navigation state isn't lost. */
    private fun applyMapNightFilter() {
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        b.map.overlayManager.tilesOverlay.setColorFilter(if (isNight) darkTileColorFilter() else null)
        b.map.invalidate()
    }

    /** Standard osmdroid dark-tile trick: invert the tile colors, then rotate hue back on the blue axis -
     *  a plain invert alone turns the light OSM basemap into a jarring orange/purple; this pulls it back
     *  to the recognizable dark-navy look. Verified on-device; rotating all three color axes instead
     *  (the "more correct" reading of this technique) looks worse, not better. */
    private fun darkTileColorFilter(): ColorMatrixColorFilter {
        val negative = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val hueRotate = ColorMatrix().apply { setRotate(2, 180f) }
        negative.postConcat(hueRotate)
        return ColorMatrixColorFilter(negative)
    }

    /** Reloads whichever GPX was persisted at last shutdown - a copy in app-internal storage, not a
     *  content Uri: URI grants can lapse (provider revokes it, app data cleared oddly, etc.) in ways a
     *  plain file we own outright doesn't, and the whole point here is surviving a reboot reliably. */
    private fun restoreLastRoute() {
        if (!lastRouteFile.exists()) return
        Thread {
            try {
                val text = lastRouteFile.readText()
                val points = GpxParser.parsePoints(text)
                val route = Route(points)
                runOnUiThread { onGpxLoaded(points, route, isRestore = true) }
            } catch (e: Exception) {
                lastRouteFile.delete()
            }
        }.start()
    }

    /** Reading + parsing runs off the main thread: the URI may point at a cloud-backed provider
     *  (Drive, Dropbox, ...) whose openInputStream() can block on network I/O. */
    private fun loadGpxFromUri(uri: Uri) {
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: throw IllegalArgumentException("Could not read file.")
                val points = GpxParser.parsePoints(text)
                val route = Route(points)
                lastRouteFile.writeText(text)
                runOnUiThread { onGpxLoaded(points, route, isRestore = false) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "GPX error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun onGpxLoaded(points: List<RoutePoint>, route: Route, isRestore: Boolean) {
        if (!isRestore) Toast.makeText(this, "Loaded ${points.size} points", Toast.LENGTH_SHORT).show()

        if (isRestore) {
            // If onStart() already reconnected to a service that's still actively navigating this
            // session, don't clobber its progress by restarting from scratch - just let that bind keep
            // rendering it.
            if (navService?.state?.value?.isNavigating == true) return
            if (!prefs.getBoolean(NavigationService.KEY_NAV_ACTIVE, false)) {
                // A route is persisted but navigation wasn't left active: show it, don't auto-start.
                renderState(NavigationState(route = route, routePoints = points, isNavigating = false))
                return
            }
            val progress = prefs.getFloat(NavigationService.KEY_NAV_PROGRESS_METERS, 0f).toDouble()
            beginNavigation(route, points, progress)
        } else {
            beginNavigation(route, points, 0.0)
        }
    }

    private fun renderState(state: NavigationState) {
        setKeepScreenOn(state.isNavigating)
        b.btnStopNav.visibility = if (state.isNavigating) View.VISIBLE else View.GONE

        syncRouteOverlay(state.routePoints)
        drawRerouteOverlay(state.rerouteRoadPoints)
        state.location?.let { relayProvider.push(it) }

        if (state.isGpsLost) {
            b.navBanner.visibility = View.VISIBLE
            b.navBanner.setBackgroundResource(R.drawable.banner_bg_gpslost)
            b.tvInstruction.text = "GPS signal lost"
            b.tvDistance.text = "Holding last known position"
            b.laneRow.visibility = View.GONE
            b.laneRow.removeAllViews()
            return
        }

        val update = state.update
        if (update == null) {
            val route = state.route
            if (route != null) {
                b.navBanner.visibility = View.VISIBLE
                b.navBanner.setBackgroundResource(R.drawable.banner_bg)
                b.tvInstruction.text = "Route loaded"
                b.tvDistance.text = "${"%.1f".format(route.totalDistance / 1000.0)} km, ${route.maneuvers.size} turns"
            }
            return
        }

        val location = state.location
        if (location != null && state.routePoints != null) {
            updateRouteSplit(state.routePoints, location.latitude, location.longitude, update.segmentIndex)
        }

        if (state.hasArrived) {
            b.navBanner.setBackgroundResource(R.drawable.banner_bg)
            b.tvInstruction.text = "Arrived"
            b.tvDistance.text = "You've reached your destination"
            b.laneRow.visibility = View.GONE
            b.laneRow.removeAllViews()
        } else if (update.isOffRoute) {
            b.navBanner.setBackgroundResource(R.drawable.banner_bg_offroute)
            b.tvInstruction.text = "Off route"
            b.tvDistance.text = if (state.rerouteRoadPoints != null)
                "Follow path back · ${formatDistance(update.offRouteMeters)}"
            else
                "Head back · ${formatDistance(update.offRouteMeters)}"
            if (location != null) {
                val bearingToRoute = rerouteBearing(location, update, state.rerouteRoadPoints)
                val heading = if (location.hasBearing() && location.speed >= MIN_SPEED_FOR_BEARING_MPS)
                    location.bearing else smoothedBearing ?: 0f
                b.ivArrow.setImageResource(R.drawable.ic_arrow_straight)
                b.ivArrow.rotation = ((bearingToRoute - heading + 360.0) % 360.0).toFloat()
            }
            b.laneRow.visibility = View.GONE
            b.laneRow.removeAllViews()
        } else {
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
            updateLaneRow(update, state.laneInfoByManeuver)
        }
        b.navBanner.visibility = View.VISIBLE
        if (location != null) updateCamera(location, update)
    }

    /** Arrived is the only event this shell reacts to today; ManeuverChanged/OffRouteEntered/OffRouteExited
     *  exist for a future TTS layer to announce - [NavigationState] already renders all of them continuously. */
    private fun handleEvent(event: Event<NavigationEvent>) {
        val evt = event.getIfNotHandled() ?: return
        if (evt is NavigationEvent.Arrived) {
            Toast.makeText(this, "Arrived at destination", Toast.LENGTH_LONG).show()
        }
    }

    /** (Re)creates the traveled/remaining polylines whenever the route changes - including when this
     *  Activity is freshly recreated (rotation) and rebinds to a service that's already navigating. */
    private fun syncRouteOverlay(routePoints: List<RoutePoint>?) {
        if (routePoints === polylineRoutePoints) return
        traveledPolyline?.let { b.map.overlays.remove(it) }
        remainingPolyline?.let { b.map.overlays.remove(it) }
        traveledPolyline = null
        remainingPolyline = null
        polylineRoutePoints = routePoints
        if (routePoints == null) {
            b.map.invalidate()
            return
        }

        val geoPoints = routePoints.map { GeoPoint(it.lat, it.lon) }
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
    }

    /** Splits the route polyline at the live GPS fix: the point nearest to us on the recorded track is
     *  effectively replaced by our actual position, so the traveled/remaining seam never shows a gap. */
    private fun updateRouteSplit(points: List<RoutePoint>, currentLat: Double, currentLon: Double, segmentIndex: Int) {
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

    private fun drawRerouteOverlay(points: List<RoutePoint>?) {
        if (points === lastRerouteRoadPoints) return
        lastRerouteRoadPoints = points
        rerouteOverlay?.let { b.map.overlays.remove(it) }
        rerouteOverlay = null
        if (points == null) {
            b.map.invalidate()
            return
        }
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

    /** Bearing to head right now while off-route: along the fetched road path once we have one
     *  (aimed a little ahead so the arrow reflects the next street to take, not just the snapped start
     *  point), falling back to a straight line to the route if OSRM hasn't answered yet. */
    private fun rerouteBearing(location: Location, update: NavUpdate, road: List<RoutePoint>?): Double {
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

    private fun updateLaneRow(update: NavUpdate, laneInfoByManeuver: Map<Int, LaneInfo>) {
        val info = if (update.isFinalLeg) null else laneInfoByManeuver[update.maneuverIndex]
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

    override fun onResume() {
        super.onResume()
        b.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        b.map.onPause()
    }

    override fun onDestroy() {
        myLocationOverlay?.disableMyLocation()
        super.onDestroy()
    }

    /** Feeds the existing MyLocationNewOverlay (blue dot) from locations NavigationService already
     *  retrieved, instead of running a second independent GpsMyLocationProvider in the Activity - GPS
     *  ownership belongs entirely to the service now. */
    private class RelayLocationProvider : IMyLocationProvider {
        private var consumer: IMyLocationConsumer? = null
        private var last: Location? = null

        override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
            consumer = myLocationConsumer
            return true
        }

        override fun stopLocationProvider() { consumer = null }
        override fun getLastKnownLocation(): Location? = last
        override fun destroy() { consumer = null }

        fun push(location: Location) {
            last = location
            consumer?.onLocationChanged(location, this)
        }
    }

    companion object {
        private const val KEY_BATTERY_OPT_PROMPTED = "battery_opt_prompted"
        private const val ARRIVED_THRESHOLD_METERS = 30.0
        private const val BASE_ZOOM = 17.0
        private const val APPROACH_ZOOM = 18.7
        private const val ZOOM_APPROACH_START_METERS = 150.0
        private const val ZOOM_APPROACH_FULL_METERS = 40.0
        private const val CAMERA_ANIMATION_MS = 800L
        private const val MIN_SPEED_FOR_BEARING_MPS = 1.0f
        private const val BEARING_SMOOTHING_ALPHA = 0.35f
        private const val MIN_REROUTE_AIM_METERS = 15.0
    }
}
