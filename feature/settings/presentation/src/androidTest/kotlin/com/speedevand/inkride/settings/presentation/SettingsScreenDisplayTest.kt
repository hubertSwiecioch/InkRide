package com.speedevand.inkride.settings.presentation

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.testing.support.TestSettings
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenDisplayTest : SettingsTestHarness() {
    private data class MetricSwitch(
        val key: String,
        val read: (UserSettings) -> Boolean,
    )

    private val metricSwitches =
        listOf(
            MetricSwitch("show_distance") { it.showDistance },
            MetricSwitch("show_moving_time") { it.showMovingTime },
            MetricSwitch("show_average_speed") { it.showAverageSpeed },
            MetricSwitch("show_max_speed") { it.showMaxSpeed },
            MetricSwitch("show_elevation_gain") { it.showElevationGain },
            MetricSwitch("show_calories") { it.showCalories },
            MetricSwitch("show_altitude") { it.showAltitude },
            MetricSwitch("show_grade") { it.showGrade },
            MetricSwitch("show_power") { it.showPower },
            MetricSwitch("show_compass") { it.showCompass },
        )

    @Test
    fun everyMetricVisibilitySwitchPersistsWhenTurnedOff() {
        setSettingsContent()
        selectTab(SettingsTab.DISPLAY)

        metricSwitches.forEach { metric ->
            clickTag(SettingsTestTags.switch(metric.key))
            composeTestRule.waitUntil(timeoutMillis = 5_000L) {
                settingsRepository.lastSaved?.let { !metric.read(it) } == true
            }
            assertThat(metric.read(settingsRepository.lastSaved!!)).isFalse()
        }
    }

    @Test
    fun switchingToImperialUnitsPersists() {
        setSettingsContent()
        selectTab(SettingsTab.DISPLAY)

        clickTag(SettingsTestTags.radio(MeasurementUnits.IMPERIAL.name))

        awaitSaved { it.units == MeasurementUnits.IMPERIAL }
        assertThat(settingsRepository.lastSaved?.units).isEqualTo(MeasurementUnits.IMPERIAL)
    }

    @Test
    fun switchingBackToMetricUnitsPersists() {
        settingsRepository.emitSettings(TestSettings.default(units = MeasurementUnits.IMPERIAL))
        setSettingsContent()
        selectTab(SettingsTab.DISPLAY)

        clickTag(SettingsTestTags.radio(MeasurementUnits.METRIC.name))

        awaitSaved { it.units == MeasurementUnits.METRIC }
        assertThat(settingsRepository.lastSaved?.units).isEqualTo(MeasurementUnits.METRIC)
    }

    @Test
    fun keepScreenOnPersists() {
        setSettingsContent()
        selectTab(SettingsTab.DISPLAY)

        clickTag(SettingsTestTags.switch(SettingsTestTags.KEEP_SCREEN_ON))

        awaitSaved { !it.keepScreenOn }
        assertThat(settingsRepository.lastSaved?.keepScreenOn).isEqualTo(false)
    }
}
