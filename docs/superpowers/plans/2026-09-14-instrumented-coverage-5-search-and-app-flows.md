# Instrumented Coverage, Plan 5: Destination Search and App Flows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cover the destination-search error paths at module level, and the three flows that only exist across module boundaries — bottom-nav navigation and back stack, ride → history persistence, and process death.

**Architecture:** The destination-search tests follow Plan 3's module-level shape: `DestinationSearchRoot` over fakes in a `KoinTestRule`. The `:app` tests are different in kind — they run against the real Koin graph, the real nav graph and real Room, launching `MainActivity` with `ActivityScenario`, which is the existing `RideTrackingE2ETestBase` pattern. They are in `:app` precisely because a module test cannot reach across modules.

**Tech Stack:** Compose `ui-test-junit4`, `createEmptyComposeRule` + `ActivityScenario` for `:app`, Koin 4.2.1, JUnit4 + `AndroidJUnit4`, assertk.

**Spec:** `docs/superpowers/specs/2026-09-14-full-instrumented-test-coverage-design.md`

**Depends on:** Plan 1 in full. Task 3 additionally depends on Plan 4 Task 1 (the `HistoryTestTags` object) and Plan 4 Task 3 (`RideDetailTestTags`), because the ride → history flow asserts against the history list and detail screens.

## Global Constraints

- Kotlin JVM target 11; `compileSdk = 36`, `minSdk = 26`.
- `./gradlew ktlintCheck` must pass; run `./gradlew ktlintFormat` before every commit, including `androidTest` sources.
- No network. `PlaceSearchService`, `RoutingService` and `CurrentLocationProvider` are always faked — a test that reaches Nominatim or OSRM is a broken test, not a slow one.
- `:app` tests must seed `UserSettings` through the repository **before** launching `MainActivity`: `MainActivity` waits for the first emission and `AppNavigation` freezes its start destination on first composition, so a later write cannot redirect it.
- `:app` tests must leave no state behind: stop `RideTracker`, stop `TrackingService`, close the scenario, and unload any Koin overrides in `@After`. Room is real and shared across test classes in this module.
- Bottom-nav items are selected by the content description on their icon (`stringResource(item.labelRes)`), which already exists — no production change is needed for navigation tests.
- `DestinationSearchEvent.ShowError` surfaces outside the semantics tree. Assert the observable consequence (no route loaded, no navigation, results unchanged), never the toast text.

---

### Task 1: `DestinationSearchScreen` tags and error-path tests

The happy path is already covered end to end by `RideTrackingRouteSearchTest` in `:app`. This class takes the paths that an end-to-end test cannot force.

**Files:**
- Create: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchTestTags.kt`
- Modify: `feature/dashboard/presentation/src/main/java/com/speedevand/inkride/dashboard/presentation/DestinationSearchScreen.kt`
- Create: `feature/dashboard/presentation/src/androidTest/kotlin/com/speedevand/inkride/dashboard/presentation/DestinationSearchScreenTest.kt`

**Interfaces:**
- Consumes: `KoinTestRule`, `FakePlaceSearchService`, `FakeRoutingService`, `FakeCurrentLocationProvider`, `TestRoutes` (Plan 1 Task 4).
- Produces: `object DestinationSearchTestTags` with `const val QUERY_FIELD`, `RESULT_LIST`, `EMPTY_STATE`, `SEARCH_PROGRESS`, `ROUTING_PROGRESS`, `BACK_BUTTON`, and `fun resultRow(index: Int): String`.

---

- [ ] **Step 1: Write the tag object**

```kotlin
package com.speedevand.inkride.dashboard.presentation

/**
 * Stable identifiers for the destination-search screen. Result rows are keyed
 * by index because two places can share a display name.
 */
object DestinationSearchTestTags {
    const val QUERY_FIELD = "destination_search_query"
    const val RESULT_LIST = "destination_search_results"
    const val EMPTY_STATE = "destination_search_empty"
    const val SEARCH_PROGRESS = "destination_search_progress"
    const val ROUTING_PROGRESS = "destination_search_routing_progress"
    const val BACK_BUTTON = "destination_search_back"

