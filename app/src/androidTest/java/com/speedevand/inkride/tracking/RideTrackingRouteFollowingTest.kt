package com.speedevand.inkride.tracking

import assertk.assertThat
import assertk.assertions.contains
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RideSensorSample
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.core.domain.tracking.RoutePoint
import com.speedevand.inkride.core.domain.tracking.RouteWaypoint
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.dashboard.presentation.R
import com.speedevand.inkride.tracking.support.dashboardString
import com.speedevand.inkride.tracking.support.textOf
import com.speedevand.inkride.tracking.support.waitUntilTagContentDescription
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.math.cos

/**
 * Drives a loaded route's turn-by-turn readout through the real Compose UI:
 * loads a [PlannedRoute] with a right-angle corner directly into [RideTracker]
 * (the same call [com.speedevand.inkride.dashboard.presentation.DashboardViewModel]
 * makes once [com.speedevand.inkride.dashboard.presentation.GpxRouteLoader] has
 * parsed a GPX file — bypassing the system file picker, which isn't practical
 * to drive from an instrumented test), then feeds GPS fixes heading toward the
 * corner and asserts the dashboard renders the geometry-derived turn arrow.
 *
 * The corner geometry mirrors `RouteFollowerTest`'s "east to south" right-turn
 * fixture: a rider heading east who then turns south at the marked waypoint.
 */
class RideTrackingRouteFollowingTest : RideTrackingE2ETestBase() {
    private val originLatitude = 52.0
    private val originLongitude = 21.0

    // ~685 m due east of the origin, matching RouteFollowerTest's fixture.
    private val cornerLatitude = 52.0
    private val cornerLongitude = 21.01

    private val route =
        PlannedRoute(
            name = "Right Turn Test",
            points =
                listOf(
                    RoutePoint(originLatitude, originLongitude),
                    RoutePoint(cornerLatitude, cornerLongitude),
                    // South of the corner, completing the right-angle turn.
                    RoutePoint(51.99, cornerLongitude),
                ),
            waypoints = listOf(RouteWaypoint(cornerLatitude, cornerLongitude, "Corner")),
        )

    @Test
    fun showsGeometryDerivedRightTurnArrowWhileApproachingACorner() {
        startRideAndSettle()
        GlobalContext.get().get<RideTracker>().loadRoute(route)

        feedEastboundSteps(count = 5, speedKmh = 20.0)

        composeTestRule.waitUntilTagContentDescription(DashboardTestTags.ROUTE_NEXT_TURN_ICON) {
            it == dashboardString(R.string.dashboard_route_turn_right)
        }

        assertThat(composeTestRule.textOf(DashboardTestTags.ROUTE_NEXT_TURN_TEXT)).contains("Corner")
    }

    // A synthetic eastbound path from the origin (bearing 90°), mirroring
    // RideSamples.movingSample's northbound one but along this test's route —
    // kept local since no other test needs an eastbound fixture yet.
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
