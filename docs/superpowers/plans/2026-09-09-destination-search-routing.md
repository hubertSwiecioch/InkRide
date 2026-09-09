# Destination Search & Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the rider search for a destination by name, pick a match, and have InkRide fetch a cycling route to it and start following it through the existing route-following stack (turn arrow, off-route detection) with zero changes to that stack.

**Architecture:** A new `:feature:dashboard:data` module wraps two keyless public OSM-ecosystem HTTP APIs (Nominatim geocoding, OSRM demo-server bike routing) plus a one-shot Android `LocationManager` lookup, all behind `:core:domain` interfaces. A new `DestinationSearchScreen` collects a query, debounces it, shows matches, and on selection resolves the current position, requests a route, and hands the resulting `PlannedRoute` to the existing `RideTracker.loadRoute()` — the same call the GPX-file flow already makes.

**Tech Stack:** Kotlin, Jetpack Compose + MMD components, Koin DI, Ktor client (OkHttp engine) + kotlinx.serialization for JSON, JUnit5 + assertk + turbine for JVM unit tests, Robolectric for the Android `LocationManager` wrapper, the existing `RideTrackingE2ETestBase` Compose instrumented-test harness for the end-to-end flow.

**Spec:** `docs/superpowers/specs/2026-09-09-destination-search-routing-design.md`

## Global Constraints