    fun resultRow(index: Int): String = "destination_search_result_$index"
}
```

- [ ] **Step 2: Apply the tags**

In `DestinationSearchScreen.kt`, tag the query `TextFieldMMD` (`QUERY_FIELD`), the results list container (`RESULT_LIST`), each result row by its index (`resultRow(index)` — switch the results loop to `forEachIndexed` if it is not already), the empty/no-results node (`EMPTY_STATE`), the searching and routing progress indicators (`SEARCH_PROGRESS`, `ROUTING_PROGRESS`) and the back control (`BACK_BUTTON`).

If the screen has no distinct "no results" node today, add one: a `TextMMD` shown when `state.query` is at or above the minimum length, `state.results` is empty and `state.isSearching` is false, using a new `destination_search_no_results` string resource. Without it, "no results" and "nothing typed yet" are indistinguishable to a user as well as to a test.

- [ ] **Step 3: Write the tests**

```kotlin
package com.speedevand.inkride.dashboard.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.domain.tracking.RoutingService
import com.speedevand.inkride.core.testing.fakes.FakeCurrentLocationProvider
import com.speedevand.inkride.core.testing.fakes.FakePlaceSearchService
import com.speedevand.inkride.core.testing.fakes.FakeRoutingService
import com.speedevand.inkride.core.testing.rules.KoinTestRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module

@RunWith(AndroidJUnit4::class)
class DestinationSearchScreenTest {
    private val placeSearch = FakePlaceSearchService()
    private val routing = FakeRoutingService()
    private val location = FakeCurrentLocationProvider()

    @get:Rule(order = 0)
    val koinRule =
        KoinTestRule(
            modules =
                listOf(
                    dashboardPresentationModule,
                    module {
                        single<PlaceSearchService> { placeSearch }
                        single<RoutingService> { routing }
                        single<CurrentLocationProvider> { location }
                    },
                ),
        )

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(onBack: () -> Unit = {}) {
        composeTestRule.setContent { DestinationSearchRoot(onNavigateBack = onBack) }
    }

