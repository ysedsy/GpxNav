package com.example.gpxnav

import android.location.Location

/**
 * Snapshot of navigation as of the latest processed GPS fix, posted by [NavigationService] and rendered
 * by whatever is currently observing it (today: MainActivity's map/banner; nothing stops a future TTS
 * layer from observing the same LiveData to decide what to say, without needing its own GPS/session state).
 */
data class NavigationState(
    val route: Route? = null,
    val routePoints: List<RoutePoint>? = null,
    val location: Location? = null,
    val update: NavUpdate? = null,
    val laneInfoByManeuver: Map<Int, LaneInfo> = emptyMap(),
    val rerouteRoadPoints: List<RoutePoint>? = null,
    val isNavigating: Boolean = false,
    val hasArrived: Boolean = false,
    val isGpsLost: Boolean = false
)

/**
 * Discrete, fire-once transitions worth reacting to once rather than on every [NavigationState] tick -
 * e.g. a TTS layer should speak an instruction once per [ManeuverChanged], not once per GPS fix. Delivered
 * as [Event] so a fresh observer (a rebind after rotation, or a TTS layer subscribing later) doesn't replay
 * history.
 */
sealed class NavigationEvent {
    data class ManeuverChanged(val update: NavUpdate) : NavigationEvent()
    object OffRouteEntered : NavigationEvent()
    object OffRouteExited : NavigationEvent()
    object Arrived : NavigationEvent()
    object GpsLost : NavigationEvent()
    object GpsRecovered : NavigationEvent()
}

/** LiveData "consume-once" wrapper: without it, every new observer would immediately replay whatever
 *  event was last posted, which is correct for continuous state but wrong for a one-shot announcement. */
class Event<out T>(private val content: T) {
    private var handled = false

    fun getIfNotHandled(): T? {
        if (handled) return null
        handled = true
        return content
    }
}
