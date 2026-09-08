package com.speedevand.inkride.onboarding

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
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

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(OnboardingTestTags.SKIP_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
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

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(DashboardTestTags.METRICS_PAGER).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(DashboardTestTags.METRICS_PAGER).assertExists()
    }

    @Test
    fun permissionAlreadyGrantedAutoAdvancesPastPermissionSteps() {
        launch(hasCompletedOnboarding = false)

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(OnboardingTestTags.SKIP_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(OnboardingTestTags.SKIP_BUTTON).performClick()

        // Permissions are pre-granted via permissionRule, so PermissionPrimingPage's
        // primary button should read "Continue" and tapping it should advance
        // immediately without ever needing "Skip for now".
        composeTestRule.onNodeWithTag(OnboardingTestTags.PERMISSION_PRIMARY_BUTTON).assertIsDisplayed()
        composeTestRule.onNodeWithTag(OnboardingTestTags.PERMISSION_PRIMARY_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule
                .onAllNodesWithTag(DashboardTestTags.METRICS_PAGER)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                composeTestRule
                    .onAllNodesWithTag(OnboardingTestTags.PERMISSION_PRIMARY_BUTTON)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        // If there's a second permission step (API 33+), tap it too.
        if (
            composeTestRule
                .onAllNodesWithTag(OnboardingTestTags.PERMISSION_PRIMARY_BUTTON)
                .fetchSemanticsNodes()
                .isNotEmpty()
        ) {
            composeTestRule.onNodeWithTag(OnboardingTestTags.PERMISSION_PRIMARY_BUTTON).performClick()
        }

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(DashboardTestTags.METRICS_PAGER).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(DashboardTestTags.METRICS_PAGER).assertExists()
    }
}
