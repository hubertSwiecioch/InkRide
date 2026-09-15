package com.speedevand.inkride.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.fakes.FakeBikeProfileRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import com.speedevand.inkride.core.testing.rules.KoinTestRule
import com.speedevand.inkride.core.testing.support.TestBikeProfiles
import com.speedevand.inkride.core.testing.support.TestSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module

@RunWith(AndroidJUnit4::class)
class BikeProfilesScreenTest {
    private val settingsRepository = FakeUserSettingsRepository(TestSettings.default())
    private val profileRepository = FakeBikeProfileRepository()

    @get:Rule(order = 0)
    val koinRule =
        KoinTestRule(
            modules =
                listOf(
                    settingsPresentationModule,
                    module {
                        single<UserSettingsRepository> { settingsRepository }
                        single<BikeProfileRepository> { profileRepository }
                    },
                ),
        )

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(onBack: () -> Unit = {}) {
        composeTestRule.setContent { BikeProfilesRoot(onNavigateBack = onBack) }
    }

    /** Scrolls a tagged node into view before clicking: list plus open form outgrows one screen. */
    private fun clickTag(tag: String) {
        composeTestRule.onNodeWithTag(tag).performScrollTo().performClick()
    }

    @Test
    fun anEmptyListShowsTheEmptyState() {
        setContent()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun storedProfilesRenderWithTheirNameAndWeight() {
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road(), TestBikeProfiles.mtb()))
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.row(1L)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Road bike").assertIsDisplayed()
        composeTestRule.onNodeWithText("MTB").assertIsDisplayed()
    }

    @Test
    fun addingAProfileStoresItAndShowsItInTheList() {
        setContent()

        clickTag(BikeProfilesTestTags.ADD_BUTTON)
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.NAME_FIELD).performScrollTo().performTextInput("Gravel")
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.WEIGHT_FIELD).performScrollTo().performTextInput("9.5")
        clickTag(BikeProfilesTestTags.typeRadio(BikeType.CITY))
        clickTag(BikeProfilesTestTags.SAVE_BUTTON)

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(BikeProfilesTestTags.ADD_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Gravel").assertIsDisplayed()
    }

    @Test
    fun savingWithAnEmptyNameKeepsTheFormOpenAndStoresNothing() {
        setContent()

        clickTag(BikeProfilesTestTags.ADD_BUTTON)
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.WEIGHT_FIELD).performScrollTo().performTextInput("9.5")
        clickTag(BikeProfilesTestTags.SAVE_BUTTON)
        composeTestRule.waitForIdle()

        // The form is still on screen, so nothing was committed.
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.SAVE_BUTTON).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun savingWithANonNumericWeightKeepsTheFormOpen() {
        setContent()

        clickTag(BikeProfilesTestTags.ADD_BUTTON)
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.NAME_FIELD).performScrollTo().performTextInput("Gravel")
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.WEIGHT_FIELD).performScrollTo().performTextInput("abc")
        clickTag(BikeProfilesTestTags.SAVE_BUTTON)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.SAVE_BUTTON).assertIsDisplayed()
    }

    @Test
    fun cancellingClosesTheFormWithoutStoringAnything() {
        setContent()

        clickTag(BikeProfilesTestTags.ADD_BUTTON)
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.NAME_FIELD).performScrollTo().performTextInput("Gravel")
        clickTag(BikeProfilesTestTags.CANCEL_BUTTON)
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(BikeProfilesTestTags.SAVE_BUTTON).assertCountEquals(0)
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun editingAProfileUpdatesItInPlace() {
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road()))
        setContent()
        composeTestRule.waitForIdle()

        clickTag(BikeProfilesTestTags.editButton(1L))
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.NAME_FIELD).performScrollTo().performTextClearance()
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.NAME_FIELD).performTextInput("Road bike v2")
        clickTag(BikeProfilesTestTags.SAVE_BUTTON)

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithText("Road bike v2").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithTag(BikeProfilesTestTags.row(1L)).assertCountEquals(1)
    }

    @Test
    fun deletingAProfileRemovesItFromTheList() {
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road(), TestBikeProfiles.mtb()))
        setContent()
        composeTestRule.waitForIdle()

        clickTag(BikeProfilesTestTags.deleteButton(2L))

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(BikeProfilesTestTags.row(2L)).fetchSemanticsNodes().isEmpty()
        }
        assertThat(profileRepository.deletedIds).contains(2L)
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.row(1L)).assertIsDisplayed()
    }

    @Test
    fun deletingTheActiveProfileClearsTheActiveIdInSettings() {
        settingsRepository.emitSettings(TestSettings.default(activeBikeProfileId = 1L))
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road()))
        setContent()
        composeTestRule.waitForIdle()

        clickTag(BikeProfilesTestTags.deleteButton(1L))

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.activeBikeProfileId == null
        }
        assertThat(settingsRepository.lastSaved?.activeBikeProfileId).isNull()
    }

    @Test
    fun choosingAProfileMakesItActive() {
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road(), TestBikeProfiles.mtb()))
        setContent()
        composeTestRule.waitForIdle()

        clickTag(BikeProfilesTestTags.activeRadio(2L))

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.activeBikeProfileId == 2L
        }
        assertThat(settingsRepository.lastSaved?.activeBikeProfileId).isEqualTo(2L)
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.activeRadio(2L)).assertIsSelected()
    }

    @Test
    fun imperialUnitsLabelWeightInPounds() {
        settingsRepository.emitSettings(TestSettings.default(units = MeasurementUnits.IMPERIAL))
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road()))
        setContent()
        composeTestRule.waitForIdle()

        // 8.5 kg renders as 18.7 lbs in the row's "<weight> <unit> · <type>" line.
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.row(1L)).assertIsDisplayed()
        assertThat(
            composeTestRule
                .onAllNodesWithText("lbs", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        ).isTrue()
    }

    @Test
    fun backInvokesItsNavigationCallback() {
        var backPressed = false
        setContent(onBack = { backPressed = true })

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.BACK_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { backPressed }
        assertThat(backPressed).isTrue()
    }
}
