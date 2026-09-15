package com.speedevand.inkride.tracking.support

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags

fun ComposeTestRule.swipeMetricsPagerToNextPage() {
    onNodeWithTag(DashboardTestTags.METRICS_PAGER).performTouchInput { swipeUp() }
    waitForIdle()
}

fun ComposeTestRule.swipeMetricsPagerToPreviousPage() {
    onNodeWithTag(DashboardTestTags.METRICS_PAGER).performTouchInput { swipeDown() }
    waitForIdle()
}
