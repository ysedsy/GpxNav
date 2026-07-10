package com.example.gpxnav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

/**
 * Owns GPS acquisition and [NavigationSession] updates as a foreground service, so a ride continues
 * turn-by-turn even with the screen off or the app backgrounded - none of this can live in MainActivity,
 * since Android suspends an Activity's work soon after it's no longer visible.
 *
 * [MainActivity] binds to render [state]/[events]; unbinding (e.g. the user backgrounds the app) does not
 * stop navigation, since this is a *started* service (via [Intent] to [ContextCompat.startForegroundService])
 * as well as a bound one - it only stops on [ACTION_STOP] or automatically shortly after arrival.
 *
 * [state] and [events] are plain LiveData so anything can subscribe, not just MainActivity - a future
 * TTS layer would observe [events] for the discrete "say this once" moments (a new maneuver, going
 * off-route, losing/recovering GPS, arriving) rather than polling [state] and diffing it by hand.
 */
class NavigationService : Service() {

    private val binder = LocalBinder()
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private var navProvider: GpsMyLocationProvider? = null
    private var navSession: NavigationSession? = null
    private var route: Route? = null
    private var routePoints: List<RoutePoint>? = null
    private var laneInfoByManeuver: Map<Int, LaneInfo> = emptyMap()

    private var rerouteRoadPoints: List<RoutePoint>? = null
    private var rerouteFetchInFlight = false
    private var lastRerouteFetchAtMs = 0L
    private var lastRerouteOriginLat = 0.0
    private var lastRerouteOriginLon = 0.0

    private var lastLocation: Location? = null
    private var lastUpdate: NavUpdate? = null
    private var hasArrived = false
    private var arrivalStopScheduled = false
    private var lastOffRoute = false
    private var lastManeuverIndex = -1

    private var lastFixAtMs = 0L
    private var gpsLost = false

    private val handler = Handler(Looper.getMainLooper())
    private var lastNotifyAtMs = 0L
    private var pendingNotifyRunnable: Runnable? = null

    /** Runs every [GPS_WATCHDOG_INTERVAL_MS] while navigating: fixes normally arrive every ~1s (see
     *  startGps()), so an absence has to be detected on a timer rather than reacting to a fix that never
     *  comes. Self-terminates (stops rescheduling) once navSession is null. Assigned in onCreate (rather
     *  than at the property site) purely because a val can't reference itself in its own initializer. */
    private lateinit var gpsWatchdog: Runnable

    private val _state = MutableLiveData(NavigationState())
    val state: LiveData<NavigationState> get() = _state
    private val _events = MutableLiveData<Event<NavigationEvent>>()
    val events: LiveData<Event<NavigationEvent>> get() = _events

    private lateinit var notificationManager: NotificationManager

