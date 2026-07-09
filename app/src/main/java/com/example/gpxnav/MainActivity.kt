package com.example.gpxnav

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
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
    private var routePolyline: Polyline? = null
    private var navSession: NavigationSession? = null
    private var followMode = true

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
        }

        if (hasLocationPermission()) enableLocation()
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

    private fun loadGpx(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: throw IllegalArgumentException("Could not read file.")
            val points = GpxParser.parsePoints(text)
            val route = Route(points)
            navSession = NavigationSession(route)

            routePolyline?.let { b.map.overlays.remove(it) }
            val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
            val polyline = Polyline().apply {
                setPoints(geoPoints)
                outlinePaint.color = ContextCompat.getColor(this@MainActivity, R.color.nav_accent)
                outlinePaint.strokeWidth = 10f
            }
            b.map.overlays.add(polyline)
            routePolyline = polyline

            b.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geoPoints), true, 100)
            b.map.invalidate()

            b.navBanner.visibility = View.VISIBLE
            b.navBanner.setBackgroundResource(R.drawable.banner_bg)
            b.tvInstruction.text = "Route loaded"
            b.tvDistance.text = "${"%.1f".format(route.totalDistance / 1000.0)} km, ${route.maneuvers.size} turns"

            Toast.makeText(this, "Loaded ${points.size} points", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "GPX error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onLocationUpdate(location: Location) {
        val session = navSession ?: return
        val update = session.update(location.latitude, location.longitude)

        if (update.offRouteMeters > OFF_ROUTE_THRESHOLD_METERS) {
            b.navBanner.setBackgroundResource(R.drawable.banner_bg_offroute)
            b.tvInstruction.text = "Off route"
            b.tvDistance.text = "${update.offRouteMeters.roundToInt()} m from route"
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
        }
        b.navBanner.visibility = View.VISIBLE

        if (followMode) {
            b.map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
        }
    }

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
        private const val OFF_ROUTE_THRESHOLD_METERS = 60.0
        private const val ARRIVED_THRESHOLD_METERS = 30.0
    }
}
