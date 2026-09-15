package com.speedevand.inkride.history.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.testing.support.TestRides
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `StateRestorationTester` recreates the composition the way a configuration
 * change does, which is what `rememberSaveable` state has to survive. These are
 * the regression guard for the dialog/sheet flags being saveable.
 */
@RunWith(AndroidJUnit4::class)
class HistoryRotationTest : HistoryTestHarness() {
    private companion object {
        const val RIDE_ID = 1L
    }

    override fun initialRides(): List<RideRecord> = listOf(TestRides.record(id = RIDE_ID))

    @Test
    fun theDeleteAllDialogSurvivesARecomposition() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            RideHistoryRoot(
                onNavigateToDetail = {},
                onNavigateToLifetimeStats = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HistoryTestTags.DELETE_ALL_BUTTON).performClick()
        composeTestRule.onNodeWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_DIALOG).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_DIALOG).assertIsDisplayed()
    }

    @Test
    fun theRideDeleteDialogSurvivesARecomposition() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            RideDetailRoot(rideId = RIDE_ID, onNavigateBack = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RideDetailTestTags.DELETE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_DIALOG).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_DIALOG).assertIsDisplayed()
    }

    @Test
    fun theRouteSheetSurvivesARecomposition() {
        trackPointRepository.setPoints(RIDE_ID, TestRides.trackPoints(count = 20))
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            RideDetailRoot(rideId = RIDE_ID, onNavigateBack = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RideDetailTestTags.SHOW_ROUTE_BUTTON).performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(RideDetailTestTags.ROUTE_SHEET)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithTag(RideDetailTestTags.ROUTE_SHEET).assertIsDisplayed()
    }
}