    private fun type(query: String) {
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.QUERY_FIELD).performTextInput(query)
    }

    private fun awaitResults() {
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag(DestinationSearchTestTags.resultRow(0))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun aQueryBelowTheMinimumLengthNeverReachesTheService() {
        setContent()

        type("wa")
        // Longer than the debounce, so a query that was going to be sent has been.
        composeTestRule.waitForIdle()
        Thread.sleep(DestinationSearchViewModel.SEARCH_DEBOUNCE_MS + 300L)

        assertThat(placeSearch.queries).isEmpty()
    }

    @Test
    fun matchingPlacesRenderAsSelectableRows() {
        setContent()

        type("warsaw")
        awaitResults()

        composeTestRule.onNodeWithTag(DestinationSearchTestTags.resultRow(0)).assertIsDisplayed()
    }

    @Test
    fun noMatchesShowsTheEmptyState() {
        placeSearch.result = Result.Error(PlaceSearchError.NO_RESULTS)
        setContent()

        type("nowhereville")

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag(DestinationSearchTestTags.EMPTY_STATE)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun aFailedSearchLeavesTheResultListEmpty() {
        placeSearch.result = Result.Error(PlaceSearchError.NETWORK_FAILED)
        setContent()

        type("warsaw")
        composeTestRule.waitForIdle()
        Thread.sleep(DestinationSearchViewModel.SEARCH_DEBOUNCE_MS + 500L)

        composeTestRule.onAllNodesWithTag(DestinationSearchTestTags.resultRow(0)).assertCountEquals(0)
    }

    @Test
    fun selectingAResultWithNoCyclingRouteDoesNotNavigateAway() {
        routing.result = Result.Error(RoutingError.NO_ROUTE_FOUND)
        var navigatedBack = false
        setContent(onBack = { navigatedBack = true })

        type("warsaw")
        awaitResults()
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.resultRow(0)).performClick()
        composeTestRule.waitForIdle()

        assertThat(routing.callCount).isEqualTo(1)
        assertThat(navigatedBack).isEqualTo(false)
    }

    @Test
    fun aFailedRoutingRequestDoesNotNavigateAway() {
        routing.result = Result.Error(RoutingError.NETWORK_FAILED)
        var navigatedBack = false
        setContent(onBack = { navigatedBack = true })

        type("warsaw")
        awaitResults()
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.resultRow(0)).performClick()
        composeTestRule.waitForIdle()

        assertThat(navigatedBack).isEqualTo(false)
    }

    @Test
    fun aDeniedLocationPermissionStopsTheRouteBeforeRoutingIsCalled() {
        location.result = Result.Error(LocationError.PERMISSION_DENIED)
        setContent()

        type("warsaw")
        awaitResults()
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.resultRow(0)).performClick()
        composeTestRule.waitForIdle()

        assertThat(routing.callCount).isEqualTo(0)
    }

    @Test
    fun aTimedOutLocationLookupStopsTheRouteBeforeRoutingIsCalled() {
        location.result = Result.Error(LocationError.TIMED_OUT)
        setContent()

        type("warsaw")
        awaitResults()
        composeTestRule.onNodeWithTag(DestinationSearchTestTags.resultRow(0)).performClick()
        composeTestRule.waitForIdle()

        assertThat(routing.callCount).isEqualTo(0)
    }

    @Test
    fun backInvokesItsNavigationCallback() {
        var backPressed = false
        setContent(onBack = { backPressed = true })

        composeTestRule.onNodeWithTag(DestinationSearchTestTags.BACK_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { backPressed }
        assertThat(backPressed).isTrue()
    }
}
```

Three details to settle against the real code before running:

1. `SEARCH_DEBOUNCE_MS` and `MIN_QUERY_LENGTH` are private in `DestinationSearchViewModel`. Move them into a `companion object` marked `@VisibleForTesting`, or duplicate the debounce value as a local constant in the test with a comment pointing at the source — do not guess it.
2. `DestinationSearchRoot(onNavigateBack: () -> Unit)` takes no other parameter today; confirm the signature.
3. `dashboardPresentationModule` also binds `GpxRouteLoader` from `androidContext()` and the `DashboardViewModel`, which needs a `RideTracker`. If Koin fails to resolve `RideTracker` when the module is loaded, bind a `RideTracker` in the test module too, or split the destination-search bindings into their own Koin module in production — prefer the former, since the second is a production change this plan does not scope.

The two `Thread.sleep` calls are deliberate and are the only ones in this plan: they wait out a real debounce inside the ViewModel, which has no test-clock hook. Every other wait uses `waitUntil`.

- [ ] **Step 4: Run the class**

Run: `./gradlew :feature:dashboard:presentation:connectedDebugAndroidTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/dashboard/presentation
git commit -m "test: add instrumented destination search error-path tests"
```

---

### Task 2: App navigation and back stack

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/navigation/AppNavigationE2ETest.kt`

**Interfaces:**
- Consumes: `stringRes` (Plan 1); `SettingsTestTags` (Plan 3 Task 1); `HistoryTestTags` (Plan 4 Task 1); `DashboardTestTags` (existing).
- Produces: nothing other tasks depend on.

---

- [ ] **Step 1: Write the test**

This class uses the real Koin graph — no overrides — because it exercises navigation, not sensors. It seeds settings so the app starts on the dashboard rather than onboarding.