- Keyless services only: Nominatim (`nominatim.openstreetmap.org`) for geocoding, OSRM demo server (`router.project-osrm.org`, bike profile) for routing — no API keys, no Google/GMS.
- Nominatim requests must carry a non-default `User-Agent` identifying the app.
- Search input debounces **600 ms** after the last keystroke and requires **≥3 characters** before querying; each new query cancels any in-flight one.
- All new UI strings go in both `feature/dashboard/presentation/src/main/res/values/strings.xml` and `values-pl/strings.xml`.
- Every new function/class gets a JVM unit test written and watched-fail first (TDD), except pure interface/data-class declarations, which carry no independent behavior to test — matching how `RideSensorDataSource`/`BleSensorDataSource` have no dedicated test files of their own in this codebase, only their implementations do.
- `RideTracker` is a concrete, `final` class (not mockable by Mockito without extra setup this project doesn't have) — any test needing one constructs a **real** `RideTracker` wired with small hand-written fakes for its own dependencies, never a mock.
- Follow existing module conventions exactly: `inkride.android.library` for a pure data module, package names mirroring existing siblings (`com.speedevand.inkride.dashboard.data`), Koin `module { }` files named `<feature>DataModule`/`<feature>PresentationModule`.

---

## Task 1: Nominatim place search service

**Files:**
- Create: `feature/dashboard/data/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/PlaceSearchService.kt`
- Create: `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/NominatimPlaceSearchService.kt`
- Create: `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/DashboardDataModule.kt`
- Modify: `app/src/main/java/com/speedevand/inkride/InkRideApp.kt`
- Test: `feature/dashboard/data/src/test/kotlin/com/speedevand/inkride/dashboard/data/NominatimPlaceSearchServiceTest.kt`

**Interfaces:**
- Produces: `interface PlaceSearchService { suspend fun search(query: String): Result<List<PlaceResult>, PlaceSearchError> }`, `data class PlaceResult(val displayName: String, val latitude: Double, val longitude: Double)`, `enum class PlaceSearchError : Error { NETWORK_FAILED, NO_RESULTS }` — all in `:core:domain`, package `com.speedevand.inkride.core.domain.tracking`.
- Produces: `class NominatimPlaceSearchService(userAgent: String, httpClient: HttpClient = ...) : PlaceSearchService` in `:feature:dashboard:data`.
- Produces: `val dashboardDataModule` (Koin `module { }`) in `:feature:dashboard:data`, registered in `InkRideApp.onCreate()`.

- [ ] **Step 1: Add the Ktor version and libraries to the version catalog**

Edit `gradle/libs.versions.toml`. Add to `[versions]` (after `osmdroid = "6.1.20"`):

```toml
ktor = "3.5.2"
```

Add to `[libraries]` (after `osmdroid-android = ...`):

```toml
ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { group = "io.ktor", name = "ktor-client-okhttp", version.ref = "ktor" }
ktor-client-content-negotiation = { group = "io.ktor", name = "ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-mock = { group = "io.ktor", name = "ktor-client-mock", version.ref = "ktor" }
```

- [ ] **Step 2: Scaffold the `:feature:dashboard:data` module**

Add to `settings.gradle.kts`, right after `include(":feature:dashboard:presentation")`:

```kotlin
include(":feature:dashboard:data")
```

Create `feature/dashboard/data/build.gradle.kts`:

```kotlin
plugins {
    id("inkride.android.library")
}

android {
    namespace = "com.speedevand.inkride.dashboard.data"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage.engine)
    testImplementation(libs.ktor.client.mock)
}
```

(`inkride.android.library` already adds `junit-jupiter-api/engine`, `assertk`, `turbine`, `mockito-kotlin`, `kotlinx-coroutines-test` as `testImplementation` — see `build-logic/convention/src/main/kotlin/com/speedevand/inkride/convention/AndroidLibraryConventionPlugin.kt`. Robolectric/androidx-test-core/junit-vintage-engine are added here because Task 3's `AndroidCurrentLocationProvider` test needs them, matching `feature/tracking/data/build.gradle.kts`'s identical block.)

Add to `app/build.gradle.kts`'s `dependencies { }`, right after `implementation(project(":feature:dashboard:presentation"))`:

```kotlin
    implementation(project(":feature:dashboard:data"))
```

- [ ] **Step 3: Add the domain contract**

Create `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/PlaceSearchService.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.Error
import com.speedevand.inkride.core.domain.Result

/** Finds candidate places matching a free-text query (a place name or address). */
interface PlaceSearchService {
    suspend fun search(query: String): Result<List<PlaceResult>, PlaceSearchError>
}

/** A single geocoded search match. */
data class PlaceResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
)

enum class PlaceSearchError : Error {
    /** The request failed: no connectivity, timeout, or a non-2xx response. */
    NETWORK_FAILED,

    /** The query matched no places. */
    NO_RESULTS,
}
```

No test for this file — it's an interface and two plain data declarations, no behavior to exercise (same as `RideSensorDataSource`, `BleSensorDataSource` elsewhere in `:core:domain`). Its shape is proven by Step 5's failing test instead.

- [ ] **Step 4: Write the failing test for `NominatimPlaceSearchService`**

Create `feature/dashboard/data/src/test/kotlin/com/speedevand/inkride/dashboard/data/NominatimPlaceSearchServiceTest.kt`:

```kotlin
package com.speedevand.inkride.dashboard.data

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class NominatimPlaceSearchServiceTest {
    private fun clientReturning(
        status: HttpStatusCode,
        body: String,
    ): HttpClient =
        HttpClient(
            MockEngine { _ ->
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            // Mirrors NominatimPlaceSearchService's own default client config, so
            // this test's fixtures behave the same as the real one under a real
            // (much larger) Nominatim response.
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    @Test
    fun `search maps a successful response into PlaceResult`() =
        runTest {
            val json = """[{"lat":"52.2297","lon":"21.0122","display_name":"Warsaw, Poland"}]"""
            val service = NominatimPlaceSearchService("InkRide-test", clientReturning(HttpStatusCode.OK, json))

            val result = service.search("Warsaw")

            assertThat(result).isEqualTo(
                Result.Success(listOf(com.speedevand.inkride.core.domain.tracking.PlaceResult("Warsaw, Poland", 52.2297, 21.0122))),
            )
        }

    @Test
    fun `search returns NO_RESULTS for an empty match list`() =
        runTest {
            val service = NominatimPlaceSearchService("InkRide-test", clientReturning(HttpStatusCode.OK, "[]"))

            val result = service.search("zzzzzznotaplace")

            assertThat(result).isEqualTo(Result.Error(PlaceSearchError.NO_RESULTS))
        }

    @Test
    fun `search returns NETWORK_FAILED on a server error`() =
        runTest {
            val service = NominatimPlaceSearchService("InkRide-test", clientReturning(HttpStatusCode.InternalServerError, ""))

            val result = service.search("Warsaw")

            assertThat(result).isEqualTo(Result.Error(PlaceSearchError.NETWORK_FAILED))
        }
}
```

- [ ] **Step 5: Run it, verify it fails to compile (the class doesn't exist yet)**

Run: `./gradlew :feature:dashboard:data:test --tests "com.speedevand.inkride.dashboard.data.NominatimPlaceSearchServiceTest"`
Expected: FAILED — `compileTestKotlin` fails with "Unresolved reference: NominatimPlaceSearchService".

- [ ] **Step 6: Implement `NominatimPlaceSearchService`**

Create `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/NominatimPlaceSearchService.kt`:

```kotlin
package com.speedevand.inkride.dashboard.data

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Geocodes a free-text query via the OSM Foundation's public Nominatim search
 * API (nominatim.openstreetmap.org), keyless. Its usage policy requires a
 * non-default User-Agent identifying the calling app, and explicitly permits
 * this shape of use: one search directly triggered by the end user typing a
 * query, not bulk/scripted lookups.
 */
class NominatimPlaceSearchService(
    private val userAgent: String,
    private val httpClient: HttpClient =
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        },
) : PlaceSearchService {
    override suspend fun search(query: String): Result<List<PlaceResult>, PlaceSearchError> {
        val httpResponse =
            try {
                httpClient.get("https://nominatim.openstreetmap.org/search") {
                    header(HttpHeaders.UserAgent, userAgent)
                    parameter("q", query)
                    parameter("format", "json")
                    parameter("limit", "8")
                }
            } catch (e: Exception) {
                return Result.Error(PlaceSearchError.NETWORK_FAILED)
            }

        if (!httpResponse.status.isSuccess()) {
            return Result.Error(PlaceSearchError.NETWORK_FAILED)
        }

        val results =
            try {
                httpResponse.body<List<NominatimResult>>()
            } catch (e: Exception) {
                return Result.Error(PlaceSearchError.NETWORK_FAILED)
            }

        if (results.isEmpty()) return Result.Error(PlaceSearchError.NO_RESULTS)

        return Result.Success(
            results.mapNotNull { result ->
                val lat = result.lat.toDoubleOrNull() ?: return@mapNotNull null
                val lon = result.lon.toDoubleOrNull() ?: return@mapNotNull null
                PlaceResult(displayName = result.displayName, latitude = lat, longitude = lon)
            },
        )
    }
}

@Serializable
private data class NominatimResult(
    val lat: String,
    val lon: String,
    @SerialName("display_name") val displayName: String,
)
```

- [ ] **Step 7: Run the test, verify it passes**

Run: `./gradlew :feature:dashboard:data:test --tests "com.speedevand.inkride.dashboard.data.NominatimPlaceSearchServiceTest"`
Expected: PASS, 3 tests.

- [ ] **Step 8: Wire the Koin module and register it in the app**

Create `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/DashboardDataModule.kt`:

```kotlin
package com.speedevand.inkride.dashboard.data

import com.speedevand.inkride.core.domain.tracking.PlaceSearchService
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dashboardDataModule =
    module {
        single<PlaceSearchService> { NominatimPlaceSearchService(userAgent = androidContext().packageName) }
    }
```

Edit `app/src/main/java/com/speedevand/inkride/InkRideApp.kt`: add the import

```kotlin
import com.speedevand.inkride.dashboard.data.dashboardDataModule
```

and add `dashboardDataModule,` to the `modules(...)` list (any position — Koin module order doesn't matter here).

- [ ] **Step 9: Run ktlint and the full build to verify everything still compiles**

Run: `./gradlew ktlintCheck`
Expected: PASS (run `./gradlew ktlintFormat` first if it reports violations, then re-run check).

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — confirms the new module, its Koin registration, and the version-catalog/Ktor additions all resolve and compile together.

- [ ] **Step 10: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts app/build.gradle.kts app/src/main/java/com/speedevand/inkride/InkRideApp.kt feature/dashboard/data core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/PlaceSearchService.kt
git commit -m "Add Nominatim-backed place search service"
```

---

## Task 2: OSRM routing service

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RoutingService.kt`
- Create: `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/OsrmRoutingService.kt`
- Modify: `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/DashboardDataModule.kt`
- Test: `feature/dashboard/data/src/test/kotlin/com/speedevand/inkride/dashboard/data/OsrmRoutingServiceTest.kt`

**Interfaces:**
- Consumes: `PlannedRoute(name: String?, points: List<RoutePoint>, waypoints: List<RouteWaypoint>)`, `RoutePoint(latitude: Double, longitude: Double)`, `RouteWaypoint(latitude: Double, longitude: Double, name: String?)` — all already in `:core:domain` (`core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/PlannedRoute.kt`), unchanged by this plan.
- Produces: `interface RoutingService { suspend fun route(originLatitude: Double, originLongitude: Double, destinationLatitude: Double, destinationLongitude: Double): Result<PlannedRoute, RoutingError> }`, `enum class RoutingError : Error { NETWORK_FAILED, NO_ROUTE_FOUND }` in `:core:domain`.
- Produces: `class OsrmRoutingService(httpClient: HttpClient = ...) : RoutingService` in `:feature:dashboard:data`.

- [ ] **Step 1: Add the domain contract**

Create `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RoutingService.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.Error
import com.speedevand.inkride.core.domain.Result

/** Computes a cycling route between two points as a [PlannedRoute]. */
interface RoutingService {
    suspend fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<PlannedRoute, RoutingError>
}

enum class RoutingError : Error {
    /** The request failed: no connectivity, timeout, or a non-2xx response. */
    NETWORK_FAILED,

    /** No cycling route exists between the two points. */
    NO_ROUTE_FOUND,
}
```

- [ ] **Step 2: Write the failing test for `OsrmRoutingService`**

Create `feature/dashboard/data/src/test/kotlin/com/speedevand/inkride/dashboard/data/OsrmRoutingServiceTest.kt`:

```kotlin
package com.speedevand.inkride.dashboard.data

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RoutePoint
import com.speedevand.inkride.core.domain.tracking.RouteWaypoint
import com.speedevand.inkride.core.domain.tracking.RoutingError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class OsrmRoutingServiceTest {
    private fun clientReturning(
        status: HttpStatusCode,
        body: String,
    ): HttpClient =
        HttpClient(
            MockEngine { _ ->
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            // Mirrors OsrmRoutingService's own default client config, so this
            // test's fixtures behave the same as the real one under a real
            // (much larger) OSRM response.
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    // A straight-then-right-turn route: depart -> Corner (turn) -> arrive.
    // depart/arrive must NOT become waypoints; only the "turn" step should.
    private val successBody =
        """
        {
          "code": "Ok",
          "routes": [
            {
              "geometry": { "coordinates": [[21.0, 52.0], [21.01, 52.0], [21.01, 51.99]] },
              "legs": [
                {
                  "steps": [
                    { "maneuver": { "location": [21.0, 52.0], "type": "depart" }, "name": "" },
                    { "maneuver": { "location": [21.01, 52.0], "type": "turn" }, "name": "Corner" },
                    { "maneuver": { "location": [21.01, 51.99], "type": "arrive" }, "name": "" }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

    @Test
    fun `route maps geometry to points and non-depart-non-arrive steps to waypoints`() =
        runTest {
            val service = OsrmRoutingService(clientReturning(HttpStatusCode.OK, successBody))

            val result = service.route(52.0, 21.0, 51.99, 21.01)

            assertThat(result).isEqualTo(
                Result.Success(
                    PlannedRoute(
                        name = null,
                        points =
                            listOf(
                                RoutePoint(52.0, 21.0),
                                RoutePoint(52.0, 21.01),
                                RoutePoint(51.99, 21.01),
                            ),
                        waypoints = listOf(RouteWaypoint(52.0, 21.01, "Corner")),
                    ),
                ),
            )
        }

    @Test
    fun `route returns NO_ROUTE_FOUND when the routes list is empty`() =
        runTest {
            val service = OsrmRoutingService(clientReturning(HttpStatusCode.OK, """{"code":"NoRoute","routes":[]}"""))

            val result = service.route(52.0, 21.0, 51.99, 21.01)

            assertThat(result).isEqualTo(Result.Error(RoutingError.NO_ROUTE_FOUND))
        }

    @Test
    fun `route returns NETWORK_FAILED on a server error`() =
        runTest {
            val service = OsrmRoutingService(clientReturning(HttpStatusCode.InternalServerError, ""))

            val result = service.route(52.0, 21.0, 51.99, 21.01)

            assertThat(result).isEqualTo(Result.Error(RoutingError.NETWORK_FAILED))
        }
}
```

- [ ] **Step 3: Run it, verify it fails to compile**

Run: `./gradlew :feature:dashboard:data:test --tests "com.speedevand.inkride.dashboard.data.OsrmRoutingServiceTest"`
Expected: FAILED — "Unresolved reference: OsrmRoutingService".

- [ ] **Step 4: Implement `OsrmRoutingService`**

Create `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/OsrmRoutingService.kt`:

```kotlin
package com.speedevand.inkride.dashboard.data

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RoutePoint
import com.speedevand.inkride.core.domain.tracking.RouteWaypoint
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.domain.tracking.RoutingService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Computes a bike route between two points via the public OSRM demo server
 * (router.project-osrm.org), keyless, "reasonable, non-commercial use" only,
 * no uptime SLA. The natural upgrade path if reliability becomes a problem is
 * a self-hosted OSRM instance behind this same interface.
 */
class OsrmRoutingService(
    private val httpClient: HttpClient =
        HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        },
) : RoutingService {
    override suspend fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<PlannedRoute, RoutingError> {
        val coordinates =
            String.format(
                Locale.US,
                "%.6f,%.6f;%.6f,%.6f",
                originLongitude,
                originLatitude,
                destinationLongitude,
                destinationLatitude,
            )

        val httpResponse =
            try {
                httpClient.get("https://router.project-osrm.org/route/v1/bike/$coordinates") {
                    parameter("geometries", "geojson")
                    parameter("steps", "true")
                    parameter("overview", "full")
                }
            } catch (e: Exception) {
                return Result.Error(RoutingError.NETWORK_FAILED)
            }

        if (!httpResponse.status.isSuccess()) {
            return Result.Error(RoutingError.NETWORK_FAILED)
        }

        val parsed =
            try {
                httpResponse.body<OsrmRouteResponse>()
            } catch (e: Exception) {
                return Result.Error(RoutingError.NETWORK_FAILED)
            }

        val route = parsed.routes.firstOrNull()
        if (parsed.code != "Ok" || route == null) {
            return Result.Error(RoutingError.NO_ROUTE_FOUND)
        }

        val points =
            route.geometry.coordinates.map { coordinate ->
                RoutePoint(latitude = coordinate[1], longitude = coordinate[0])
            }
        val waypoints =
            route.legs
                .flatMap { it.steps }
                .filter { it.maneuver.type != "depart" && it.maneuver.type != "arrive" }
                .map { step ->
                    RouteWaypoint(
                        latitude = step.maneuver.location[1],
                        longitude = step.maneuver.location[0],
                        name = step.name.ifBlank { null },
                    )
                }

        return Result.Success(PlannedRoute(name = null, points = points, waypoints = waypoints))
    }
}

@Serializable
private data class OsrmRouteResponse(
    val code: String,
    val routes: List<OsrmRoute> = emptyList(),
)

@Serializable
private data class OsrmRoute(
    val geometry: OsrmGeometry,
    val legs: List<OsrmLeg>,
)

@Serializable
private data class OsrmGeometry(
    val coordinates: List<List<Double>>,
)

@Serializable
private data class OsrmLeg(
    val steps: List<OsrmStep>,
)

@Serializable
private data class OsrmStep(
    val maneuver: OsrmManeuver,
    val name: String,
)

@Serializable
private data class OsrmManeuver(
    val location: List<Double>,
    val type: String,
)
```

- [ ] **Step 5: Run the test, verify it passes**

Run: `./gradlew :feature:dashboard:data:test --tests "com.speedevand.inkride.dashboard.data.OsrmRoutingServiceTest"`
Expected: PASS, 3 tests.

- [ ] **Step 6: Register it in the Koin module**

Edit `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/DashboardDataModule.kt`, add the import `com.speedevand.inkride.core.domain.tracking.RoutingService` and a line inside `module { }`:

```kotlin
single<RoutingService> { OsrmRoutingService() }
```

- [ ] **Step 7: Run ktlint and the module's full test suite**

Run: `./gradlew ktlintCheck` (use `ktlintFormat` first if needed)
Run: `./gradlew :feature:dashboard:data:test`
Expected: both PASS.

- [ ] **Step 8: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/RoutingService.kt feature/dashboard/data
git commit -m "Add OSRM-backed bike routing service"
```

---

## Task 3: Android current-location provider

**Files:**
- Create: `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/CurrentLocationProvider.kt`
- Create: `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/AndroidCurrentLocationProvider.kt`
- Modify: `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/DashboardDataModule.kt`
- Test: `feature/dashboard/data/src/test/kotlin/com/speedevand/inkride/dashboard/data/AndroidCurrentLocationProviderTest.kt`

**Interfaces:**
- Produces: `interface CurrentLocationProvider { suspend fun getCurrentLocation(): Result<LocationFix, LocationError> }`, `data class LocationFix(val latitude: Double, val longitude: Double)`, `enum class LocationError : Error { PERMISSION_DENIED, TIMED_OUT, PROVIDER_UNAVAILABLE }` in `:core:domain`.
- Produces: `class AndroidCurrentLocationProvider(context: Context, timeoutMs: Long = 15_000L) : CurrentLocationProvider` in `:feature:dashboard:data`.

This is InkRide's first *one-shot* location lookup, decoupled from `RideTracker`'s ride-sample pipeline (which only runs once `status != IDLE` — see the spec's "Current-position gap" section). It reuses the exact `Handler(Looper.getMainLooper())` + `LocationListener` + `requestSingleUpdate`/`removeUpdates` pattern already proven in `feature/tracking/data/src/main/java/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSource.kt`, and the exact Robolectric `shadowOf(...).grantPermissions/simulateLocation/idle()` test pattern already proven in `feature/tracking/data/src/test/kotlin/com/speedevand/inkride/tracking/data/AndroidRideSensorDataSourceStartTest.kt`.

- [ ] **Step 1: Add the domain contract**

Create `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/CurrentLocationProvider.kt`:

```kotlin
package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.Error
import com.speedevand.inkride.core.domain.Result

/**
 * One-shot "where am I right now" lookup. Deliberately independent of
 * [RideTracker]'s sample pipeline, which only runs while a ride is active
 * (`status != IDLE`) — see the destination-search design doc's "Current-
 * position gap" section for why that pipeline can't be reused here.
 */
interface CurrentLocationProvider {
    suspend fun getCurrentLocation(): Result<LocationFix, LocationError>
}

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
)

enum class LocationError : Error {
    PERMISSION_DENIED,
    TIMED_OUT,
    PROVIDER_UNAVAILABLE,
}
```

- [ ] **Step 2: Write the failing tests for `AndroidCurrentLocationProvider`**

Create `feature/dashboard/data/src/test/kotlin/com/speedevand/inkride/dashboard/data/AndroidCurrentLocationProviderTest.kt`:

```kotlin
package com.speedevand.inkride.dashboard.data

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.LocationFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidCurrentLocationProviderTest {
    private val context = ApplicationProvider.getApplicationContext<ContextWrapper>()
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Test
    fun `returns PERMISSION_DENIED when location permission is not granted`() =
        runTest {
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            val provider = AndroidCurrentLocationProvider(context)

            val result = provider.getCurrentLocation()

            assertThat(result).isEqualTo(Result.Error(LocationError.PERMISSION_DENIED))
        }

    @Test
    fun `returns PROVIDER_UNAVAILABLE when the GPS provider is absent`() =
        runTest {
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            shadowOf(locationManager).removeProvider(LocationManager.GPS_PROVIDER)
            val provider = AndroidCurrentLocationProvider(context)

            val result = provider.getCurrentLocation()

            assertThat(result).isEqualTo(Result.Error(LocationError.PROVIDER_UNAVAILABLE))
        }

    @Test
    fun `returns the fix from a simulated GPS location`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val provider = AndroidCurrentLocationProvider(context)

        var result: Result<LocationFix, LocationError>? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        scope.launch { result = provider.getCurrentLocation() }

        val fix =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 52.0
                longitude = 21.0
            }
        shadowOf(locationManager).simulateLocation(fix)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(result).isEqualTo(Result.Success(LocationFix(52.0, 21.0)))
        scope.cancel()
    }

    @Test
    fun `returns TIMED_OUT when no fix arrives before the timeout`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val provider = AndroidCurrentLocationProvider(context, timeoutMs = 1_000L)

        var result: Result<LocationFix, LocationError>? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        scope.launch { result = provider.getCurrentLocation() }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000L))

        assertThat(result).isEqualTo(Result.Error(LocationError.TIMED_OUT))
        scope.cancel()
    }
}
```

- [ ] **Step 3: Run the tests, verify they fail to compile**

Run: `./gradlew :feature:dashboard:data:test --tests "com.speedevand.inkride.dashboard.data.AndroidCurrentLocationProviderTest"`
Expected: FAILED — "Unresolved reference: AndroidCurrentLocationProvider".

- [ ] **Step 4: Implement `AndroidCurrentLocationProvider`**

Create `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/AndroidCurrentLocationProvider.kt`:

```kotlin
package com.speedevand.inkride.dashboard.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.LocationFix
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidCurrentLocationProvider(
    private val context: Context,
    private val timeoutMs: Long = 15_000L,
) : CurrentLocationProvider {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): Result<LocationFix, LocationError> {
        if (!hasLocationPermission()) {
            return Result.Error(LocationError.PERMISSION_DENIED)
        }
        if (locationManager.getProvider(LocationManager.GPS_PROVIDER) == null) {
            return Result.Error(LocationError.PROVIDER_UNAVAILABLE)
        }

        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            lateinit var listener: LocationListener
            val timeoutRunnable =
                Runnable {
                    locationManager.removeUpdates(listener)
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(Result.Error(LocationError.TIMED_OUT)))
                    }
                }
            listener =
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        handler.removeCallbacks(timeoutRunnable)
                        locationManager.removeUpdates(this)
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.success(Result.Success(LocationFix(location.latitude, location.longitude))),
                            )
                        }
                    }
                }
            continuation.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                locationManager.removeUpdates(listener)
            }
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            handler.postDelayed(timeoutRunnable, timeoutMs)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
```

(`continuation.resumeWith(Result.success(...))` is `kotlin.Result.success` wrapping our own domain `com.speedevand.inkride.core.domain.Result` — used instead of the simpler `continuation.resume(...)` extension only because both a plain `resume` and our domain type are both named `Result`; `resumeWith` sidesteps the ambiguity without an import alias.)

- [ ] **Step 5: Run the tests, verify they pass**

Run: `./gradlew :feature:dashboard:data:test --tests "com.speedevand.inkride.dashboard.data.AndroidCurrentLocationProviderTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Register it in the Koin module**

