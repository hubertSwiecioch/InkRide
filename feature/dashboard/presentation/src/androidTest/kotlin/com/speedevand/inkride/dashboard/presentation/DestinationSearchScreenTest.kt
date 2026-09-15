package com.speedevand.inkride.dashboard.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService
import com.speedevand.inkride.core.domain.tracking.RideMetricsCalculator
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.domain.tracking.RoutingService
import com.speedevand.inkride.core.testing.fakes.FakeBleSensorDataSource
import com.speedevand.inkride.core.testing.fakes.FakeCurrentLocationProvider
import com.speedevand.inkride.core.testing.fakes.FakePlaceSearchService
import com.speedevand.inkride.core.testing.fakes.FakeRideHistoryRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideLapRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideSensorDataSource
import com.speedevand.inkride.core.testing.fakes.FakeRideTrackPointRepository
import com.speedevand.inkride.core.testing.fakes.FakeRoutingService
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import com.speedevand.inkride.core.testing.rules.KoinTestRule
import com.speedevand.inkride.core.testing.support.TestSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module

@RunWith(AndroidJUnit4::class)
class DestinationSearchScreenTest {
    private val placeSearch = FakePlaceSearchService()
    private val routing = FakeRoutingService()
    private val location = FakeCurrentLocationProvider()

    /**
     * `DestinationSearchViewModel` takes a [RideTracker] to load the resolved
     * route into. None of these tests reaches that branch, but Koin still has
     * to build one, so it gets a tracker wired entirely to fakes.
     */
    private val rideTracker =
        RideTracker(
            sensorDataSource = FakeRideSensorDataSource(),
            metricsCalculator = RideMetricsCalculator(),
            historyRepository = FakeRideHistoryRepository(),
            trackPointRepository = FakeRideTrackPointRepository(),
            lapRepository = FakeRideLapRepository(),
            bleSensorDataSource = FakeBleSensorDataSource(),
            userSettingsRepository = FakeUserSettingsRepository(TestSettings.default()),
        )

    @get:Rule(order = 0)
    val koinRule =
        KoinTestRule(
            modules =
                listOf(
                    dashboardPresentationModule,
                    module {
                        single<PlaceSearchService> { placeSearch }
                        single<RoutingService> { routing }
                        single<CurrentLocationProvider> { location }
                        single<RideTracker> { rideTracker }
                        single<RideSensorDataSource> { FakeRideSensorDataSource() }
                        single<BleSensorDataSource> { FakeBleSensorDataSource() }
                    },
                ),
        )

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(onBack: () -> Unit = {}) {
        composeTestRule.setContent { DestinationSearchRoot(onNavigateBack = onBack) }
    }

    private fun type(query: String) {
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.QUERY_FIELD).performTextInput(query)
    }

    /** Waits out the ViewModel's real debounce; there is no test clock behind it. */
    private fun awaitDebounce() {
        composeTestRule.waitForIdle()
        Thread.sleep(DestinationSearchViewModel.SEARCH_DEBOUNCE_MS + 500L)
    }

    private fun awaitResults() {
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag(DestinationSearchTestTags.resultRow(0))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun aQueryBelowTheMinimumLengthNeverReachesTheService() {
        setContent()

        type("wa")
        awaitDebounce()

        assertThat(placeSearch.queries).isEmpty()
    }

    @Test
    fun matchingPlacesRenderAsSelectableRows() {
        setContent()

        type("warsaw")
        awaitResults()

        composeTestRule.onNodeWithTag(DestinationSearchTestTags.resultRow(0)).assertIsDisplayed()
    }

    @Test
    fun noMatchesShowsTheEmptyState() {
        placeSearch.result = Result.Error(PlaceSearchError.NO_RESULTS)
        setContent()

        type("nowhereville")

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag(DestinationSearchTestTags.EMPTY_STATE)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun aFailedSearchLeavesTheResultListEmpty() {
        placeSearch.result = Result.Error(PlaceSearchError.NETWORK_FAILED)
        setContent()

        type("warsaw")
        awaitDebounce()

        composeTestRule.onAllNodesWithTag(DestinationSearchTestTags.resultRow(0)).assertCountEquals(0)
    }

    @Test
    fun selectingAResultWithNoCyclingRouteDoesNotNavigateAway() {
        routing.result = Result.Error(RoutingError.NO_ROUTE_FOUND)
        var navigatedBack = false
        setContent(onBack = { navigatedBack = true })

        type("warsaw")
        awaitResults()
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.resultRow(0)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { routing.callCount == 1 }

        assertThat(routing.callCount).isEqualTo(1)
        assertThat(navigatedBack).isFalse()
    }

    @Test
    fun aFailedRoutingRequestDoesNotNavigateAway() {
        routing.result = Result.Error(RoutingError.NETWORK_FAILED)
        var navigatedBack = false
        setContent(onBack = { navigatedBack = true })

        type("warsaw")
        awaitResults()
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.resultRow(0)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { routing.callCount == 1 }

        assertThat(navigatedBack).isFalse()
    }

    @Test
    fun aDeniedLocationPermissionStopsTheRouteBeforeRoutingIsCalled() {
        location.result = Result.Error(LocationError.PERMISSION_DENIED)
        setContent()

        type("warsaw")
        awaitResults()
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.resultRow(0)).performClick()
        composeTestRule.waitForIdle()

        assertThat(routing.callCount).isEqualTo(0)
    }

    @Test
    fun aTimedOutLocationLookupStopsTheRouteBeforeRoutingIsCalled() {
        location.result = Result.Error(LocationError.TIMED_OUT)
        setContent()

        type("warsaw")
        awaitResults()
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.resultRow(0)).performClick()
        composeTestRule.waitForIdle()

        assertThat(routing.callCount).isEqualTo(0)
    }

    @Test
    fun backInvokesItsNavigationCallback() {
        var backPressed = false
        setContent(onBack = { backPressed = true })

        composeTestRule.onNodeWithTag(DestinationSearchTestTags.BACK_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { backPressed }
        assertThat(backPressed).isTrue()
    }
}
