package com.speedevand.inkride.history.presentation

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.history.LifetimeStats
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.textOf
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifetimeStatsScreenTest : HistoryTestHarness() {
    private val populated =
        LifetimeStats(
            totalRides = 12,
            totalDistanceKm = 340.5,
            totalMovingTimeSeconds = 45_000L,
            totalElevationGainM = 4_200.0,
            maxSpeedKmh = 61.3,
            totalCaloriesKcal = 9_800.0,
        )

    private fun setContent(onBack: () -> Unit = {}) {
        composeTestRule.setContent { LifetimeStatsRoot(onNavigateBack = onBack) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun totalsRenderFromTheRepository() {
        lifetimeStatsRepository.emit(populated)
        setContent()

        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_RIDES)).isEqualTo("12")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_DISTANCE)).isEqualTo("340.5 km")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_MOVING_TIME)).isEqualTo("12h 30m")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_ELEVATION)).isEqualTo("4200 m")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.MAX_SPEED)).isEqualTo("61.3 km/h")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_CALORIES)).isEqualTo("9800 kcal")
    }

    @Test
    fun anEmptyHistoryRendersZeroes() {
        setContent()

        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_RIDES)).isEqualTo("0")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_DISTANCE)).isEqualTo("0.0 km")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.MAX_SPEED)).isEqualTo("0.0 km/h")
    }

    @Test
    fun imperialUnitsRenderMilesFeetAndMilesPerHour() {
        settingsRepository.emitSettings(TestSettings.default(units = MeasurementUnits.IMPERIAL))
        lifetimeStatsRepository.emit(populated)
        setContent()

        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_DISTANCE).endsWith("mi")).isTrue()
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_ELEVATION).endsWith("ft")).isTrue()
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.MAX_SPEED).endsWith("mph")).isTrue()
    }

    @Test
    fun backInvokesItsNavigationCallback() {
        var backPressed = false
        setContent(onBack = { backPressed = true })

        composeTestRule.onNodeWithTag(LifetimeStatsTestTags.BACK_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { backPressed }
        assertThat(backPressed).isTrue()
    }
}