Edit `feature/dashboard/data/src/main/java/com/speedevand/inkride/dashboard/data/DashboardDataModule.kt`, add the import `com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider` and a line inside `module { }`:

```kotlin
single<CurrentLocationProvider> { AndroidCurrentLocationProvider(androidContext()) }
```

- [ ] **Step 7: Run ktlint and the module's full test suite**

Run: `./gradlew ktlintCheck` (use `ktlintFormat` first if needed)
Run: `./gradlew :feature:dashboard:data:test`
Expected: both PASS.

- [ ] **Step 8: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/CurrentLocationProvider.kt feature/dashboard/data
git commit -m "Add one-shot Android current-location provider"
```

---

## Task 4: DestinationSearchViewModel

**Files:**
- Create: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchContract.kt`
- Create: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchErrorMappers.kt`
- Create: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchViewModel.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardPresentationModule.kt`
- Modify: `feature/dashboard/presentation/src/main/res/values/strings.xml`
- Modify: `feature/dashboard/presentation/src/main/res/values-pl/strings.xml`
- Test: `feature/dashboard/presentation/src/test/kotlin/com/speedevand/inkride/dashboard/presentation/DestinationSearchViewModelTest.kt`

**Interfaces:**
- Consumes: `PlaceSearchService`, `RoutingService`, `CurrentLocationProvider` (Task 1-3), `RideTracker.loadRoute(route: PlannedRoute)` (existing), `PlaceResult`, `LocationFix`.
- Produces:
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
  class DestinationSearchViewModel(
      placeSearchService: PlaceSearchService,
      routingService: RoutingService,
      currentLocationProvider: CurrentLocationProvider,
      rideTracker: RideTracker,
  ) : ViewModel() {
      val state: StateFlow<DestinationSearchState>
      val events: Flow<DestinationSearchEvent>
      fun onAction(action: DestinationSearchAction)
  }
  ```
  Task 5 (screen) consumes `state`, `events`, and `onAction` exactly as `DashboardViewModel` is already consumed by `DashboardRoot`.

- [ ] **Step 1: Add the new strings**

Edit `feature/dashboard/presentation/src/main/res/values/strings.xml`, add after `dashboard_route_off`:

```xml
    <string name="destination_search_error_network">Couldn\'t reach the search service. Check your connection and try again.</string>
    <string name="destination_search_error_no_results">No places found</string>
    <string name="destination_search_error_no_route">No cycling route found to that destination.</string>
    <string name="destination_search_error_location_permission">Location permission is needed to find your current position.</string>
    <string name="destination_search_error_location_timeout">Couldn\'t get your current location in time.</string>
    <string name="destination_search_error_location_unavailable">GPS is unavailable on this device.</string>
