package com.speedevand.inkride.lifecycle

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.MainActivity
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.support.textOf
import com.speedevand.inkride.core.testing.support.waitUntilTagText
import com.speedevand.inkride.onboarding.presentation.OnboardingTestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * `OnboardingViewModel` keeps its page index in a `SavedStateHandle`; this is
 * the test that proves the wiring works when the activity is rebuilt.
 */
class OnboardingProcessDeathE2ETest {
    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @get:Rule
    val permissionRule: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            GrantPermissionRule.grant(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun launchFreshInstall() {
        runBlocking {
            GlobalContext.get().get<UserSettingsRepository>().save(
                UserSettings(weightKg = 75, age = 30, hasCompletedOnboarding = false),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitForIdle()
    }

    @After
    fun closeScenario() {
        // Leave the next test class with a completed-onboarding install.
        runBlocking {
            GlobalContext.get().get<UserSettingsRepository>().save(
                UserSettings(weightKg = 75, age = 30, hasCompletedOnboarding = true),
            )
        }
        scenario?.close()
    }

    @Test
    fun onboardingResumesOnTheSamePageAfterRecreation() {
        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(OnboardingTestTags.NEXT_BUTTON)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val firstPageTitle = composeTestRule.textOf(OnboardingTestTags.PAGE_TITLE)

        composeTestRule.onNodeWithTag(OnboardingTestTags.NEXT_BUTTON).performClick()
        composeTestRule.waitUntilTagText(OnboardingTestTags.PAGE_TITLE) { it != firstPageTitle }
        val titleBeforeRecreate = composeTestRule.textOf(OnboardingTestTags.PAGE_TITLE)

        scenario?.recreate()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(OnboardingTestTags.PAGE_TITLE).assertIsDisplayed()
        assertThat(composeTestRule.textOf(OnboardingTestTags.PAGE_TITLE)).isEqualTo(titleBeforeRecreate)
    }
}