```kotlin
package com.speedevand.inkride.navigation

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import assertk.assertThat
import assertk.assertions.isTrue
import com.speedevand.inkride.MainActivity
import com.speedevand.inkride.R
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.support.stringRes
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.history.presentation.HistoryTestTags
import com.speedevand.inkride.settings.presentation.SettingsTab
import com.speedevand.inkride.settings.presentation.SettingsTestTags
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
        scenario?.close()
    }

    private fun tapNav(labelRes: Int) {
        composeTestRule.onNodeWithContentDescription(stringRes(labelRes)).performClick()
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

    @Test
    fun returningToATabRestoresTheStateItWasLeftIn() {
        tapNav(R.string.nav_settings)
        composeTestRule.onNodeWithTag(SettingsTestTags.tab(SettingsTab.DISPLAY)).performClick()
        composeTestRule.waitForIdle()

        tapNav(R.string.nav_history)
        tapNav(R.string.nav_settings)

        // The DISPLAY tab's own content, not PROFILE's, proving restoreState worked.
        composeTestRule
            .onNodeWithTag(SettingsTestTags.switch("show_distance"))
            .assertIsDisplayed()
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
            .onAllNodesWithContentDescription(stringRes(R.string.nav_history))
            .assertCountEquals(0)

        pressBack()

        composeTestRule
            .onNodeWithContentDescription(stringRes(R.string.nav_history))
            .assertIsDisplayed()
    }

    @Test
    fun systemBackFromTheDashboardDoesNotReturnToOnboarding() {
        var finished = false

        pressBack()
        scenario?.onActivity { finished = it.isFinishing }

        assertThat(finished).isTrue()
    }

    private fun pressBack() {
        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.waitForIdle()
    }
}
```

Add the import `androidx.compose.ui.test.onAllNodesWithContentDescription`.

`systemBackFromTheDashboardDoesNotReturnToOnboarding` asserts the activity is finishing, which is the observable form of "the onboarding route is not on the back stack". If `isFinishing` is false because the dispatcher ran asynchronously, wrap the read in `composeTestRule.waitUntil { … }`.

- [ ] **Step 2: Run the class**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*AppNavigationE2ETest"`
Expected: PASS, 4 tests.

- [ ] **Step 3: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add app/src/androidTest
git commit -m "test: add app navigation and back stack E2E tests"
```

---

### Task 3: Ride to history persistence flow

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/history/RideToHistoryE2ETest.kt`

**Interfaces:**
- Consumes: `RideTrackingE2ETestBase` (existing, in `com.speedevand.inkride.tracking`); `HistoryTestTags` and `RideDetailTestTags` (Plan 4); `stringRes`, `textOf` (Plan 1).
- Produces: nothing other tasks depend on.

---

- [ ] **Step 1: Write the test**

Extending `RideTrackingE2ETestBase` gets the fake sensor sources, the seeded settings, the launched `MainActivity`, and the teardown that stops `RideTracker` and `TrackingService` — all of which this flow needs.

```kotlin
package com.speedevand.inkride.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.R
import com.speedevand.inkride.core.testing.support.stringRes
import com.speedevand.inkride.core.testing.support.textOf
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.history.presentation.HistoryTestTags
import com.speedevand.inkride.history.presentation.RideDetailTestTags
import com.speedevand.inkride.tracking.RideTrackingE2ETestBase
import org.junit.Test

/**
 * The only test that crosses the real tracking → Room → history boundary: a
 * ride recorded on the dashboard, read back through the history repository, and
 * deleted again. Everything in between is real — real Room, real repositories,
 * real nav graph — with only the GPS/BLE sources faked.
 */
class RideToHistoryE2ETest : RideTrackingE2ETestBase() {
    @Test
    fun aRecordedRideAppearsInHistoryWithMatchingDetailAndCanBeDeleted() {
        startRideAndSettle()
        feedMovingSteps(count = 12, speedKmh = 20.0)

        val dashboardDistance = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE)

        composeTestRule.onNodeWithTag(DashboardTestTags.STOP_RESET_BUTTON).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(stringRes(R.string.nav_history)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(HistoryTestTags.LIST)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        val rideId = firstRideId()
        composeTestRule.onNodeWithTag(HistoryTestTags.row(rideId)).assertIsDisplayed()
        val listDistance = composeTestRule.textOf(HistoryTestTags.rowDistance(rideId))

        composeTestRule.onNodeWithTag(HistoryTestTags.row(rideId)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(RideDetailTestTags.detail(RideDetailTestTags.DISTANCE))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        assertThat(
            composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.DISTANCE)),
        ).isEqualTo(listDistance)
        // The dashboard shows the same distance the ride was saved with.
        assertThat(listDistance).isEqualTo(dashboardDistance)

        composeTestRule.onNodeWithTag(RideDetailTestTags.DELETE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_ACCEPT).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(HistoryTestTags.row(rideId))
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * Room is real and shared across test classes in this module, so the id of
     * the ride just recorded is not predictable — read it from the repository
     * instead of assuming 1.
     */
    private fun firstRideId(): Long =
        kotlinx.coroutines.runBlocking {
            org.koin.core.context.GlobalContext
                .get()
                .get<com.speedevand.inkride.core.domain.history.RideHistoryRepository>()
                .observeAll()
                .let { kotlinx.coroutines.flow.first(it) }
                .first()
                .id
        }
}
```

