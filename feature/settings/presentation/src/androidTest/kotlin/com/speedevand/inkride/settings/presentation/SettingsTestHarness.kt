package com.speedevand.inkride.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.speedevand.inkride.core.domain.settings.BikeProfile
import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.fakes.FakeBikeProfileRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import com.speedevand.inkride.core.testing.rules.KoinTestRule
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.text
import org.junit.Rule
import org.koin.dsl.module

/**
 * Shared setup for the settings instrumented tests: a Koin container with the
 * real `settingsPresentationModule` plus fakes bound over its two repositories,
 * and a Compose rule hosting the `*Root` composable.
 *
 * Subclasses override [initialSettings] / [initialProfiles] to seed state
 * before the screen is first composed.
 */
abstract class SettingsTestHarness {
    protected open fun initialSettings(): UserSettings = TestSettings.default()

    protected open fun initialProfiles(): List<BikeProfile> = emptyList()

    val settingsRepository by lazy { FakeUserSettingsRepository(initialSettings()) }
    val bikeProfileRepository by lazy { FakeBikeProfileRepository(initialProfiles()) }

    @get:Rule(order = 0)
    val koinRule by lazy {
        KoinTestRule(
            modules =
                listOf(
                    settingsPresentationModule,
                    module {
                        single<UserSettingsRepository> { settingsRepository }
                        single<BikeProfileRepository> { bikeProfileRepository }
                    },
                ),
        )
    }

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    fun setSettingsContent(
        onBack: () -> Unit = {},
        onOpenBleSensors: () -> Unit = {},
        onOpenBikeProfiles: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            SettingsRoot(
                onBack = onBack,
                onOpenBleSensors = onOpenBleSensors,
                onOpenBikeProfiles = onOpenBikeProfiles,
            )
        }
    }

    fun selectTab(tab: SettingsTab) {
        composeTestRule.onNodeWithTag(SettingsTestTags.tab(tab)).performClick()
        composeTestRule.waitForIdle()
    }

    /**
     * Scrolls a tagged row into view, then clicks it. Each section is one long
     * scrolling column: on a phone-sized screen its lower rows (the metric
     * switches, the alert steppers) start outside the viewport, where a click
     * would land on whatever is actually drawn there.
     */
    fun clickTag(tag: String) {
        composeTestRule.onNodeWithTag(tag).performScrollTo().performClick()
    }

    /** The text of a tagged node, scrolled into view first — see [clickTag]. */
    fun textOfTag(tag: String): String = composeTestRule.onNodeWithTag(tag).performScrollTo().text()

    /**
     * Waits for a write to land in the repository. Saves travel through the
     * ViewModel's coroutine scope, so they are not visible the instant a click
     * returns.
     */
    fun awaitSaved(predicate: (UserSettings) -> Boolean) {
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.let(predicate) == true
        }
    }
}
