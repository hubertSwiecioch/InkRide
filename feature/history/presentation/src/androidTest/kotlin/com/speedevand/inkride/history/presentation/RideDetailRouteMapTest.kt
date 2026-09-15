package com.speedevand.inkride.history.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
 * osmdroid fetches map tiles over the network, which the CI emulator has no
 * guaranteed access to, so these assertions are structural only: the sheet
 * opened and a MapView is in the tree. Never assert that a tile rendered.
 */
@RunWith(AndroidJUnit4::class)
class RideDetailRouteMapTest : HistoryTestHarness() {
    private companion object {
        const val RIDE_ID = 1L
    }

    override fun initialRides(): List<RideRecord> = listOf(TestRides.record(id = RIDE_ID))

    private fun setContent() {
        composeTestRule.setContent {
            RideDetailRoot(rideId = RIDE_ID, onNavigateBack = {})
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun theShowRouteButtonIsAbsentWhenThereIsNoTrack() {
        setContent()

        composeTestRule.onAllNodesWithTag(RideDetailTestTags.SHOW_ROUTE_BUTTON).assertCountEquals(0)
    }

    @Test
    fun showingTheRouteOpensASheetContainingTheMap() {
        trackPointRepository.setPoints(RIDE_ID, TestRides.trackPoints(count = 20))
        setContent()

        composeTestRule.onNodeWithTag(RideDetailTestTags.SHOW_ROUTE_BUTTON).performScrollTo().performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule.onAllNodesWithTag(RideDetailTestTags.ROUTE_MAP).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(RideDetailTestTags.ROUTE_SHEET).assertIsDisplayed()
        composeTestRule.onNodeWithTag(RideDetailTestTags.ROUTE_MAP).assertIsDisplayed()
    }
}
