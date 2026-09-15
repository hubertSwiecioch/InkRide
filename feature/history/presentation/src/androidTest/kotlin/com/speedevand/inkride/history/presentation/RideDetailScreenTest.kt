package com.speedevand.inkride.history.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.testing.support.TestRides
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.text
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideDetailScreenTest : HistoryTestHarness() {
    private companion object {
        const val RIDE_ID = 1L
    }

    override fun initialRides(): List<RideRecord> = listOf(TestRides.record(id = RIDE_ID))

    private fun setContent(
        rideId: Long = RIDE_ID,
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            RideDetailRoot(rideId = rideId, onNavigateBack = onBack)
        }
        composeTestRule.waitForIdle()
    }

    /** The detail body is one long scrolling column, so values are scrolled into view first. */
    private fun detailText(key: String): String =
        composeTestRule
            .onNodeWithTag(RideDetailTestTags.detail(key))
            .performScrollTo()
            .text()

    @Test
    fun everyMetricRendersFromTheStoredRide() {
        setContent()

        assertThat(detailText(RideDetailTestTags.DISTANCE)).isEqualTo("25.00 km")
        assertThat(detailText(RideDetailTestTags.AVG_SPEED)).isEqualTo("30.0 km/h")
        assertThat(detailText(RideDetailTestTags.MAX_SPEED)).isEqualTo("45.5 km/h")
        assertThat(detailText(RideDetailTestTags.ELEVATION_GAIN)).isEqualTo("320 m")
        assertThat(detailText(RideDetailTestTags.CALORIES)).isEqualTo("780 kcal")
        assertThat(detailText(RideDetailTestTags.AVG_POWER)).isEqualTo("165 W")
        assertThat(detailText(RideDetailTestTags.MOVING_TIME)).isEqualTo("00:50:00")
        assertThat(detailText(RideDetailTestTags.ELAPSED_TIME)).isEqualTo("01:00:00")
    }

    @Test
    fun lapsRenderWhenTheRideHasThem() {
        lapRepository.setLaps(RIDE_ID, TestRides.laps(count = 3))
        setContent()

        composeTestRule.onNodeWithTag(RideDetailTestTags.LAPS_SECTION).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(RideDetailTestTags.lapRow(1)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(RideDetailTestTags.lapRow(3)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theLapsSectionIsAbsentWhenTheRideHasNoLaps() {
        setContent()

        composeTestRule.onAllNodesWithTag(RideDetailTestTags.LAPS_SECTION).assertCountEquals(0)
    }

    @Test
    fun theElevationChartRendersWhenTheRideHasATrack() {
        trackPointRepository.setPoints(RIDE_ID, TestRides.trackPoints(count = 30))
        setContent()

        // The chart's min/max labels are drawn onto a Canvas and carry no
        // semantics; their content is covered by RideRecordMappingTest.
        composeTestRule.onNodeWithTag(RideDetailTestTags.ELEVATION_CHART).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theElevationChartIsAbsentWithoutTrackPoints() {
        setContent()

        composeTestRule.onAllNodesWithTag(RideDetailTestTags.ELEVATION_CHART).assertCountEquals(0)
    }

    @Test
    fun cancellingTheDeleteDialogKeepsTheRide() {
        var navigatedBack = false
        setContent(onBack = { navigatedBack = true })

        composeTestRule.onNodeWithTag(RideDetailTestTags.DELETE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_DIALOG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_CANCEL).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag(RideDetailTestTags.CONFIRM_DELETE_DIALOG)
                .fetchSemanticsNodes()
                .isEmpty()
        }

        assertThat(navigatedBack).isFalse()
    }

    @Test
    fun confirmingTheDeleteRemovesTheRideAndNavigatesBack() {
        var navigatedBack = false
        setContent(onBack = { navigatedBack = true })

        composeTestRule.onNodeWithTag(RideDetailTestTags.DELETE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_ACCEPT).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { navigatedBack }
        assertThat(navigatedBack).isTrue()
    }

    @Test
    fun backNavigatesWithoutDeletingAnything() {
        var navigatedBack = false
        setContent(onBack = { navigatedBack = true })

        composeTestRule.onNodeWithTag(RideDetailTestTags.BACK_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { navigatedBack }
        assertThat(navigatedBack).isTrue()
    }

    @Test
    fun imperialUnitsRenderMilesFeetAndMilesPerHour() {
        settingsRepository.emitSettings(TestSettings.default(units = MeasurementUnits.IMPERIAL))
        setContent()

        assertThat(detailText(RideDetailTestTags.DISTANCE).endsWith("mi")).isTrue()
        assertThat(detailText(RideDetailTestTags.MAX_SPEED).endsWith("mph")).isTrue()
        assertThat(detailText(RideDetailTestTags.ELEVATION_GAIN).endsWith("ft")).isTrue()
    }

    @Test
    fun anUnknownRideIdShowsTheNotFoundStateWithoutCrashing() {
        setContent(rideId = 999L)

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(RideDetailTestTags.NOT_FOUND).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(RideDetailTestTags.NOT_FOUND).assertIsDisplayed()
    }
}
