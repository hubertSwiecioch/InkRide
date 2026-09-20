package com.speedevand.inkride.settings.presentation

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.speedevand.inkride.core.testing.support.TestSettings
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTrainingTest : SettingsTestHarness() {
    @Test
    fun enablingFtpPersistsAStartingValueToEdit() {
        setSettingsContent()

        clickTag(SettingsTestTags.switch(SettingsTestTags.FTP))

        awaitSaved { it.ftpWatts != null }
        assertThat(settingsRepository.lastSaved?.ftpWatts).isEqualTo(SettingsConstants.FTP_DEFAULT_WATTS)
    }

    @Test
    fun steppingFtpUpPersistsTheNewValue() {
        settingsRepository.emitSettings(TestSettings.default().copy(ftpWatts = 200))
        setSettingsContent()
        composeTestRule.waitForIdle()

        clickTag(SettingsTestTags.stepperPlus(SettingsTestTags.FTP))

        awaitSaved { it.ftpWatts == 205 }
        assertThat(settingsRepository.lastSaved?.ftpWatts).isEqualTo(205)
    }

    @Test
    fun turningFtpOffClearsItRatherThanStoringAFallback() {
        settingsRepository.emitSettings(TestSettings.default().copy(ftpWatts = 250))
        setSettingsContent()
        composeTestRule.waitForIdle()

        clickTag(SettingsTestTags.switch(SettingsTestTags.FTP))

        // Null is the whole point: without an FTP, IF and TSS are unavailable
        // rather than computed against a number the rider never gave.
        awaitSaved { it.ftpWatts == null }
        assertThat(settingsRepository.lastSaved?.ftpWatts).isNull()
    }

    @Test
    fun enablingLthrSeedsTheAgePredictedFallbackSoNothingChangesYet() {
        settingsRepository.emitSettings(TestSettings.default().copy(age = 30, lthrBpm = null))
        setSettingsContent()
        composeTestRule.waitForIdle()

        clickTag(SettingsTestTags.switch(SettingsTestTags.LTHR))

        // Tanaka HRmax(30) = 187, 90 % = 168 — the same value the calculator
        // was already falling back to, so switching the row on is not a change
        // of training load, only of who owns the number.
        awaitSaved { it.lthrBpm == 168 }
        assertThat(settingsRepository.lastSaved?.lthrBpm).isEqualTo(168)
    }

    @Test
    fun autoDetectionCanBeTurnedOff() {
        setSettingsContent()

        clickTag(SettingsTestTags.switch(SettingsTestTags.AUTO_DETECT_THRESHOLDS))

        awaitSaved { !it.autoDetectThresholds }
        assertThat(settingsRepository.lastSaved?.autoDetectThresholds).isEqualTo(false)
    }

    @Test
    fun autoDetectionCanBeTurnedBackOn() {
        settingsRepository.emitSettings(TestSettings.default().copy(autoDetectThresholds = false))
        setSettingsContent()
        composeTestRule.waitForIdle()

        clickTag(SettingsTestTags.switch(SettingsTestTags.AUTO_DETECT_THRESHOLDS))

        awaitSaved { it.autoDetectThresholds }
        assertThat(settingsRepository.lastSaved?.autoDetectThresholds).isEqualTo(true)
    }
}
