package com.speedevand.inkride.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.speedevand.inkride.R
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.testing.support.stringRes
import com.speedevand.inkride.core.testing.support.text
import com.speedevand.inkride.core.testing.support.textOf
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.history.presentation.HistoryTestTags
import com.speedevand.inkride.history.presentation.RideDetailTestTags
import com.speedevand.inkride.tracking.RideTrackingE2ETestBase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * The only test that crosses the real tracking → Room → history boundary: a
 * ride recorded on the dashboard, read back through the history repository, and
 * deleted again. Everything in between is real — real Room, real repositories,
 * real nav graph — with only the GPS/BLE sources faked.
 */
class RideToHistoryE2ETest : RideTrackingE2ETestBase() {
    @Test
    fun aRecordedRideAppearsInHistoryWithMatchingDetailAndCanBeDeleted() {
        startRideAndSettle()
        feedMovingSteps(count = 12, speedKmh = 20.0)

        // The dashboard renders the number without its unit, in the same
        // two-decimal format the history row uses.
        val dashboardDistance = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE)

        composeTestRule.onNodeWithTag(DashboardTestTags.STOP_RESET_BUTTON).performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithContentDescription(stringRes(R.string.nav_history), useUnmergedTree = true)
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(HistoryTestTags.LIST)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        val rideId = firstRideId()
        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(HistoryTestTags.row(rideId))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag(HistoryTestTags.row(rideId)).assertIsDisplayed()
        // The row is clickable and merges its children, so read the metrics
        // line from the unmerged tree.
        val listMetrics =
            composeTestRule
                .onNodeWithTag(HistoryTestTags.rowMetrics(rideId), useUnmergedTree = true)
                .text()
        assertThat(listMetrics.startsWith("$dashboardDistance km")).isTrue()

        composeTestRule.onNodeWithTag(HistoryTestTags.row(rideId)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(RideDetailTestTags.detail(RideDetailTestTags.DISTANCE))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // The detail screen reads the same persisted row the list rendered.
        assertThat(
            composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.DISTANCE)),
        ).isEqualTo("$dashboardDistance km")

        composeTestRule.onNodeWithTag(RideDetailTestTags.DELETE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_ACCEPT).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(HistoryTestTags.row(rideId))
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * Room is real and shared across test classes in this module, so the id of
     * the ride just recorded is not predictable — read it from the repository
     * instead of assuming 1.
     */
    private fun firstRideId(): Long =
        runBlocking {
            GlobalContext
                .get()
                .get<RideHistoryRepository>()
                .observeAll()
                .first()
                .first()
                .id
        }

    /** Runs before the base class's teardown, so the rows go before the tracker stops. */
    @After
    fun clearHistory() {
        runBlocking { GlobalContext.get().get<RideHistoryRepository>().deleteAll() }
    }
}