Clean up `firstRideId`'s fully-qualified names into proper imports when writing the file — they are spelled out here only to make the dependencies explicit. `kotlinx.coroutines.flow.first` is an extension, so the idiomatic form is `repository.observeAll().first().first().id` with `import kotlinx.coroutines.flow.first`.

Two things to confirm against the real code before running: that the dashboard's distance metric and the history row's distance use the same formatting (both go through `FORMAT_TWO_DECIMALS` plus a unit, but check), and that `STOP_RESET_BUTTON` saves the ride rather than requiring a second confirmation tap. If the dashboard formats distance differently, drop the `listDistance == dashboardDistance` assertion and keep the list-versus-detail one, which is the part that proves persistence round-tripped.

- [ ] **Step 2: Ensure this class leaves the database clean**

Add to the test class:

```kotlin
    @After
    fun clearHistory() {
        kotlinx.coroutines.runBlocking {
            org.koin.core.context.GlobalContext
                .get()
                .get<com.speedevand.inkride.core.domain.history.RideHistoryRepository>()
                .deleteAll()
        }
    }
```

`RideTrackingE2ETestBase` already has an `@After`; JUnit4 runs a subclass's `@After` before the superclass's, which is the order wanted here — clear the rows, then let the base tear down the tracker and service.

- [ ] **Step 3: Run the class**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*RideToHistoryE2ETest"`
Expected: PASS, 1 test.

- [ ] **Step 4: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add app/src/androidTest
git commit -m "test: add ride-to-history persistence E2E test"
```

---

### Task 4: Process death

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/lifecycle/ProcessDeathE2ETest.kt`

**Interfaces:**
- Consumes: `RideTrackingE2ETestBase` (existing); `OnboardingTestTags` (existing); `DashboardTestTags` (existing).
- Produces: nothing other tasks depend on.

---

- [ ] **Step 1: Write the active-ride recreation test**

`ActivityScenario.recreate()` runs the full configuration-change path, so this covers what `StateRestorationTester` cannot: the activity being rebuilt around a process-scoped `RideTracker`.

`RideTrackingE2ETestBase` keeps its `ActivityScenario` private, so expose it first. In `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingE2ETestBase.kt`, change:

```kotlin
    private var scenario: ActivityScenario<MainActivity>? = null
```

to:

```kotlin
    protected var scenario: ActivityScenario<MainActivity>? = null
        private set
```

and add a helper next to `startRideAndSettle`:

```kotlin
    /** Rebuilds the activity the way a configuration change does. */
    protected fun recreateActivity() {
        scenario?.recreate()
        composeTestRule.waitForIdle()
    }
```

- [ ] **Step 2: Write the test**

```kotlin
package com.speedevand.inkride.lifecycle

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.testing.support.textOf
import com.speedevand.inkride.core.testing.support.waitUntilTagText
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags
import com.speedevand.inkride.tracking.RideTrackingE2ETestBase
import org.junit.Test

/**
 * `RideTracker` is a process-scoped singleton and the ride lives there, not in
 * the activity (see the tracking architecture notes). Recreating the activity
 * must therefore leave the ride running and re-attach the UI to it.
 */
class ProcessDeathE2ETest : RideTrackingE2ETestBase() {
    @Test
    fun anActiveRideSurvivesActivityRecreationAndKeepsAccumulating() {
        startRideAndSettle()
        feedMovingSteps(count = 8, speedKmh = 20.0)
        val distanceBefore = composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE)

        recreateActivity()

        // The rebuilt UI shows the ride that is still running, not a reset one.
        composeTestRule.onNodeWithTag(DashboardTestTags.METRIC_DISTANCE).assertIsDisplayed()
        assertThat(composeTestRule.textOf(DashboardTestTags.METRIC_DISTANCE)).isEqualTo(distanceBefore)

        feedMovingSteps(count = 6, speedKmh = 20.0)

        composeTestRule.waitUntilTagText(DashboardTestTags.METRIC_DISTANCE) { it != distanceBefore }
    }
}
```

