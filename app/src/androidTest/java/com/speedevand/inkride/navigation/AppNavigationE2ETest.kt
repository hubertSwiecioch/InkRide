package com.speedevand.inkride.navigation

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import assertk.assertThat
import assertk.assertions.isTrue
import com.speedevand.inkride.MainActivity
import com.speedevand.inkride.R
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.support.TestRides
import com.speedevand.inkride.core.testing.support.stringRes
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.history.presentation.HistoryTestTags
import com.speedevand.inkride.settings.presentation.SettingsTab
import com.speedevand.inkride.settings.presentation.SettingsTestTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * Exercises the bottom navigation and back stack against the real nav graph.
 * No Koin overrides: this is about navigation wiring, not sensors.
 */
class AppNavigationE2ETest {
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
    fun launchOnDashboard() {
        // Must be written before the activity starts: MainActivity waits for the
        // first emission and AppNavigation freezes the start destination.
        runBlocking {
            GlobalContext.get().get<UserSettingsRepository>().save(
                UserSettings(weightKg = 75, age = 30, hasCompletedOnboarding = true),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeTestRule.waitForIdle()
    }

    @After
    fun closeScenario() {
        // Room is real and shared with every other :app test class.
        runBlocking { GlobalContext.get().get<RideHistoryRepository>().deleteAll() }
        scenario?.close()
    }

    /**
     * The bottom-bar item merges its descendants and the merged node keeps only
     * the label text, so the icon's content description — the one identifier
     * that is unique on screen ("Settings" is also the settings screen's title)
     * — is reachable only through the unmerged tree.
     */
    private fun navItem(labelRes: Int) = composeTestRule.onNodeWithContentDescription(stringRes(labelRes), useUnmergedTree = true)

    private fun tapNav(labelRes: Int) {
        navItem(labelRes).performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun theBottomBarSwitchesBetweenRideHistoryAndSettings() {
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).assertIsDisplayed()

        tapNav(R.string.nav_history)
        composeTestRule
            .onNodeWithTag(HistoryTestTags.LIFETIME_STATS_BUTTON)
            .assertIsDisplayed()

        tapNav(R.string.nav_settings)
        composeTestRule
            .onNodeWithTag(SettingsTestTags.tab(SettingsTab.PROFILE))
            .assertIsDisplayed()

        tapNav(R.string.nav_ride)
        composeTestRule.onNodeWithTag(DashboardTestTags.START_PAUSE_BUTTON).assertIsDisplayed()
    }

    /**
     * Characterization test, not an endorsement: the bottom bar navigates with
     * `popUpTo(DashboardGraph) { saveState = true }` and `restoreState = true`,
     * but nothing is actually restored today — a tab comes back scrolled to the
     * top with its ViewModel rebuilt (the settings screen reopens on Profile,
     * whichever tab it was left on). If that is ever fixed, this test fails and
     * should be rewritten to assert restoration.
     */
    @Test
    fun returningToATabRebuildsItRatherThanRestoringIt() {
        seedRides(count = 20)
        tapNav(R.string.nav_history)
        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule.onAllNodesWithTag(HistoryTestTags.LIST).fetchSemanticsNodes().isNotEmpty()
        }
        val newestRideId = rideIds().first()
        composeTestRule.onNodeWithTag(HistoryTestTags.LIST).performScrollToIndex(18)
        composeTestRule.waitForIdle()
        // Pre-condition: scrolled far enough that the newest row is not composed.
        composeTestRule.onAllNodesWithTag(HistoryTestTags.row(newestRideId)).assertCountEquals(0)

        tapNav(R.string.nav_settings)
        composeTestRule.onNodeWithTag(SettingsTestTags.tab(SettingsTab.DISPLAY)).performClick()
        composeTestRule.waitForIdle()
        tapNav(R.string.nav_history)

        // The list is back at the top.
        composeTestRule.onNodeWithTag(HistoryTestTags.row(newestRideId)).assertIsDisplayed()

        tapNav(R.string.nav_settings)

        // And the settings screen is back on its first tab, not Display.
        composeTestRule
            .onNodeWithTag(SettingsTestTags.stepperValue(SettingsTestTags.WEIGHT))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithTag(SettingsTestTags.switch("show_distance"))
            .assertCountEquals(0)
    }

    @Test
    fun navigatingDeeperHidesTheBottomBarAndBackBringsItReturns() {
        tapNav(R.string.nav_settings)
        composeTestRule.onNodeWithTag(SettingsTestTags.tab(SettingsTab.BIKE)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithTag(SettingsTestTags.navRow(SettingsTestTags.BIKE_PROFILES))
            .performClick()
        composeTestRule.waitForIdle()

        // Bike profiles is not a bottom-nav destination, so the bar is gone.
        composeTestRule
            .onAllNodesWithContentDescription(stringRes(R.string.nav_history), useUnmergedTree = true)
            .assertCountEquals(0)

        pressBack()

        navItem(R.string.nav_history).assertIsDisplayed()
    }

    @Test
    fun systemBackFromTheDashboardDoesNotReturnToOnboarding() {
        pressBack()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            var finishing = false
            scenario?.onActivity { finishing = it.isFinishing }
            finishing
        }
        var finished = false
        scenario?.onActivity { finished = it.isFinishing }
        assertThat(finished).isTrue()
    }

    private fun seedRides(count: Int) {
        val repository = GlobalContext.get().get<RideHistoryRepository>()
        runBlocking {
            repeat(count) { i ->
                repository.save(
                    TestRides.record(id = 0L, startTimestamp = TestRides.START_MS + i * 86_400_000L),
                )
            }
        }
    }

    private fun rideIds(): List<Long> =
        runBlocking {
            GlobalContext
                .get()
                .get<RideHistoryRepository>()
                .observeAll()
                .first()
                .map { it.id }
        }

    private fun pressBack() {
        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
    }
}