```

Edit `feature/dashboard/presentation/src/main/res/values-pl/strings.xml`, add after `dashboard_route_off`:

```xml
    <string name="destination_search_error_network">Nie udało się połączyć z wyszukiwarką. Sprawdź połączenie i spróbuj ponownie.</string>
    <string name="destination_search_error_no_results">Nie znaleziono miejsc</string>
    <string name="destination_search_error_no_route">Nie znaleziono trasy rowerowej do tego celu.</string>
    <string name="destination_search_error_location_permission">Potrzebne jest uprawnienie do lokalizacji, aby ustalić Twoją pozycję.</string>
    <string name="destination_search_error_location_timeout">Nie udało się ustalić pozycji GPS na czas.</string>
    <string name="destination_search_error_location_unavailable">GPS jest niedostępny na tym urządzeniu.</string>
```

- [ ] **Step 2: Add the MVI contract and error mappers**

Create `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchContract.kt`:

```kotlin
package com.speedevand.inkride.dashboard.presentation

import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.presentation.UiText

data class DestinationSearchState(
    val query: String = "",
    val results: List<PlaceResult> = emptyList(),
    val isSearching: Boolean = false,
    val isRouting: Boolean = false,
)

sealed interface DestinationSearchAction {
    data class OnQueryChanged(
        val query: String,
    ) : DestinationSearchAction

    data class OnResultSelected(
        val result: PlaceResult,
    ) : DestinationSearchAction
}

sealed interface DestinationSearchEvent {
    data class ShowError(
        val message: UiText,
    ) : DestinationSearchEvent

    data object NavigateBackToDashboard : DestinationSearchEvent
}
```

Create `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchErrorMappers.kt`:

```kotlin
package com.speedevand.inkride.dashboard.presentation

import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.presentation.UiText

fun PlaceSearchError.toUiText(): UiText =
    when (this) {
        PlaceSearchError.NETWORK_FAILED -> UiText.StringResource(R.string.destination_search_error_network)
        PlaceSearchError.NO_RESULTS -> UiText.StringResource(R.string.destination_search_error_no_results)
    }

fun LocationError.toUiText(): UiText =
    when (this) {
        LocationError.PERMISSION_DENIED -> UiText.StringResource(R.string.destination_search_error_location_permission)
        LocationError.TIMED_OUT -> UiText.StringResource(R.string.destination_search_error_location_timeout)
        LocationError.PROVIDER_UNAVAILABLE -> UiText.StringResource(R.string.destination_search_error_location_unavailable)
    }

