package com.speedevand.inkride.onboarding

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.speedevand.inkride.MainActivity
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.onboarding.presentation.OnboardingTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * Seeds UserSettings directly (bypassing the UI) before launching
 * MainActivity, matching RideTrackingE2ETestBase's approach -- the
 * repository must be written to before the activity's first composition
 * reads it.
 */
class OnboardingE2ETest {
    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch(hasCompletedOnboarding: Boolean) {
        val userSettingsRepository = GlobalContext.get().get<UserSettingsRepository>()
        runBlocking {
            userSettingsRepository.save(
                UserSettings(weightKg = 75, age = 30, hasCompletedOnboarding = hasCompletedOnboarding),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun firstLaunchShowsOnboardingThenReachesDashboardAfterSkippingThrough() {
        launch(hasCompletedOnboarding = false)

        composeTestRule.onNodeWithTag(OnboardingTestTags.SKIP_BUTTON).performClick()

        // One permission-priming step per required permission (location
        // always, notifications only on API 33+) -- skip through however
        // many appear instead of hardcoding a count that varies by OS
        // version.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule
                .onAllNodesWithTag(DashboardTestTags.METRICS_PAGER)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                composeTestRule
                    .onAllNodesWithTag(OnboardingTestTags.PERMISSION_SKIP_BUTTON)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        while (
            composeTestRule
                .onAllNodesWithTag(OnboardingTestTags.PERMISSION_SKIP_BUTTON)
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            composeTestRule.onNodeWithTag(OnboardingTestTags.PERMISSION_SKIP_BUTTON).performClick()
        }

        composeTestRule.onNodeWithTag(DashboardTestTags.METRICS_PAGER).assertExists()

        val userSettingsRepository = GlobalContext.get().get<UserSettingsRepository>()
        val settings = runBlocking { userSettingsRepository.observeSettings().first() }
        assertTrue(settings.hasCompletedOnboarding)
    }

    @Test
    fun returningUserSkipsOnboardingEntirely() {
        launch(hasCompletedOnboarding = true)

        composeTestRule.onNodeWithTag(DashboardTestTags.METRICS_PAGER).assertExists()
    }
}
