# Destination search & routing design

## Context

InkRide can currently follow a route only if the rider has a pre-made GPX
file to load (`GpxRouteLoader` → `RideTracker.loadRoute()`, wired to the
`AltRoute` icon in `DashboardTopBar`). There is no way to type a place name
and have the app find it and compute a route there — the app has never made
an outbound network call of its own (the only existing network use is
`osmdroid`'s internal tile fetching for the post-ride map in
`feature/history/presentation/RideRouteMap.kt`, which pulls grayscale Mapnik
tiles for E-Ink legibility).

Goal: let the rider type a destination, pick it from a short list of
matches, and have InkRide fetch a cycling route to it and start following it
— reusing the entire route-following stack built for GPX (`PlannedRoute`,
`RouteFollower`, `RouteBar`'s turn arrow) unchanged.

Explicitly out of scope for this design:
- Turn-by-turn voice/haptic guidance beyond what already exists (the arrow +
  distance readout).
- Waypoint-based multi-stop routes, alternate-route choice, or route
  re-calculation when the rider goes off the routed path (off-route
  detection already exists and is unaffected).
- Self-hosting a routing/geocoding backend — see "Services" below for why
  the public OSM-ecosystem servers are the right starting point and the
  documented upgrade path.
- Choosing an arbitrary start point for the route. The start is always the
  rider's current position.

## Services

**Geocoding — Nominatim** (`nominatim.openstreetmap.org`), keyless, run by
the OSM Foundation. Its
[usage policy](https://operations.osmfoundation.org/policies/nominatim/)
explicitly permits end-user-triggered search from an app ("Use that is
directly triggered by the end-user... is ok, provided that your number of
users is moderate") — exactly this feature's shape (one search per typed
query, not bulk/scripted). Absolute ceiling 1 req/sec; requires a
non-default `User-Agent` identifying the app, which `RideRouteMap.kt`
already sets for `osmdroid` (`Configuration.userAgentValue = ctx.packageName`)
— same value, new call site.

**Routing — OSRM demo server** (`router.project-osrm.org`), keyless, runs
car/foot/**bike** profiles worldwide, FOSSGIS-sponsored. Its `/route`
endpoint (`steps=true`) returns, per turn, a structured `maneuver.modifier`
(`left`/`right`/`straight`/`slight left`/...) *and* the route geometry as an
ordered point list. Because those step points are literally vertices of the
same polyline, InkRide's existing geometry-based `TurnDirection` classifier
in `RouteFollower` reproduces the same direction without reading
`maneuver.modifier` at all — **no changes needed in `:core:domain`**. One
turn-classification code path serves both GPX-loaded and routed trips.

Both are free, keyless, and already in the OSM ecosystem this app depends
on for map tiles — consistent with `project_overview.md`'s "prioritize
OpenStreetMap (OSM) and other open-source providers" and the de-googled
constraint (no Google Places/Directions). Caveat to carry forward: the demo
server has no uptime SLA ("reasonable, non-commercial use" only, ≤1
req/sec) — if that becomes a reliability problem, the natural upgrade is a
self-hosted OSRM instance behind the same `RoutingService` interface, no
call-site changes required.

## Current-position gap

`RideTracker`'s entire sample pipeline is gated on ride status:

```kotlin
sensorDataSource.observeSamples().collect { sample ->
    val statusBefore = _state.value.status
    if (statusBefore == TrackingStatus.IDLE) return@collect
    ...
```

So today the app has **no live position at all** before the rider taps
START — confirmed on-device (dashboard showed "GPS accuracy: Poor (--)"
with a fixed emulator geo location until tracking was started). Searching
for a destination is squarely a *before-the-ride* action (GPX loading
already works at any status), so this feature needs its own one-shot
"where am I right now" lookup, decoupled from `RideTracker`'s gated
pipeline — not a reuse of it.

## Architecture

**New module `:feature:dashboard:data`** (dashboard currently has only
`:presentation`; this is its first real data-layer concern beyond the
Compose-adjacent `GpxRouteLoader`). Built with `inkride.android.library`
(no Compose needed here).

New dependencies (none of these exist in the project today):
- Ktor client core + an engine (OkHttp) for the two HTTP calls.
- `kotlinx-serialization-json` content negotiation — the JSON library
  itself is already a dependency (used for nav routes' `@Serializable`
  objects), so this only adds the Ktor integration artifact.

**New interfaces in `:core:domain`** (`tracking` package, next to
`PlannedRoute`/`RouteFollower` — same "produces a `PlannedRoute`" family):

```kotlin
interface PlaceSearchService {
    suspend fun search(query: String): Result<List<PlaceResult>, PlaceSearchError>
}

data class PlaceResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
)

enum class PlaceSearchError : Error { NETWORK_FAILED, NO_RESULTS }

interface RoutingService {
    suspend fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<PlannedRoute, RoutingError>
}

enum class RoutingError : Error { NETWORK_FAILED, NO_ROUTE_FOUND }

interface CurrentLocationProvider {
    suspend fun getCurrentLocation(): Result<LocationFix, LocationError>
}

data class LocationFix(val latitude: Double, val longitude: Double)

enum class LocationError : Error { PERMISSION_DENIED, TIMED_OUT, PROVIDER_UNAVAILABLE }
```

Same `Error`-sealed-enum shape as `GpxLoadError`/`SensorError` already in
the codebase — no new error-handling pattern introduced.

**Implementations in `:feature:dashboard:data`:**
- `NominatimPlaceSearchService` — `GET /search?q=...&format=json&limit=8`
  with the shared `User-Agent`; maps `[{lat, lon, display_name}]` → `List<PlaceResult>`;
  empty array → `PlaceSearchError.NO_RESULTS`.
- `OsrmRoutingService` — `GET /route/v1/bike/{lon1},{lat1};{lon2},{lat2}?geometries=geojson&steps=true&overview=full`;
  maps `routes[0].geometry.coordinates` → `PlannedRoute.points`. Each step
  whose `maneuver.type` is neither `depart` (no turn happens at the start)
  nor `arrive` (no turn at the destination either — `RouteFollower`'s
  existing "distance to route end" fallback already covers the final leg)
  becomes a `RouteWaypoint` from that step's `maneuver.location` + `name`.
  `code != "Ok"` or empty `routes` → `RoutingError.NO_ROUTE_FOUND`.
- `AndroidCurrentLocationProvider` — one-shot fix via
  `LocationManager.getCurrentLocation(GPS_PROVIDER, ...)` (API 30 path) with
  a `requestSingleUpdate` fallback for API 26–29 (project `minSdk` is 26);
  standard `LocationManager`, no GMS/Fused location, matching the existing
  de-googled constraint already applied to `AndroidRideSensorDataSource`.

## UI flow

1. `DashboardTopBar`'s route-icon action changes from directly launching the
   GPX file picker to opening a `ModalBottomSheetMMD` with two choices:
   "Load GPX file" (existing `routePicker.launch(...)` flow, unchanged) and
   "Search destination" (new). The "clear route" behavior (icon swaps to
   `Close` when a route is active) is unchanged.
2. "Search destination" dismisses the sheet and navigates to a new
   `DestinationSearchRoute` (added to
   `core/domain/navigation/NavigationRoutes.kt`, wired into
   `dashboardGraph` in `DashboardNavigation.kt`, same pattern as every
   other route).
3. `DestinationSearchScreen` — a text field plus a scrollable results list.
   Typing debounces **600 ms** after the last keystroke and requires
   **≥3 characters** before calling `PlaceSearchService.search()` — this
   keeps requests within Nominatim's 1 req/sec ceiling and avoids
   refreshing the E-Ink screen on every keystroke, while still giving the
   live-suggestions UX the rider asked for. Each new query cancels any
   in-flight one (`Flow.debounce` + `collectLatest`).
4. Tapping a result: show a loading state, call
   `CurrentLocationProvider.getCurrentLocation()`, then
   `RoutingService.route(...)` with that origin and the picked
   `PlaceResult`'s coordinates as destination. On success, call
   `RideTracker.loadRoute(route)` (the exact call `DashboardViewModel`
   already makes after a GPX parse) and navigate back to the dashboard —
   `RouteBar`, the turn arrow, and off-route detection all pick this up
   with zero changes, since they only ever see a `PlannedRoute`.
5. Any failure (`LocationError`, `PlaceSearchError`, `RoutingError`) surfaces
   as a `DestinationSearchEvent.ShowError(UiText)` — same
   `Channel`/`ObserveAsEvents` mechanism already used for GPX load errors —
   and leaves the rider on the search screen to retry, rather than
   navigating away.

## MVI contract

New file `DestinationSearchContract.kt`, following the existing split-file
pattern (`DashboardContract.kt`, `OnboardingContract.kt`):

```kotlin
data class DestinationSearchState(
    val query: String = "",
    val results: List<PlaceResult> = emptyList(),
    val isSearching: Boolean = false,
    val isRouting: Boolean = false,
)

sealed interface DestinationSearchAction {
    data class OnQueryChanged(val query: String) : DestinationSearchAction
    data class OnResultSelected(val result: PlaceResult) : DestinationSearchAction
}

sealed interface DestinationSearchEvent {
    data class ShowError(val message: UiText) : DestinationSearchEvent
    data object NavigateBackToDashboard : DestinationSearchEvent
}
```

`DestinationSearchViewModel` depends on `PlaceSearchService`,
`RoutingService`, `CurrentLocationProvider`, and `RideTracker` (already a
Koin singleton, same as `DashboardViewModel`). Screen split as
`DestinationSearchRoot` (wires VM, `ObserveAsEvents`) /
`DestinationSearchScreen` (pure UI: text field + results list). Results
list uses the same custom `verticalScrollbar` already recorded as a
deliberate MMD exception (project memory: MMD scrollbar exception) —
MMD has no list component of its own.

## Localization

New strings (`dashboard_route_load_gpx`, `dashboard_route_search_destination`,
plus `DestinationSearchScreen` copy: search hint, empty-state, loading,
error messages) go in both `feature/dashboard/presentation/src/main/res/values/strings.xml`
and `values-pl/strings.xml`, matching every other string in this module.

## Error handling detail

- `LocationError.PERMISSION_DENIED` — shouldn't normally occur (location
  permission is already primed during onboarding and required for ride
  tracking), but handled defensively the same way `SensorErrorToUiText.kt`
  already handles the equivalent case for ride tracking.
- `PlaceSearchError.NO_RESULTS` — shown inline in the results list as an
  empty state, not as a `ShowError` event (it's an expected outcome of a
  query, not a failure).
- Network failures (`NETWORK_FAILED` on either service) and
  `RoutingError.NO_ROUTE_FOUND` (e.g. destination unreachable by bike, or
  across water) — `ShowError`, rider stays on the search screen.

## Testing

- **Unit** — response-mapping tests for `NominatimPlaceSearchService` and
  `OsrmRoutingService` against fixed JSON fixtures (no network), covering
  the success shape, empty-results, and malformed/error-code responses.
  `DestinationSearchViewModelTest` with fake `PlaceSearchService` /
  `RoutingService` / `CurrentLocationProvider` — query debounce behavior,
  result selection → route → `RideTracker.loadRoute()` call, and each
  error path's event.
- **Instrumented** — a `RideTrackingRouteSearchTest` extending
  `RideTrackingE2ETestBase`, following the same shape as
  `RideTrackingRouteFollowingTest`: fake implementations of the three new
  services swapped in via the existing Koin-module-override pattern (no
  real network calls from CI), driving the real search screen (type a
  query, tap a result) and asserting the dashboard ends up showing the
  same turn-arrow readout `RideTrackingRouteFollowingTest` already proved
  works for a `PlannedRoute` — confirming the new ingestion path feeds the
  same, already-tested pipeline.
