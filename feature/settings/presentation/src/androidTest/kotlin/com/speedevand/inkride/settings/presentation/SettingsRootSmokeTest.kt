package com.speedevand.inkride.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.fakes.FakeBikeProfileRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import com.speedevand.inkride.core.testing.rules.KoinTestRule
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.stringRes
import org.junit.Rule
import org.junit.Test
import org.koin.dsl.module

/**
 * Proves the module-level instrumented setup works: a Koin container started by
 * the test, fakes bound in place of the real repositories, and a `*Root`
 * composable resolving its ViewModel through `koinViewModel()`.
 */
class SettingsRootSmokeTest {
    private val settingsRepository = FakeUserSettingsRepository(TestSettings.default())
    private val bikeProfileRepository = FakeBikeProfileRepository()

    @get:Rule(order = 0)
    val koinRule =
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

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsRootRendersItsTitle() {
        composeTestRule.setContent {
            SettingsRoot(
                onBack = {},
                onOpenBleSensors = {},
                onOpenBikeProfiles = {},
            )
        }

        composeTestRule
            .onNodeWithText(stringRes(R.string.settings_title))
            .assertIsDisplayed()
    }
}
