package com.speedevand.inkride.settings.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.speedevand.inkride.core.testing.support.stringRes
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenAlertsTest : SettingsTestHarness() {
    private fun openAlerts() {
        setSettingsContent()
        selectTab(SettingsTab.DISPLAY)
        composeTestRule
            .onNodeWithText(stringRes(R.string.settings_section_alerts))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun theSpeedAlertStepperIsHiddenUntilTheAlertIsEnabled() {
        openAlerts()

        composeTestRule
            .onAllNodesWithTag(SettingsTestTags.stepperValue(SettingsTestTags.ALERT_MAX_SPEED))
            .assertCountEquals(0)
    }

    @Test
    fun enablingTheSpeedAlertPersistsAThresholdAndRevealsItsStepper() {
        openAlerts()

        clickTag(SettingsTestTags.switch(SettingsTestTags.ALERT_MAX_SPEED))

        awaitSaved { it.alerts.maxSpeedKmh != null }
        assertThat(settingsRepository.lastSaved?.alerts?.maxSpeedKmh).isNotNull()
        composeTestRule
            .onNodeWithTag(SettingsTestTags.stepperValue(SettingsTestTags.ALERT_MAX_SPEED))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun steppingTheSpeedAlertThresholdPersists() {
        openAlerts()
        clickTag(SettingsTestTags.switch(SettingsTestTags.ALERT_MAX_SPEED))
        awaitSaved { it.alerts.maxSpeedKmh != null }
        val before = settingsRepository.lastSaved!!.alerts.maxSpeedKmh!!

        clickTag(SettingsTestTags.stepperPlus(SettingsTestTags.ALERT_MAX_SPEED))

        awaitSaved { (it.alerts.maxSpeedKmh ?: before) > before }
    }

    @Test
    fun disablingTheSpeedAlertClearsItsThreshold() {
        openAlerts()
        clickTag(SettingsTestTags.switch(SettingsTestTags.ALERT_MAX_SPEED))
        awaitSaved { it.alerts.maxSpeedKmh != null }

        clickTag(SettingsTestTags.switch(SettingsTestTags.ALERT_MAX_SPEED))

        awaitSaved { it.alerts.maxSpeedKmh == null }
        assertThat(settingsRepository.lastSaved?.alerts?.maxSpeedKmh).isNull()
    }

    @Test
    fun theMinimumHeartRateAlertPersistsIndependentlyOfTheMaximum() {
        openAlerts()

        clickTag(SettingsTestTags.switch(SettingsTestTags.ALERT_HR_MIN))

        awaitSaved { it.alerts.hrZoneMinBpm != null }
        assertThat(settingsRepository.lastSaved?.alerts?.hrZoneMinBpm).isNotNull()
        assertThat(settingsRepository.lastSaved?.alerts?.hrZoneMaxBpm).isNull()
    }

    @Test
    fun theMaximumHeartRateAlertPersistsIndependentlyOfTheMinimum() {
        openAlerts()

        clickTag(SettingsTestTags.switch(SettingsTestTags.ALERT_HR_MAX))

        awaitSaved { it.alerts.hrZoneMaxBpm != null }
        assertThat(settingsRepository.lastSaved?.alerts?.hrZoneMaxBpm).isNotNull()
        assertThat(settingsRepository.lastSaved?.alerts?.hrZoneMinBpm).isNull()
    }
}
