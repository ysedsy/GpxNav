# GpxNav

A minimal, fully open-source Android turn-by-turn navigator for a pre-planned `.gpx` route — built for following a curvy motorcycle route (e.g. from [Kurviger](https://kurviger.de)) without depending on Google Maps or any external routing service.

No API key, no Play Services, no server: map tiles come from [osmdroid](https://github.com/osmdroid/osmdroid) (OpenStreetMap) and GPS comes from the plain Android `LocationManager`. Turn instructions ("turn right in 200 m") are derived purely from the GPX track's own geometry — bearing changes between simplified track vertices — so it works offline with any GPX file, even ones with no route/street metadata.

## Usage

1. Build & install (`Run` in Android Studio, or `./gradlew installDebug` with a device connected).
2. Grant location permission when prompted.
3. Tap the upload FAB and pick a `.gpx` file (track/route/waypoints, in that priority).
4. The route is drawn on the map and zoomed to fit. As you ride, the banner at the top shows the next turn and a live, rotating arrow + distance countdown.
5. Tap the location FAB to toggle map auto-follow.

While following, the map rotates heading-up (like Google Maps turn-by-turn), eases the zoom in as you approach a maneuver, and the route line is split into a traveled/remaining pair anchored to your live GPS fix rather than the static recorded track. When OpenStreetMap has `turn:lanes` tagging for an upcoming junction, a lane row appears in the banner highlighting which lane(s) to use — this is the one feature that needs a network call (a one-shot Overpass API lookup per loaded route); everything else stays fully offline, and most rural/curvy roads simply have no lane tagging, in which case the row just doesn't appear.

Navigation runs in a foreground service, so it keeps going with the screen off or the app backgrounded, and the screen is kept on while the app is in front and actively navigating. The last-loaded GPX is copied into app storage and reopened automatically on the next launch; if navigation was still active, it resumes from the last known progress along the route instead of the start. If GPS is lost for more than 10 seconds mid-ride, the banner and notification switch to a "GPS signal lost" state, holding the last known position/progress (no rerouting, no maneuver skipping) until a fix arrives again. Arrival ("Ziel erreicht") is detected once both the remaining route distance and the straight-line distance to the final point drop under 30 m, after which the service stops automatically. The map, banner, and route colors follow the system's day/night setting, including a dark tile filter for the OSM basemap.

## How turn detection works

1. **Parse** — reads `trkpt` (track log, preferred), falling back to `rtept`, then `wpt`.
2. **Simplify** — Douglas-Peucker reduction (12 m epsilon) turns thousands of noisy track points into the route's real geometric vertices.
3. **Classify** — the bearing change at each retained vertex is bucketed into slight/turn/sharp/U-turn, left or right.
4. **Live match** — each GPS fix is projected onto the nearest route segment (forward-biased search so it stays cheap on long routes) to get progress-along-route and off-route distance; the next maneuver ahead is looked up and its remaining distance is shown.

On a genuinely winding road this will call out every bend (verified against a real 74 km / 250-turn Black Forest route) — by design, since it's a curvy-road companion, not a street-by-street city router. Thresholds live in `NavigationEngine.kt` (`Route.classify`) if that turns out too chatty for a given route.

## Permissions

`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` for GPS, `INTERNET` for OSM tile downloads.

## Ideas for improvement

- Voice/TTS instructions instead of (or alongside) the on-screen banner (the state flow already exposes maneuver/off-route/GPS-loss/arrival as discrete events for this)