- [ ] **Step 2b: Write the onboarding recreation test**

`OnboardingViewModel` persists its page index in a `SavedStateHandle`, so this is the test that proves that wiring works.

```kotlin
package com.speedevand.inkride.lifecycle

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import com.speedevand.inkride.MainActivity
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.onboarding.presentation.OnboardingTestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

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
```

The page title is the page's identity here: `OnboardingViewModel` restores its page index from a `SavedStateHandle`, so a rebuilt activity that lands back on page one would show the first title again and fail this assertion.

Add the imports `androidx.compose.ui.test.onAllNodesWithTag`, `assertk.assertThat`, `assertk.assertions.isEqualTo`, `com.speedevand.inkride.core.testing.support.textOf` and `com.speedevand.inkride.core.testing.support.waitUntilTagText`. If the walkthrough's second step is a permission-priming page without a `PAGE_TITLE` node, advance with `PERMISSION_SKIP_BUTTON` until a titled page is showing before capturing `titleBeforeRecreate`.

- [ ] **Step 3: Run both classes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "*ProcessDeathE2ETest" --tests "*OnboardingProcessDeathE2ETest"`
Expected: PASS, 2 tests.

- [ ] **Step 4: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add app/src/androidTest
git commit -m "test: add process death E2E tests for active ride and onboarding"
```

---

### Task 5: Full-suite verification

**Files:**
- Modify: `CLAUDE.md` (the Testing Requirements section)

**Interfaces:**
- Consumes: everything from plans 1–5.
- Produces: nothing.

---

- [ ] **Step 1: Run every unit test**

Run: `./gradlew testDebugUnitTest`
Expected: PASS across all modules.

- [ ] **Step 2: Run every instrumented test**

Run (emulator running): `./gradlew connectedDebugAndroidTest --parallel`
Expected: PASS across `:app`, `:core:database`, and the four `:feature:*:presentation` modules — roughly 108 tests in total (13 pre-existing plus about 95 new).

- [ ] **Step 3: Run the instrumented suite a second time**

Run: `./gradlew connectedDebugAndroidTest --parallel`
Expected: PASS again, with the same counts. A test that passes once and fails on a rerun has an order dependency or leaks state — fix it now rather than letting CI find it.

- [ ] **Step 4: Verify style**

Run: `./gradlew ktlintCheck && ./gradlew lintDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Record where instrumented tests live**

In `CLAUDE.md`, under "Testing Requirements", replace the instrumented-tests bullet with:

```markdown
- **Instrumented tests** — UI/Compose screens, DAOs, and other Android-framework-dependent code (run via `connectedDebugAndroidTest` on a device/emulator). Screen-level tests live in each `:feature:*:presentation` module's `androidTest`, rendering the `*Root` composable over fakes bound through `KoinTestRule`; `:app/androidTest` holds only flows that cross module boundaries (ride tracking, navigation, ride → history, process death). Shared fakes, data builders, `KoinTestRule` and Compose helpers live in `:core:testing` — add a new fake there rather than in a module, unless the interface it fakes is declared inside a feature module.
```

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: record instrumented test layout in CLAUDE.md"
```

---

## Done when

- `./gradlew connectedDebugAndroidTest` is green across all six modules, twice in a row.
- `./gradlew testDebugUnitTest`, `./gradlew ktlintCheck` and `./gradlew lintDebug` are green.
- Every `DestinationSearchAction` is reached by at least one instrumented test.
- The bottom nav, the ride → history boundary, and activity recreation each have a test.
- `CLAUDE.md` tells the next contributor where to add an instrumented test.