    inner class LocalBinder : Binder() {
        val service: NavigationService get() = this@NavigationService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        gpsWatchdog = Runnable {
            if (navSession != null) {
                if (!gpsLost && System.currentTimeMillis() - lastFixAtMs > GPS_LOST_TIMEOUT_MS) {
                    gpsLost = true
                    _events.postValue(Event(NavigationEvent.GpsLost))
                    postState()
                    refreshNotification()
                }
                handler.postDelayed(gpsWatchdog, GPS_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNavigation()
            return START_NOT_STICKY
        }
        // Must call startForeground within seconds of Context.startForegroundService() regardless of
        // whether startNavigation() has been wired up yet (that arrives separately, once the caller -
        // MainActivity - has finished parsing the GPX and binds); a generic placeholder covers the gap.
        startForeground(NOTIFICATION_ID, buildNotification("Starting navigation", ""))
        return START_NOT_STICKY
    }

    /** Kicks off navigation for a parsed route: resets all per-route state, starts GPS, and fetches lane
     *  guidance in the background - mirrors what MainActivity used to do in onGpxLoaded.
     *  [initialDistanceAlongRoute] resumes progress (e.g. after an app restart) instead of the route start. */
    fun startNavigation(newRoute: Route, newRoutePoints: List<RoutePoint>, initialDistanceAlongRoute: Double = 0.0) {
        route = newRoute
        routePoints = newRoutePoints
        navSession = NavigationSession(newRoute, initialDistanceAlongRoute)
        laneInfoByManeuver = emptyMap()
        rerouteRoadPoints = null
        lastLocation = null
        lastUpdate = null
        hasArrived = false
        arrivalStopScheduled = false
        lastOffRoute = false
        lastManeuverIndex = -1
        lastFixAtMs = System.currentTimeMillis()
        gpsLost = false

        prefs.edit()
            .putBoolean(KEY_NAV_ACTIVE, true)
            .putFloat(KEY_NAV_PROGRESS_METERS, initialDistanceAlongRoute.toFloat())
            .apply()
        postState()
        startGps()
        handler.removeCallbacks(gpsWatchdog)
        handler.postDelayed(gpsWatchdog, GPS_WATCHDOG_INTERVAL_MS)

        val session = navSession
        Thread {
            val lanes = try { LaneGuidance.fetch(newRoute) } catch (e: Exception) { emptyMap() }
            handler.post {
                if (navSession === session) {
                    laneInfoByManeuver = lanes
                    postState()
                }
            }
        }.start()
    }

    /** Explicit stop: user tapped Stop (in-app or the notification action), or called automatically a
     *  few seconds after arrival. Idempotent - safe to call more than once (e.g. a pending arrival-stop
     *  firing after the user already stopped manually). */
    fun stopNavigation() {
        navProvider?.stopLocationProvider()
        navProvider = null
        navSession = null
        route = null
        routePoints = null
        rerouteRoadPoints = null
        gpsLost = false
        handler.removeCallbacksAndMessages(null)
        pendingNotifyRunnable = null

        prefs.edit().putBoolean(KEY_NAV_ACTIVE, false).apply()
        _state.postValue(NavigationState())

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startGps() {
        if (navProvider != null) return
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

    private fun onLocationUpdate(location: Location) {
        val session = navSession ?: return
        lastFixAtMs = System.currentTimeMillis()
        if (gpsLost) {
            gpsLost = false
            _events.postValue(Event(NavigationEvent.GpsRecovered))
        }

        val update = session.update(location.latitude, location.longitude)

        if (update.isOffRoute) {
            maybeFetchReroute(location, update, session)
        } else if (rerouteRoadPoints != null) {
            rerouteRoadPoints = null
        }

        val arrived = update.isFinalLeg &&
            update.distanceToEvent < ARRIVED_THRESHOLD_METERS &&
            update.distanceToDestinationMeters < ARRIVED_THRESHOLD_METERS
        lastLocation = location
        lastUpdate = update
        hasArrived = arrived
        prefs.edit().putFloat(KEY_NAV_PROGRESS_METERS, update.distanceAlongRoute.toFloat()).apply()
        postState()
        emitEvents(update, arrived)
        refreshNotification()

        if (arrived && !arrivalStopScheduled) {
            arrivalStopScheduled = true
            handler.postDelayed({ stopNavigation() }, ARRIVED_STOP_DELAY_MS)
        }
    }

    private fun emitEvents(update: NavUpdate, arrived: Boolean) {
        if (update.isOffRoute && !lastOffRoute) _events.postValue(Event(NavigationEvent.OffRouteEntered))
        if (!update.isOffRoute && lastOffRoute) _events.postValue(Event(NavigationEvent.OffRouteExited))
        lastOffRoute = update.isOffRoute

        if (!update.isOffRoute && !arrived && update.maneuverIndex != lastManeuverIndex) {
            lastManeuverIndex = update.maneuverIndex
            _events.postValue(Event(NavigationEvent.ManeuverChanged(update)))
        }
        if (arrived && lastManeuverIndex != ARRIVED_MANEUVER_MARKER) {
            lastManeuverIndex = ARRIVED_MANEUVER_MARKER
            _events.postValue(Event(NavigationEvent.Arrived))
        }
    }

    private fun postState() {
        _state.postValue(
            NavigationState(
                route = route,
                routePoints = routePoints,
                location = lastLocation,
                update = lastUpdate,
                laneInfoByManeuver = laneInfoByManeuver,
                rerouteRoadPoints = rerouteRoadPoints,
                isNavigating = navSession != null,
                hasArrived = hasArrived,
                isGpsLost = gpsLost
            )
        )
    }

    /** Same road-based reroute logic MainActivity used to run directly; moved here since it's part of
     *  live navigation state, not UI. See RerouteService for the actual street-distance-based decision. */
    private fun maybeFetchReroute(location: Location, update: NavUpdate, session: NavigationSession) {
        if (rerouteFetchInFlight) return
        val now = System.currentTimeMillis()
        if (now - lastRerouteFetchAtMs < REROUTE_MIN_INTERVAL_MS) return
        val movedFar = GeoMath.haversineMeters(
            location.latitude, location.longitude, lastRerouteOriginLat, lastRerouteOriginLon
        ) > REROUTE_REFETCH_DISTANCE_METERS
        if (rerouteRoadPoints != null && !movedFar) return

        rerouteFetchInFlight = true
        lastRerouteFetchAtMs = now
        lastRerouteOriginLat = location.latitude
        lastRerouteOriginLon = location.longitude
        val r = session.route
        val fromLat = location.latitude
        val fromLon = location.longitude
        val nearestDistanceAlongRoute = update.distanceAlongRoute
        Thread {
            val result = RerouteService.fetchReroute(r, fromLat, fromLon, nearestDistanceAlongRoute)
            handler.post {
                rerouteFetchInFlight = false
                if (navSession === session && result != null) {
                    rerouteRoadPoints = result.roadPoints
                    session.applyRerouteTarget(result.rejoinDistanceAlongRoute)
                    postState()
                }
            }
        }.start()
    }

    /** Rate-limits notification updates to at most once per second: if we're inside the window, let an
     *  already-scheduled runnable pick up whatever's current by the time it fires rather than scheduling
     *  a pile of them (the text itself is always recomputed fresh from current fields, never captured). */
    private fun refreshNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAtMs >= NOTIFY_MIN_INTERVAL_MS) {
            notifyNow()
            return
        }
        if (pendingNotifyRunnable == null) {
            val runnable = Runnable {
                pendingNotifyRunnable = null
                notifyNow()
            }
            pendingNotifyRunnable = runnable
            handler.postDelayed(runnable, NOTIFY_MIN_INTERVAL_MS - (now - lastNotifyAtMs))
        }
    }

    private fun notifyNow() {
        lastNotifyAtMs = System.currentTimeMillis()
        val (title, body) = currentNotificationText()
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, body))
    }