fun RoutingError.toUiText(): UiText =
    when (this) {
        RoutingError.NETWORK_FAILED -> UiText.StringResource(R.string.destination_search_error_network)
        RoutingError.NO_ROUTE_FOUND -> UiText.StringResource(R.string.destination_search_error_no_route)
    }
```

- [ ] **Step 3: Write the failing tests for `DestinationSearchViewModel`**

Create `feature/dashboard/presentation/src/test/kotlin/com/speedevand/inkride/dashboard/presentation/DestinationSearchViewModelTest.kt`:

```kotlin
package com.speedevand.inkride.dashboard.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.LapRecord
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.LocationFix
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RideMetricsCalculator
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideSensorSample
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.domain.tracking.RoutingService
import com.speedevand.inkride.core.domain.tracking.SensorError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DestinationSearchViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val placeSearchService = FakePlaceSearchService()
    private val routingService = FakeRoutingService()
    private val currentLocationProvider = FakeCurrentLocationProvider()
    private val rideTracker = testRideTracker()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        DestinationSearchViewModel(placeSearchService, routingService, currentLocationProvider, rideTracker)

    @Test
    fun `a query shorter than 3 characters does not trigger a search`() =
        runTest(testDispatcher) {
            val vm = viewModel()

            vm.onAction(DestinationSearchAction.OnQueryChanged("Wa"))
            advanceTimeBy(1_000L)

            assertThat(placeSearchService.queriesReceived).hasSize(0)
        }

    @Test
    fun `a query at least 3 characters long triggers a search after the debounce window`() =
        runTest(testDispatcher) {
            placeSearchService.nextResult = Result.Success(listOf(PlaceResult("Warsaw, Poland", 52.2297, 21.0122)))
            val vm = viewModel()

            vm.onAction(DestinationSearchAction.OnQueryChanged("War"))
            advanceTimeBy(700L)

            assertThat(placeSearchService.queriesReceived).isEqualTo(listOf("War"))
            assertThat(vm.state.value.results).hasSize(1)
        }

    @Test
    fun `only the latest query survives rapid typing`() =
        runTest(testDispatcher) {
            placeSearchService.nextResult = Result.Success(emptyList())
            val vm = viewModel()

            vm.onAction(DestinationSearchAction.OnQueryChanged("War"))
            advanceTimeBy(100L)
            vm.onAction(DestinationSearchAction.OnQueryChanged("Wars"))
            advanceTimeBy(700L)

            assertThat(placeSearchService.queriesReceived).isEqualTo(listOf("Wars"))
        }

    @Test
    fun `a network failure while searching surfaces a ShowError event`() =
        runTest(testDispatcher) {
            placeSearchService.nextResult = Result.Error(PlaceSearchError.NETWORK_FAILED)
            val vm = viewModel()

            vm.events.test {
                vm.onAction(DestinationSearchAction.OnQueryChanged("War"))
                advanceTimeBy(700L)
                assertThat(awaitItem()).isEqualTo(DestinationSearchEvent.ShowError(PlaceSearchError.NETWORK_FAILED.toUiText()))
            }
        }

    @Test
    fun `selecting a result routes from the current location and loads it into RideTracker`() =
        runTest(testDispatcher) {
            currentLocationProvider.nextResult = Result.Success(LocationFix(52.0, 21.0))
            routingService.nextResult = Result.Success(PlannedRoute(name = null, points = emptyList(), waypoints = emptyList()))
            val vm = viewModel()
            val selected = PlaceResult("Warsaw, Poland", 52.2297, 21.0122)

            vm.events.test {
                vm.onAction(DestinationSearchAction.OnResultSelected(selected))
                assertThat(awaitItem()).isEqualTo(DestinationSearchEvent.NavigateBackToDashboard)
            }
            assertThat(rideTracker.state.value.activeRoute?.name).isEqualTo("Warsaw, Poland")
            assertThat(routingService.lastRequest).isEqualTo(RouteRequest(52.0, 21.0, 52.2297, 21.0122))
        }

    @Test
    fun `a location failure while routing surfaces a ShowError event and never calls the routing service`() =
        runTest(testDispatcher) {
            currentLocationProvider.nextResult = Result.Error(LocationError.TIMED_OUT)
            val vm = viewModel()

            vm.events.test {
                vm.onAction(DestinationSearchAction.OnResultSelected(PlaceResult("Warsaw, Poland", 52.2297, 21.0122)))
                assertThat(awaitItem()).isEqualTo(DestinationSearchEvent.ShowError(LocationError.TIMED_OUT.toUiText()))
            }
            assertThat(routingService.lastRequest).isNull()
        }
}

private class FakePlaceSearchService : PlaceSearchService {
    val queriesReceived = mutableListOf<String>()
    var nextResult: Result<List<PlaceResult>, PlaceSearchError> = Result.Success(emptyList())

    override suspend fun search(query: String): Result<List<PlaceResult>, PlaceSearchError> {
        queriesReceived.add(query)
        return nextResult
    }
}

private data class RouteRequest(
    val originLatitude: Double,
    val originLongitude: Double,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
)

private class FakeRoutingService : RoutingService {
    var nextResult: Result<PlannedRoute, RoutingError> = Result.Error(RoutingError.NO_ROUTE_FOUND)
    var lastRequest: RouteRequest? = null

    override suspend fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<PlannedRoute, RoutingError> {
        lastRequest = RouteRequest(originLatitude, originLongitude, destinationLatitude, destinationLongitude)
        return nextResult
    }
}

private class FakeCurrentLocationProvider : CurrentLocationProvider {
    var nextResult: Result<LocationFix, LocationError> = Result.Error(LocationError.PROVIDER_UNAVAILABLE)

    override suspend fun getCurrentLocation(): Result<LocationFix, LocationError> = nextResult
}

private fun testRideTracker(): RideTracker =
    RideTracker(
        sensorDataSource =
            object : RideSensorDataSource {
                override fun observeSamples(): Flow<RideSensorSample> = emptyFlow()

                override fun start(): EmptyResult<SensorError> = Result.Success(Unit)

                override fun stop() = Unit
            },
        metricsCalculator = RideMetricsCalculator(),
        historyRepository =
            object : RideHistoryRepository {
                override fun observeAll(): Flow<List<RideRecord>> = emptyFlow()

                override suspend fun getById(id: Long) = Result.Error(DataError.Local.NOT_FOUND)

                override suspend fun save(ride: RideRecord) = Result.Success(1L)

                override suspend fun deleteById(id: Long): EmptyResult<DataError.Local> = Result.Success(Unit)

                override suspend fun deleteAll(): EmptyResult<DataError.Local> = Result.Success(Unit)
            },
        trackPointRepository =
            object : RideTrackPointRepository {
                override suspend fun savePoints(
                    rideId: Long,
                    points: List<RideTrackPoint>,
                ): EmptyResult<DataError.Local> = Result.Success(Unit)

                override suspend fun getPoints(rideId: Long) = Result.Success(emptyList<RideTrackPoint>())
            },
        lapRepository =
            object : RideLapRepository {
                override suspend fun saveLaps(
                    rideId: Long,
                    laps: List<LapRecord>,
                ): EmptyResult<DataError.Local> = Result.Success(Unit)

                override suspend fun getLaps(rideId: Long) = Result.Success(emptyList<LapRecord>())
            },
        bleSensorDataSource =
            object : BleSensorDataSource {
                override fun observeSamples(): Flow<BleSample> = emptyFlow()

                override fun connect(
                    hrmAddress: String?,
                    cadenceAddress: String?,
                ) = Unit

                override fun disconnect() = Unit
            },
        userSettingsRepository =
            object : UserSettingsRepository {
                private val settings = MutableStateFlow(UserSettings(weightKg = 75, age = 30))

                override fun observeSettings(): Flow<UserSettings> = settings

                override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> {
                    this.settings.value = settings
                    return Result.Success(Unit)
                }
            },
    )
