package com.speedevand.inkride.lifecycle

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.testing.support.textOf
import com.speedevand.inkride.core.testing.support.waitUntilTagText
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.RideTrackingE2ETestBase
import org.junit.Test

/**
 * `RideTracker` is a process-scoped singleton and the ride lives there, not in
 * the activity. Recreating the activity must therefore leave the ride running
 * and re-attach the UI to it.
 */
class ProcessDeathE2ETest : RideTrackingE2ETestBase() {
    @Test
    fun anActiveRideSurvivesActivityRecreationAndKeepsAccumulating() {
        startRideAndSettle()
        feedMovingSteps(count = 8, speedKmh = 20.0)
        val distanceBefore = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE)

        recreateActivity()

        // The rebuilt UI shows the ride that is still running, not a reset one.
        composeTestRule.onNodeWithTag(DashboardTestTags.METRIC_DISTANCE).assertIsDisplayed()
        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE)).isEqualTo(distanceBefore)

        feedMovingSteps(count = 6, speedKmh = 20.0)

        composeTestRule.waitUntilTagText(DashboardTestTags.METRIC_DISTANCE) { it != distanceBefore }
    }
}
