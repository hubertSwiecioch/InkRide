package com.speedevand.inkride.tracking

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilExactlyOneExists
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
import com.speedevand.inkride.core.domain.tracking.RideSensorSample
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
import kotlin.math.cos

/**
 * Drives the real search screen (type a query, pick a result) with fake
 * network/location services swapped in, then asserts the dashboard shows the
 * same geometry-derived turn arrow [RideTrackingRouteFollowingTest] already
 * proved works for a GPX-loaded [PlannedRoute] — confirming this new
 * ingestion path feeds the same, already-tested pipeline.
 *
 * `RideTracker.loadRoute()` always resets `routeProgress` to null, and
 * progress is only recomputed from GPS fixes processed while a ride is
 * actively TRACKING (see `RideTracker.launchCollection`'s
 * `statusBefore == TrackingStatus.IDLE` guard) — so, exactly like
 * [RideTrackingRouteFollowingTest], this test starts the ride and feeds
 * eastbound fixes toward the corner after the route loads; without that, the
 * turn arrow never renders and `ROUTE_NEXT_TURN_ICON` never appears.
 */
@OptIn(ExperimentalTestApi::class)
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
        // effectively-instant resolution -- poll for the actual result to
        // appear rather than sleeping out a fixed margin over the debounce,
        // which is flaky on shared/slow CI hardware.
        composeTestRule.waitUntilExactlyOneExists(hasText("Test Corner Place"), timeoutMillis = 5_000L)
        composeTestRule.onNodeWithText("Test Corner Place").performClick()
        composeTestRule.waitForIdle()

        // Route is now loaded but routeProgress stays null until GPS fixes are
        // processed while TRACKING (see class doc) — start the ride (it
        // preserves the just-loaded activeRoute, same as RideTracker.start()
        // preserving a pre-set route/goal) and feed the same eastbound
        // approach to the corner as RideTrackingRouteFollowingTest.
        startRideAndSettle()
        feedEastboundSteps(count = 5, speedKmh = 20.0)

        composeTestRule.waitUntilTagContentDescription(DashboardTestTags.ROUTE_NEXT_TURN_ICON) {
            it == dashboardString(R.string.dashboard_route_turn_right)
        }
        assertThat(composeTestRule.textOf(DashboardTestTags.ROUTE_NEXT_TURN_TEXT)).contains("Corner")
    }

    // Same synthetic eastbound path (bearing 90°) as
    // RideTrackingRouteFollowingTest.feedEastboundSteps, kept local for the
    // same reason that one is: no shared fixture exists yet for an arbitrary
    // route origin/bearing.
    private val originLatitude = 52.0
    private val originLongitude = 21.0
    private val metersPerDegreeLatitude = 111_320.0
    private val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(Math.toRadians(originLatitude))

    private fun feedEastboundSteps(
        count: Int,
        speedKmh: Double,
    ) {
        val speedMps = speedKmh / 3.6
        repeat(count) { index ->
            val stepIndex = index + 1
            val deltaLonDeg = (speedMps * stepIndex) / metersPerDegreeLongitude
            fakeSensorSource.emit(
                RideSensorSample(
                    timestampMs = System.currentTimeMillis(),
                    latitude = originLatitude,
                    longitude = originLongitude + deltaLonDeg,
                    altitudeFromGpsM = 100.0,
                    altitudeFromBarometerM = 100.0,
                    speedFromGpsMps = speedMps,
                    accuracyM = 5f,
                    bearingDegrees = 90f,
                    satelliteCount = 8,
                ),
            )
            Thread.sleep(1_000L)
        }
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