```

- [ ] **Step 4: Run the tests, verify they fail to compile**

Run: `./gradlew :feature:dashboard:presentation:testDebugUnitTest --tests "com.speedevand.inkride.dashboard.presentation.DestinationSearchViewModelTest"`
Expected: FAILED — "Unresolved reference: DestinationSearchViewModel" (and related contract types).

- [ ] **Step 5: Implement `DestinationSearchViewModel`**

Create `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchViewModel.kt`:

```kotlin
package com.speedevand.inkride.dashboard.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.core.domain.tracking.RoutingService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DestinationSearchViewModel(
    private val placeSearchService: PlaceSearchService,
    private val routingService: RoutingService,
    private val currentLocationProvider: CurrentLocationProvider,
    private val rideTracker: RideTracker,
) : ViewModel() {
    private val _state = MutableStateFlow(DestinationSearchState())
    val state = _state.asStateFlow()

    private val _events = Channel<DestinationSearchEvent>()
    val events = _events.receiveAsFlow()

    private val queryFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(SEARCH_DEBOUNCE_MS)
                .filter { it.length >= MIN_QUERY_LENGTH }
                .distinctUntilChanged()
                .collectLatest { query -> performSearch(query) }
        }
    }

    fun onAction(action: DestinationSearchAction) {
        when (action) {
            is DestinationSearchAction.OnQueryChanged -> onQueryChanged(action.query)
            is DestinationSearchAction.OnResultSelected -> onResultSelected(action.result)
        }
    }

    private fun onQueryChanged(query: String) {
        _state.update { current ->
            current.copy(query = query, results = if (query.length < MIN_QUERY_LENGTH) emptyList() else current.results)
        }
        queryFlow.tryEmit(query)
    }

    private suspend fun performSearch(query: String) {
        _state.update { it.copy(isSearching = true) }
        when (val result = placeSearchService.search(query)) {
            is Result.Success -> _state.update { it.copy(isSearching = false, results = result.data) }
            is Result.Error -> {
                _state.update { it.copy(isSearching = false, results = emptyList()) }
                if (result.error == PlaceSearchError.NETWORK_FAILED) {
                    _events.send(DestinationSearchEvent.ShowError(result.error.toUiText()))
                }
            }
        }
    }

    private fun onResultSelected(result: PlaceResult) {
        viewModelScope.launch {
            _state.update { it.copy(isRouting = true) }

            val location =
                when (val locationResult = currentLocationProvider.getCurrentLocation()) {
                    is Result.Success -> locationResult.data
                    is Result.Error -> {
                        _state.update { it.copy(isRouting = false) }
                        _events.send(DestinationSearchEvent.ShowError(locationResult.error.toUiText()))
                        return@launch
                    }
                }

            val routeResult =
                routingService.route(
                    originLatitude = location.latitude,
                    originLongitude = location.longitude,
                    destinationLatitude = result.latitude,
                    destinationLongitude = result.longitude,
                )

            _state.update { it.copy(isRouting = false) }

            when (routeResult) {
                is Result.Success -> {
                    rideTracker.loadRoute(routeResult.data.copy(name = result.displayName))
                    _events.send(DestinationSearchEvent.NavigateBackToDashboard)
                }

                is Result.Error -> _events.send(DestinationSearchEvent.ShowError(routeResult.error.toUiText()))
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 600L
        const val MIN_QUERY_LENGTH = 3
    }
}
```

- [ ] **Step 6: Run the tests, verify they pass**

Run: `./gradlew :feature:dashboard:presentation:testDebugUnitTest --tests "com.speedevand.inkride.dashboard.presentation.DestinationSearchViewModelTest"`
Expected: PASS, 6 tests.

- [ ] **Step 7: Register the ViewModel in Koin**

Edit `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardPresentationModule.kt`:

```kotlin
package com.speedevand.inkride.dashboard.presentation

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val dashboardPresentationModule =
    module {
        single<GpxRouteLoader> { AndroidGpxRouteLoader(androidContext()) }
        viewModelOf(::DashboardViewModel)
        viewModelOf(::DestinationSearchViewModel)
    }
```

- [ ] **Step 8: Run ktlint and the module's full test suite**

Run: `./gradlew ktlintCheck` (use `ktlintFormat` first if needed)
Run: `./gradlew :feature:dashboard:presentation:testDebugUnitTest`
Expected: both PASS.

- [ ] **Step 9: Commit**

```bash
git add feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchContract.kt feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchErrorMappers.kt feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchViewModel.kt feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardPresentationModule.kt feature/dashboard/presentation/src/main/res/values/strings.xml feature/dashboard/presentation/src/main/res/values-pl/strings.xml feature/dashboard/presentation/src/test/kotlin/com/speedevand/inkride/dashboard/presentation/DestinationSearchViewModelTest.kt
git commit -m "Add DestinationSearchViewModel: debounced search, routing, RideTracker handoff"
```

---

## Task 5: DestinationSearchScreen

**Files:**
- Create: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchScreen.kt`
- Modify: `feature/dashboard/presentation/src/main/res/values/strings.xml`
- Modify: `feature/dashboard/presentation/src/main/res/values-pl/strings.xml`

**Interfaces:**
- Consumes: `DestinationSearchState`, `DestinationSearchAction`, `DestinationSearchEvent`, `DestinationSearchViewModel` (Task 4), `PlaceResult`.
- Produces: `@Composable fun DestinationSearchRoot(onNavigateBack: () -> Unit)` — Task 6 wires this into the nav graph.

No isolated Compose test for this task: this codebase has no precedent for per-component instrumented tests in `feature/dashboard/presentation` (it currently has zero `androidTest` sources of its own) — every Compose screen here is exercised through the heavyweight end-to-end harness in `:app` instead (see `RideTrackingE2ETestBase` and its subclasses). Task 8 is that coverage for this screen.

This screen uses `TextFieldMMD` + a plain scrolling `Column` (with the project's documented custom `verticalScrollbar` exception — MMD has no list/search-with-suggestions component that fits a dedicated full-screen search-then-list flow without pulling in `SearchBarMMD`'s much heavier expand/collapse-overlay API, which is designed for a different UX pattern). Loading states render as static text, not a spinner, per the E-Ink "no fluid animation" rule.

- [ ] **Step 1: Add the screen's strings**

Edit `feature/dashboard/presentation/src/main/res/values/strings.xml`, add after `dashboard_route_off`:

```xml
    <string name="destination_search_title">Search destination</string>
    <string name="destination_search_back">Back</string>
    <string name="destination_search_hint">Search for a place</string>
    <string name="destination_search_searching">Searching…</string>
    <string name="destination_search_routing">Finding a route…</string>
```

Edit `feature/dashboard/presentation/src/main/res/values-pl/strings.xml`, add after `dashboard_route_off`:

```xml
    <string name="destination_search_title">Wyszukaj cel</string>
    <string name="destination_search_back">Wstecz</string>
    <string name="destination_search_hint">Szukaj miejsca</string>
    <string name="destination_search_searching">Szukam…</string>
    <string name="destination_search_routing">Wyznaczam trasę…</string>
```

- [ ] **Step 2: Implement the screen**

Create `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchScreen.kt`:

```kotlin
package com.speedevand.inkride.dashboard.presentation

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.core.presentation.ObserveAsEvents
import com.speedevand.inkride.core.presentation.verticalScrollbar
import org.koin.androidx.compose.koinViewModel

@Composable
fun DestinationSearchRoot(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: DestinationSearchViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is DestinationSearchEvent.ShowError ->
                Toast.makeText(context, event.message.asString(context), Toast.LENGTH_LONG).show()

            DestinationSearchEvent.NavigateBackToDashboard -> onNavigateBack()
        }
    }

    DestinationSearchScreen(
        state = state,
        onAction = viewModel::onAction,
        onNavigateBack = onNavigateBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationSearchScreen(
    state: DestinationSearchState,
    onAction: (DestinationSearchAction) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.destination_search_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.destination_search_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(DesignConstants.PADDING_MEDIUM),
            verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_MEDIUM),
        ) {
            TextFieldMMD(
                value = state.query,
                onValueChange = { onAction(DestinationSearchAction.OnQueryChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { TextMMD(text = stringResource(R.string.destination_search_hint)) },
            )

            when {
                state.isSearching ->
                    TextMMD(
                        text = stringResource(R.string.destination_search_searching),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                state.isRouting ->
                    TextMMD(
                        text = stringResource(R.string.destination_search_routing),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                state.query.length >= 3 && state.results.isEmpty() ->
                    TextMMD(
                        text = stringResource(R.string.destination_search_error_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                    )
            }

            val scrollState = rememberScrollState()
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .verticalScrollbar(scrollState),
                verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_SMALL),
            ) {
                state.results.forEach { result ->
                    DestinationResultRow(
                        result = result,
                        onClick = { onAction(DestinationSearchAction.OnResultSelected(result)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationResultRow(
    result: PlaceResult,
    onClick: () -> Unit,
) {
    TextMMD(
        text = result.displayName,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = DesignConstants.PADDING_SMALL),
    )
}
```

- [ ] **Step 3: Run ktlint**

Run: `./gradlew ktlintCheck` (use `ktlintFormat` first if needed)
Expected: PASS.

- [ ] **Step 4: Compile the module**

Run: `./gradlew :feature:dashboard:presentation:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`DestinationSearchRoot` isn't called from anywhere yet — that's Task 6 — so this only proves the screen itself is well-formed; the module compiling with no other regressions is this task's verification, matching the "no isolated test" note above.)

- [ ] **Step 5: Commit**

```bash
git add feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchScreen.kt feature/dashboard/presentation/src/main/res/values/strings.xml feature/dashboard/presentation/src/main/res/values-pl/strings.xml
git commit -m "Add DestinationSearchScreen"
```

---

## Task 6: Navigation wiring

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/navigation/NavigationRoutes.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardNavigation.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardContract.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardScreen.kt`

**Interfaces:**
- Consumes: `DestinationSearchRoot(onNavigateBack: () -> Unit)` (Task 5).
- Produces: `data object DestinationSearchRoute` (navigation route); `DashboardRoot(onOpenSettings: () -> Unit, onSearchDestination: () -> Unit, onStartService: () -> Unit, onStopService: () -> Unit)` (new `onSearchDestination` param); `DashboardAction.OnSearchDestinationClick`. Task 7 fires this action from the new bottom sheet.

- [ ] **Step 1: Add the route**

Edit `core/domain/src/main/java/com/speedevand/inkride/core/domain/navigation/NavigationRoutes.kt`, add after `data object BikeProfilesRoute`:

```kotlin
@Serializable
data object DestinationSearchRoute
```

- [ ] **Step 2: Add the action to `DashboardContract`**

Edit `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardContract.kt`, add inside `sealed interface DashboardAction`, after `data object OnOpenSettingsClick`:

```kotlin
    data object OnSearchDestinationClick : DashboardAction
```

- [ ] **Step 3: Thread `onSearchDestination` through `DashboardRoot`**

Edit `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardScreen.kt`. Change the `DashboardRoot` signature and its interception `when`:

```kotlin
@Composable
fun DashboardRoot(
    onOpenSettings: () -> Unit,
    onSearchDestination: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
```

Replace the existing `onAction` block inside `DashboardRoot`'s call to `DashboardScreen`:

```kotlin
    DashboardScreen(
        state = state,
        onAction = { action ->
            when (action) {
                DashboardAction.OnOpenSettingsClick -> onOpenSettings()
                DashboardAction.OnSearchDestinationClick -> onSearchDestination()
                else -> viewModel.onAction(action)
            }
        },
    )
```

- [ ] **Step 4: Wire the route into `dashboardGraph`**

Edit `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardNavigation.kt`:

```kotlin
package com.speedevand.inkride.dashboard.presentation

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.speedevand.inkride.core.domain.navigation.DashboardGraph
import com.speedevand.inkride.core.domain.navigation.DashboardRoute
import com.speedevand.inkride.core.domain.navigation.DestinationSearchRoute
import com.speedevand.inkride.core.domain.navigation.SettingsRoute

fun NavGraphBuilder.dashboardGraph(
    navController: NavController,
    trackingServiceClass: Class<*>,
) {
    navigation<DashboardGraph>(startDestination = DashboardRoute) {
        composable<DashboardRoute> {
            val context = LocalContext.current
            DashboardRoot(
                onOpenSettings = {
                    navController.navigate(SettingsRoute)
                },
                onSearchDestination = {
                    navController.navigate(DestinationSearchRoute)
                },
                onStartService = {
                    val intent =
                        Intent(context, trackingServiceClass).apply {
                            action = "ACTION_START"
                        }
                    context.startService(intent)
                },
                onStopService = {
                    val intent =
                        Intent(context, trackingServiceClass).apply {
                            action = "ACTION_STOP"
                        }
                    context.startService(intent)
                },
            )
        }
        composable<DestinationSearchRoute> {
            DestinationSearchRoot(onNavigateBack = { navController.popBackStack() })
        }
    }
}
```

- [ ] **Step 5: Compile**

Run: `./gradlew :core:domain:compileKotlin :feature:dashboard:presentation:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`DashboardTopBar`'s `onLoadRoute` still directly opens the file picker at this point — `DashboardAction.OnSearchDestinationClick` is defined and routed, but nothing fires it yet. That's Task 7.)

- [ ] **Step 6: Run ktlint**

Run: `./gradlew ktlintCheck` (use `ktlintFormat` first if needed)
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/navigation/NavigationRoutes.kt feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardNavigation.kt feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardContract.kt feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardScreen.kt
git commit -m "Wire DestinationSearchRoute into the dashboard nav graph"
```

---

## Task 7: Route-source bottom sheet

**Files:**
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/RouteBar.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardScreen.kt`
- Modify: `feature/dashboard/presentation/src/main/res/values/strings.xml`
- Modify: `feature/dashboard/presentation/src/main/res/values-pl/strings.xml`

**Interfaces:**
- Consumes: `DashboardAction.OnSearchDestinationClick` (Task 6).
- Produces: `@Composable fun RouteSourceSheet(onDismiss: () -> Unit, onLoadGpxFile: () -> Unit, onSearchDestination: () -> Unit)`.

This is the last piece connecting the existing "Load route" topbar icon to the new search flow — no change to `DashboardTopBar.kt` itself, since `onLoadRoute` already exists as a generic "the load-route icon was tapped" callback; only what `DashboardScreen` does in response changes (open a choice sheet instead of launching the file picker directly).

- [ ] **Step 1: Add the sheet's strings**

Edit `feature/dashboard/presentation/src/main/res/values/strings.xml`, add after `dashboard_route_off`:

```xml
    <string name="dashboard_route_source_title">Add a route</string>
    <string name="dashboard_route_load_gpx">Load GPX file</string>
    <string name="dashboard_route_search_destination">Search destination</string>
```

Edit `feature/dashboard/presentation/src/main/res/values-pl/strings.xml`, add after `dashboard_route_off`:

```xml
    <string name="dashboard_route_source_title">Dodaj trasę</string>
    <string name="dashboard_route_load_gpx">Wczytaj plik GPX</string>
    <string name="dashboard_route_search_destination">Wyszukaj cel</string>
```

- [ ] **Step 2: Add `RouteSourceSheet`**

Edit `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/RouteBar.kt`, add after `ClearRouteConfirmationSheet`:

```kotlin
/**
 * Choice sheet shown when the topbar's route icon is tapped with no route
 * active: load a GPX file from disk, or search for a destination and route
 * to it. Both close the sheet immediately on selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSourceSheet(
    onDismiss: () -> Unit,
    onLoadGpxFile: () -> Unit,
    onSearchDestination: () -> Unit,
) {
    ModalBottomSheetMMD(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(DesignConstants.PADDING_LARGE),
            verticalArrangement = Arrangement.spacedBy(DesignConstants.PADDING_MEDIUM),
        ) {
            TextMMD(
                text = stringResource(R.string.dashboard_route_source_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            ButtonMMD(onClick = onLoadGpxFile, modifier = Modifier.fillMaxWidth()) {
                TextMMD(text = stringResource(R.string.dashboard_route_load_gpx))
            }
            OutlinedButtonMMD(onClick = onSearchDestination, modifier = Modifier.fillMaxWidth()) {
                TextMMD(text = stringResource(R.string.dashboard_route_search_destination))
            }
        }
    }
}
```

- [ ] **Step 3: Wire the sheet into `DashboardScreen`**

Edit `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardScreen.kt`. Add the import `com.speedevand.inkride.dashboard.presentation.components.RouteSourceSheet`. Add state and the sheet, right after the existing `showClearRouteConfirm` block:

```kotlin
    var showRouteSourceSheet by remember { mutableStateOf(false) }
    if (showRouteSourceSheet) {
        RouteSourceSheet(
            onDismiss = { showRouteSourceSheet = false },
            onLoadGpxFile = {
                showRouteSourceSheet = false
                routePicker.launch(arrayOf("*/*"))
            },
            onSearchDestination = {
                showRouteSourceSheet = false
                onAction(DashboardAction.OnSearchDestinationClick)
            },
        )
    }
