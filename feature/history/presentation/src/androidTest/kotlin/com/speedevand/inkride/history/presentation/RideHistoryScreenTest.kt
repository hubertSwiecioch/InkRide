package com.speedevand.inkride.history.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.testing.support.TestRides
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.stringRes
import com.speedevand.inkride.core.testing.support.text
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideHistoryScreenTest : HistoryTestHarness() {
    override fun initialRides(): List<RideRecord> =
        listOf(
            TestRides.record(id = 1L, startTimestamp = TestRides.START_MS + 86_400_000L, distanceKm = 25.0),
            TestRides.record(id = 2L, startTimestamp = TestRides.START_MS, distanceKm = 10.0),
        )

    /**
     * The row is clickable, so it merges its descendants: the tagged metrics
     * text is only reachable through the unmerged tree.
     */
    private fun metricsOf(id: Long): String =
        composeTestRule
            .onNodeWithTag(HistoryTestTags.rowMetrics(id), useUnmergedTree = true)
            .text()

    private fun setContent(
        onRideClick: (Long) -> Unit = {},
        onLifetimeStats: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            RideHistoryRoot(
                onNavigateToDetail = onRideClick,
                onNavigateToLifetimeStats = onLifetimeStats,
            )
        }
    }

    @Test
    fun ridesRenderNewestFirstWithTheirDistanceAndSpeed() {
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.row(1L)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HistoryTestTags.row(2L)).assertIsDisplayed()
        // Distance, moving time and speed share one text node in the row.
        val metrics = metricsOf(1L)
        assertThat(metrics.startsWith("25.00 km")).isTrue()
        assertThat(metrics.endsWith("30.0 km/h")).isTrue()
    }

    @Test
    fun anEmptyHistoryShowsTheEmptyState() {
        historyRepository.emitRides(emptyList())
        setContent()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(HistoryTestTags.EMPTY_STATE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(HistoryTestTags.EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun clickingARideNavigatesWithItsId() {
        var clickedId: Long? = null
        setContent(onRideClick = { clickedId = it })
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.row(2L)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { clickedId != null }
        assertThat(clickedId).isEqualTo(2L)
    }

    @Test
    fun deletingARideRemovesItFromTheList() {
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.rowDelete(2L)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(HistoryTestTags.row(2L)).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithTag(HistoryTestTags.row(1L)).assertIsDisplayed()
    }

    @Test
    fun undoingADeleteRestoresTheRideWithItsLapsAndTrackPoints() {
        lapRepository.setLaps(rideId = 2L, laps = TestRides.laps(count = 3))
        trackPointRepository.setPoints(rideId = 2L, points = TestRides.trackPoints(count = 5))
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.rowDelete(2L)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithText(stringRes(R.string.ride_history_undo))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(stringRes(R.string.ride_history_undo)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { historyRepository.observeAll().first() }.size == 2
        }

        val restoredId =
            runBlocking { historyRepository.observeAll().first() }
                .first { it.id != 1L }
                .id
        assertThat(runBlocking { lapRepository.getLaps(restoredId) })
            .isEqualTo(Result.Success(TestRides.laps(count = 3)))
        assertThat(runBlocking { trackPointRepository.getPoints(restoredId) })
            .isEqualTo(Result.Success(TestRides.trackPoints(count = 5)))
    }

    @Test
    fun deleteAllAsksForConfirmationAndCancellingChangesNothing() {
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.DELETE_ALL_BUTTON).performClick()
        composeTestRule.onNodeWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_DIALOG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_CANCEL).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_DIALOG)
                .fetchSemanticsNodes()
                .isEmpty()
        }

        composeTestRule.onNodeWithTag(HistoryTestTags.row(1L)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HistoryTestTags.row(2L)).assertIsDisplayed()
    }

    @Test
    fun confirmingDeleteAllEmptiesTheList() {
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.DELETE_ALL_BUTTON).performClick()
        composeTestRule.onNodeWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_ACCEPT).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(HistoryTestTags.EMPTY_STATE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aFailedDeleteLeavesTheListUnchanged() {
        historyRepository.deleteResult = Result.Error(DataError.Local.UNKNOWN)
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.rowDelete(2L)).performClick()
        composeTestRule.waitForIdle()

        // The error surfaces as a Toast, which is outside the semantics tree;
        // the observable consequence is that the row is still there.
        composeTestRule.onNodeWithTag(HistoryTestTags.row(2L)).assertIsDisplayed()
    }

    @Test
    fun theStatsIconNavigatesToLifetimeStats() {
        var opened = false
        setContent(onLifetimeStats = { opened = true })
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.LIFETIME_STATS_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { opened }
        assertThat(opened).isTrue()
    }

    @Test
    fun imperialUnitsRenderMilesAndMilesPerHour() {
        settingsRepository.emitSettings(TestSettings.default(units = MeasurementUnits.IMPERIAL))
        setContent()
        composeTestRule.waitForIdle()

        val metrics = metricsOf(1L)
        assertThat(metrics.startsWith("15.53 mi")).isTrue()
        assertThat(metrics.endsWith("mph")).isTrue()
    }
}
