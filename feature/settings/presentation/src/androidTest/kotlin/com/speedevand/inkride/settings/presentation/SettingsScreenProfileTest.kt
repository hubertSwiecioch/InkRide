package com.speedevand.inkride.settings.presentation

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.stringRes
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenProfileTest : SettingsTestHarness() {
    /**
     * The bike tab and the bike section header render the same word, so the
     * header is matched as "this text, on a node that is not a tab" — tabs are
     * the only selectable nodes on this screen.
     */
    private fun assertSectionShown(
        @StringRes headerRes: Int,
    ) {
        composeTestRule
            .onNode(hasText(stringRes(headerRes)) and !isSelectable())
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun profileTabIsSelectedFirstAndShowsThePersonalSection() {
        setSettingsContent()

        assertSectionShown(R.string.settings_section_personal)
    }

    @Test
    fun switchingTabsSwapsTheSectionOnScreen() {
        setSettingsContent()

        selectTab(SettingsTab.BIKE)
        assertSectionShown(R.string.settings_section_bike)

        selectTab(SettingsTab.DISPLAY)
        assertSectionShown(R.string.settings_section_units)

        selectTab(SettingsTab.PROFILE)
        assertSectionShown(R.string.settings_section_personal)
    }

    @Test
    fun steppingWeightUpPersistsTheNewValue() {
        setSettingsContent()

        clickTag(SettingsTestTags.stepperPlus(SettingsTestTags.WEIGHT))

        awaitSaved { it.weightKg == 76 }
        assertThat(settingsRepository.lastSaved?.weightKg).isEqualTo(76)
    }

    @Test
    fun steppingAgeDownPersistsTheNewValue() {
        setSettingsContent()

        clickTag(SettingsTestTags.stepperMinus(SettingsTestTags.AGE))

        awaitSaved { it.age == 29 }
        assertThat(settingsRepository.lastSaved?.age).isEqualTo(29)
    }

    @Test
    fun theWeightStepperClampsAtItsMaximum() {
        val maxKg = SettingsConstants.WEIGHT_MAX_KG.toInt()
        settingsRepository.emitSettings(TestSettings.default().copy(weightKg = maxKg))
        setSettingsContent()
        composeTestRule.waitForIdle()

        clickTag(SettingsTestTags.stepperPlus(SettingsTestTags.WEIGHT))
        composeTestRule.waitForIdle()

        assertThat(textOfTag(SettingsTestTags.stepperValue(SettingsTestTags.WEIGHT))).isEqualTo("$maxKg kg")
    }

    @Test
    fun choosingALanguagePersistsIt() {
        setSettingsContent()

        clickTag(SettingsTestTags.radio("pl"))

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.languageCode == "pl"
        }
        assertThat(settingsRepository.lastSaved?.languageCode).isEqualTo("pl")
    }

    @Test
    fun imperialUnitsRenderWeightInPounds() {
        settingsRepository.emitSettings(TestSettings.default(units = MeasurementUnits.IMPERIAL))
        setSettingsContent()
        composeTestRule.waitForIdle()

        assertThat(
            textOfTag(SettingsTestTags.stepperValue(SettingsTestTags.WEIGHT)).endsWith("lbs"),
        ).isTrue()
    }

    @Test
    fun theBikeProfilesRowInvokesItsNavigationCallback() {
        var opened = false
        setSettingsContent(onOpenBikeProfiles = { opened = true })

        selectTab(SettingsTab.BIKE)
        clickTag(SettingsTestTags.navRow(SettingsTestTags.BIKE_PROFILES))

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { opened }
        assertThat(opened).isTrue()
    }

    @Test
    fun theBluetoothSensorsRowInvokesItsNavigationCallback() {
        var opened = false
        setSettingsContent(onOpenBleSensors = { opened = true })

        selectTab(SettingsTab.BIKE)
        clickTag(SettingsTestTags.navRow(SettingsTestTags.BLE_SENSORS))

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { opened }
        assertThat(opened).isTrue()
    }

    @Test
    fun theDefaultBikeSectionIsHiddenWhileABikeProfileIsActive() {
        settingsRepository.emitSettings(TestSettings.default(activeBikeProfileId = 1L))
        setSettingsContent()

        selectTab(SettingsTab.BIKE)

        composeTestRule
            .onNodeWithTag(SettingsTestTags.stepperValue(SettingsTestTags.BIKE_WEIGHT))
            .assertIsNotDisplayed()
    }

    @Test
    fun choosingABikeTypePersistsIt() {
        setSettingsContent()

        selectTab(SettingsTab.BIKE)
        clickTag(SettingsTestTags.radio(BikeType.MTB.name))

        awaitSaved { it.bikeType == BikeType.MTB }
        assertThat(settingsRepository.lastSaved?.bikeType).isEqualTo(BikeType.MTB)
    }

    @Test
    fun steppingBikeWeightPersistsTheNewValue() {
        setSettingsContent()

        selectTab(SettingsTab.BIKE)
        clickTag(SettingsTestTags.stepperPlus(SettingsTestTags.BIKE_WEIGHT))

        awaitSaved { it.bikeWeightKg > 10.0 }
        assertThat(settingsRepository.lastSaved?.bikeWeightKg).isEqualTo(10.5)
    }
}