```

(This must come after `routePicker` is declared, since `onLoadGpxFile` references it — place it directly above the `Scaffold(...)` call, below the existing `val routePicker = rememberLauncherForActivityResult(...)` line.)

Change `DashboardTopBar`'s `onLoadRoute` argument from launching the picker directly to opening the sheet:

```kotlin
                onLoadRoute = { showRouteSourceSheet = true },
```

- [ ] **Step 4: Run ktlint**

Run: `./gradlew ktlintCheck` (use `ktlintFormat` first if needed)
Expected: PASS.

- [ ] **Step 5: Manually verify on a device/emulator**

Run: `./gradlew :app:installDebug`, launch InkRide, tap the route icon in the topbar. Expected: a bottom sheet appears with "Load GPX file" and "Search destination" — tapping "Search destination" navigates to the new search screen with a back arrow; tapping back returns to the dashboard; tapping "Load GPX file" opens the system file picker exactly as before.

- [ ] **Step 6: Commit**

```bash
git add feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/components/RouteBar.kt feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DashboardScreen.kt feature/dashboard/presentation/src/main/res/values/strings.xml feature/dashboard/presentation/src/main/res/values-pl/strings.xml
git commit -m "Add route-source choice sheet: load GPX file or search a destination"
```

---

## Task 8: End-to-end instrumented test

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingRouteSearchTest.kt`