    /** GPS_LOST takes priority over everything else - it means the rest of this text would be stale. */
    private fun currentNotificationText(): Pair<String, String> {
        if (gpsLost) return "GPS signal lost" to "Holding last known position"
        val update = lastUpdate ?: return "Starting navigation" to ""
        return when {
            hasArrived -> "Arrived" to "You've reached your destination"
            update.isOffRoute -> "Off route" to "Head back · ${formatDistance(update.offRouteMeters)}"
            update.isFinalLeg -> instructionText(update) to "Continue to destination"
            else -> instructionText(update) to "in ${formatDistance(update.distanceToEvent)}"
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, body: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        navProvider?.stopLocationProvider()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.gpxnav.action.STOP"
        const val PREFS_NAME = "gpxnav"
        const val KEY_NAV_ACTIVE = "nav_active"
        const val KEY_NAV_PROGRESS_METERS = "nav_progress_meters"

        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 1
        private const val ARRIVED_THRESHOLD_METERS = 30.0
        private const val ARRIVED_STOP_DELAY_MS = 4000L
        private const val ARRIVED_MANEUVER_MARKER = Int.MIN_VALUE
        private const val NOTIFY_MIN_INTERVAL_MS = 1000L
        private const val REROUTE_MIN_INTERVAL_MS = 8000L
        private const val REROUTE_REFETCH_DISTANCE_METERS = 30.0
        private const val GPS_LOST_TIMEOUT_MS = 10_000L
        private const val GPS_WATCHDOG_INTERVAL_MS = 3000L
    }
}