**Interfaces:**
- Consumes: `RideTrackingE2ETestBase` (existing, `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingE2ETestBase.kt`), `PlaceSearchService`/`RoutingService`/`CurrentLocationProvider` (Tasks 1-3), `DashboardTestTags.ROUTE_NEXT_TURN_ICON` and `dashboardString`/`waitUntilTagContentDescription` (existing, from the turn-arrow work in `app/src/androidTest/java/com/speedevand/inkride/tracking/support/ComposeTestHelpers.kt`).

Drives the real search screen end-to-end with fake network/location services swapped in via Koin (same override mechanism `RideTrackingE2ETestBase` already uses for `RideSensorDataSource`/`BleSensorDataSource`), then asserts the dashboard shows the turn arrow — proving the new ingestion path (search → route → `RideTracker.loadRoute()`) feeds the exact same, already-tested pipeline `RideTrackingRouteFollowingTest` (from the turn-arrow work) already proved works for a `PlannedRoute`.

- [ ] **Step 1: Write the test**

Create `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingRouteSearchTest.kt`:

```kotlin
package com.speedevand.inkride.tracking

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import assertk.assertThat
import assertk.assertions.contains
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.LocationFix
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RoutePoint
import com.speedevand.inkride.core.domain.tracking.RouteWaypoint
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.domain.tracking.RoutingService
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.tracking.support.dashboardString
import com.speedevand.inkride.tracking.support.textOf
import com.speedevand.inkride.tracking.support.waitUntilTagContentDescription
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module

/**
 * Drives the real search screen (type a query, pick a result) with fake
 * network/location services swapped in, then asserts the dashboard shows the
 * same geometry-derived turn arrow [RideTrackingRouteFollowingTest] already
 * proved works for a GPX-loaded [PlannedRoute] — confirming this new
 * ingestion path feeds the same, already-tested pipeline.
 */
class RideTrackingRouteSearchTest : RideTrackingE2ETestBase() {
    private val placeSearchService = FakeSearchPlaceSearchService()
    private val routingService = FakeSearchRoutingService()
    private val currentLocationProvider = FakeSearchCurrentLocationProvider()

    private val searchTestModule =
        module {
            single<PlaceSearchService> { placeSearchService }
            single<RoutingService> { routingService }
            single<CurrentLocationProvider> { currentLocationProvider }
        }

    @Before
    fun setUpSearchFakes() {
        loadKoinModules(listOf(searchTestModule))
    }

    @After
    fun tearDownSearchFakes() {
        unloadKoinModules(listOf(searchTestModule))
    }

    @Test
    fun searchingAndSelectingADestinationLoadsARouteWithTheTurnArrow() {
        currentLocationProvider.nextResult = Result.Success(LocationFix(52.0, 21.0))
        placeSearchService.nextResult = Result.Success(listOf(PlaceResult("Test Corner Place", 52.0, 21.01)))
        // Same east-then-south right-turn geometry as RideTrackingRouteFollowingTest.
        routingService.nextResult =
            Result.Success(
                PlannedRoute(
                    name = null,
                    points =
                        listOf(
                            RoutePoint(52.0, 21.0),
                            RoutePoint(52.0, 21.01),
                            RoutePoint(51.99, 21.01),
                        ),
                    waypoints = listOf(RouteWaypoint(52.0, 21.01, "Corner")),
                ),
            )

        composeTestRule.onNodeWithContentDescription(dashboardString(R.string.dashboard_route_load)).performClick()
        composeTestRule.onNodeWithText(dashboardString(R.string.dashboard_route_search_destination)).performClick()
        composeTestRule.onNodeWithText(dashboardString(R.string.destination_search_hint)).performTextInput("Corner")

        // Real 600ms debounce in DestinationSearchViewModel, plus the fakes'
        // effectively-instant resolution -- give it real wall-clock time,
        // then let Compose settle before looking for the result row.
        Thread.sleep(800L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Test Corner Place").performClick()

        composeTestRule.waitUntilTagContentDescription(DashboardTestTags.ROUTE_NEXT_TURN_ICON) {
            it == dashboardString(R.string.dashboard_route_turn_right)
        }
        assertThat(composeTestRule.textOf(DashboardTestTags.ROUTE_NEXT_TURN_TEXT)).contains("Corner")
    }
}

private class FakeSearchPlaceSearchService : PlaceSearchService {
    var nextResult: Result<List<PlaceResult>, PlaceSearchError> = Result.Success(emptyList())

    override suspend fun search(query: String): Result<List<PlaceResult>, PlaceSearchError> = nextResult
}

private class FakeSearchRoutingService : RoutingService {
    var nextResult: Result<PlannedRoute, RoutingError> = Result.Error(RoutingError.NO_ROUTE_FOUND)

    override suspend fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<PlannedRoute, RoutingError> = nextResult
}

private class FakeSearchCurrentLocationProvider : CurrentLocationProvider {
    var nextResult: Result<LocationFix, LocationError> = Result.Error(LocationError.PROVIDER_UNAVAILABLE)

    override suspend fun getCurrentLocation(): Result<LocationFix, LocationError> = nextResult
}
```

- [ ] **Step 2: Run it on a connected device/emulator, verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.speedevand.inkride.tracking.RideTrackingRouteSearchTest`
Expected: `Tests 1/1 completed. (0 skipped) (0 failed)`.

- [ ] **Step 3: Verify the test actually catches a regression**

Temporarily change `routingService.nextResult`'s waypoint from `RouteWaypoint(52.0, 21.01, "Corner")` to a route with no waypoints at all (`waypoints = emptyList()`), re-run the same command, confirm it now fails (times out waiting for `ROUTE_NEXT_TURN_ICON`), then revert the change.

- [ ] **Step 4: Run ktlint and the full unit test suite one more time**

Run: `./gradlew ktlintCheck`
Run: `./gradlew testDebugUnitTest :core:domain:test`
Expected: both PASS — confirms nothing in this plan regressed existing coverage.

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingRouteSearchTest.kt
git commit -m "Add end-to-end test for destination search and routing"
```
