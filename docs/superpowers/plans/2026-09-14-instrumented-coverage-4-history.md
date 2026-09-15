# Instrumented Coverage, Plan 4: History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cover every path through `RideHistoryScreen`, `RideDetailScreen` and `LifetimeStatsScreen`, including undo-delete, GPX export through `ACTION_SEND`, the route-map sheet, and the dialog state that rotation currently loses.

**Architecture:** Screens render through their `*Root` composables against fakes bound in a `KoinTestRule`, as in Plan 3. Two extras are specific to this module: the GPX export test runs under Espresso-Intents so the system chooser is stubbed instead of launched, and the rotation tests use `StateRestorationTester`, which is the only place in the app where composition-held state actually survives a rotation — after this plan's first task makes it survive.

**Tech Stack:** Compose `ui-test-junit4` (incl. `StateRestorationTester`), Espresso-Intents, Koin 4.2.1, JUnit4 + `AndroidJUnit4`, assertk, osmdroid (asserted structurally only).

**Spec:** `docs/superpowers/specs/2026-09-14-full-instrumented-test-coverage-design.md`

**Depends on:** Plan 1 in full — `KoinTestRule`, `FakeRideHistoryRepository`, `FakeRideLapRepository`, `FakeRideTrackPointRepository`, `FakeLifetimeStatsRepository`, `FakeUserSettingsRepository`, `TestRides`, `TestSettings`, `stringRes`, and the shared `androidTest` dependency set on `AndroidFeatureConventionPlugin`.

## Global Constraints

- Kotlin JVM target 11; `compileSdk = 36`, `minSdk = 26`.
- `./gradlew ktlintCheck` must pass; run `./gradlew ktlintFormat` before every commit, including `androidTest` sources.
- Use MMD components; do not introduce raw Material components while adding tags.
- Test tags go on leaf interactive or data-carrying nodes; localized text is resolved with `stringRes(R.string.…)`.
- No network. osmdroid fetches map tiles, so map assertions are structural only — that the sheet opened and a `MapView` exists. Never assert that a tile rendered.
- Rule order: `IntentsRule` (where used) before `KoinTestRule` before the Compose rule.
- `RideDetailEvent.ShowError` and `RideHistoryEvent.ShowError` surface as a `Toast`, which Compose's semantics tree cannot see. Assert the observable consequence instead (no navigation, no intent, list unchanged) — never the toast text.
- `GpxExporter` is declared in `:feature:history:presentation`, so its fake lives in that module's `androidTest`, not in `:core:testing`.

---

### Task 1: Make dialog and sheet state survive rotation, and tag `RideHistoryScreen`

The spec records this as a defect found while designing: three visibility flags use plain `remember`, so rotating with the delete-confirmation dialog open silently closes it. Fixing it is a prerequisite for the rotation tests later in this plan.

**Files:**
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideHistoryScreen.kt:116`
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailScreen.kt:110-111`
- Create: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/HistoryTestTags.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object HistoryTestTags` with `const val LIST`, `EMPTY_STATE`, `DELETE_ALL_BUTTON`, `LIFETIME_STATS_BUTTON`, `CONFIRM_DELETE_ALL_DIALOG`, `CONFIRM_DELETE_ALL_ACCEPT`, `CONFIRM_DELETE_ALL_CANCEL`, and `fun row(id: Long): String`, `fun rowDelete(id: Long): String`, `fun rowDate(id: Long): String`, `fun rowDistance(id: Long): String`, `fun rowSpeed(id: Long): String`.

---

- [ ] **Step 1: Switch the three flags to `rememberSaveable`**

In `RideHistoryScreen.kt`:

```kotlin
    var showConfirmDeleteAll by rememberSaveable { mutableStateOf(false) }
```

In `RideDetailScreen.kt`:

```kotlin
    var showConfirmDelete by rememberSaveable { mutableStateOf(value = false) }
    var showRouteMap by rememberSaveable { mutableStateOf(value = false) }
```

Add `import androidx.compose.runtime.saveable.rememberSaveable` to both files. `Boolean` is natively saveable, so no custom `Saver` is needed.

Leave `DashboardScreen.kt:153,162,180` and `LapGoalControls.kt:97,98` alone — the spec scopes the fix to these three.

- [ ] **Step 2: Write the tag object**

Create `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/HistoryTestTags.kt`:

```kotlin
package com.speedevand.inkride.history.presentation

/**
 * Stable identifiers for the ride-history list. Rows are keyed by ride id
 * because every row renders the same delete icon and the same value layout;
 * a text selector could not tell two rides apart.
 */
object HistoryTestTags {
    const val LIST = "history_list"
    const val EMPTY_STATE = "history_empty"
    const val DELETE_ALL_BUTTON = "history_delete_all"
    const val LIFETIME_STATS_BUTTON = "history_lifetime_stats"
    const val CONFIRM_DELETE_ALL_DIALOG = "history_confirm_delete_all"
    const val CONFIRM_DELETE_ALL_ACCEPT = "history_confirm_delete_all_accept"
    const val CONFIRM_DELETE_ALL_CANCEL = "history_confirm_delete_all_cancel"

    fun row(id: Long): String = "history_row_$id"

    fun rowDelete(id: Long): String = "history_row_delete_$id"

    fun rowDate(id: Long): String = "history_row_date_$id"

    fun rowDistance(id: Long): String = "history_row_distance_$id"

    fun rowSpeed(id: Long): String = "history_row_speed_$id"
}
```

- [ ] **Step 3: Apply the tags**

In `RideHistoryScreen.kt`, add `import androidx.compose.ui.platform.testTag` and tag: the `LazyColumn` (`LIST`); the empty-state `TextMMD` (`EMPTY_STATE`); the lifetime-stats `IconButton` (`LIFETIME_STATS_BUTTON`); the delete-all `IconButton` (`DELETE_ALL_BUTTON`); inside the confirmation dialog, its container (`CONFIRM_DELETE_ALL_DIALOG`), its cancel button (`CONFIRM_DELETE_ALL_CANCEL`) and its confirm button (`CONFIRM_DELETE_ALL_ACCEPT`).

In the row composable, thread the ride id through and tag: the clickable row (`row(ride.id)`), the per-row delete `IconButton` (`rowDelete(ride.id)`), and the date, distance and average-speed `TextMMD`s (`rowDate`, `rowDistance`, `rowSpeed`). The row composable currently takes `onClick`/`onDelete` lambdas; add a `ride: RideRecordUi` parameter if it does not already have one, so the id is in scope.

- [ ] **Step 4: Verify the module builds and its unit tests pass**

```bash
./gradlew :feature:history:presentation:assembleDebug :feature:history:presentation:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 5: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/history/presentation/src/main
git commit -m "fix: keep history dialog and route-sheet state across rotation, add test tags"
```

---

### Task 2: `RideHistoryScreen` tests

**Files:**
- Create: `feature/history/presentation/src/androidTest/kotlin/com/speedevand/inkride/history/presentation/FakeGpxExporter.kt`
- Create: `feature/history/presentation/src/androidTest/kotlin/com/speedevand/inkride/history/presentation/HistoryTestHarness.kt`
- Create: `feature/history/presentation/src/androidTest/kotlin/com/speedevand/inkride/history/presentation/RideHistoryScreenTest.kt`

**Interfaces:**
- Consumes: `KoinTestRule`, the history fakes, `TestRides`, `TestSettings`, `stringRes`, `textOf` (Plan 1); `HistoryTestTags` (Task 1).
- Produces:
  - `class FakeGpxExporter : GpxExporter` with `var result: Result<Uri, GpxExportError>`, `val exportedIds: List<Long>`
  - `abstract class HistoryTestHarness` exposing `settingsRepository`, `historyRepository`, `lapRepository`, `trackPointRepository`, `lifetimeStatsRepository`, `gpxExporter`, `koinRule`, `composeTestRule`.

---

- [ ] **Step 1: Write the module-local GPX exporter fake**

```kotlin
package com.speedevand.inkride.history.presentation

import android.net.Uri
import com.speedevand.inkride.core.domain.Result

/**
 * Lives here rather than in `:core:testing` because [GpxExporter] is declared in
 * this module, and `:core:testing` must not depend on a feature module.
 */
class FakeGpxExporter : GpxExporter {
    private val _exportedIds = mutableListOf<Long>()
    val exportedIds: List<Long> get() = _exportedIds

    var result: Result<Uri, GpxExportError> =
        Result.Success(Uri.parse("content://com.speedevand.inkride.fileprovider/exports/ride-1.gpx"))

    override suspend fun export(rideId: Long): Result<Uri, GpxExportError> {
        _exportedIds += rideId
        return result
    }
}
```

- [ ] **Step 2: Write the harness**

```kotlin
package com.speedevand.inkride.history.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.speedevand.inkride.core.domain.history.LifetimeStatsRepository
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.fakes.FakeLifetimeStatsRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideHistoryRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideLapRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideTrackPointRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import com.speedevand.inkride.core.testing.rules.KoinTestRule
import com.speedevand.inkride.core.testing.support.TestSettings
import org.junit.Rule
import org.koin.dsl.module

/**
 * Shared setup for the history instrumented tests: the real
 * `historyPresentationModule` with fakes bound over its five collaborators.
 * The real module also binds a `GpxExporter` built from `androidContext()`, so
 * the fake must be registered after it to win.
 */
abstract class HistoryTestHarness {
    protected open fun initialSettings(): UserSettings = TestSettings.default()

    protected open fun initialRides(): List<RideRecord> = emptyList()

    val settingsRepository by lazy { FakeUserSettingsRepository(initialSettings()) }
    val lapRepository by lazy { FakeRideLapRepository() }
    val trackPointRepository by lazy { FakeRideTrackPointRepository() }

    // The lap and track-point fakes are handed to the history fake so that
    // deleting a ride clears them too, reproducing the `onDelete = CASCADE` that
    // RideLapEntity and RideTrackPointEntity declare. Without this wiring the
    // undo test below passes even when the undo path restores nothing — the laps
    // it asserts on were never removed in the first place.
    val historyRepository by lazy {
        FakeRideHistoryRepository(
            initial = initialRides(),
            lapRepository = lapRepository,
            trackPointRepository = trackPointRepository,
        )
    }
    val lifetimeStatsRepository by lazy { FakeLifetimeStatsRepository() }
    val gpxExporter by lazy { FakeGpxExporter() }

    @get:Rule(order = 1)
    val koinRule by lazy {
        KoinTestRule(
            modules =
                listOf(
                    historyPresentationModule,
                    module {
                        single<UserSettingsRepository> { settingsRepository }
                        single<RideHistoryRepository> { historyRepository }
                        single<RideLapRepository> { lapRepository }
                        single<RideTrackPointRepository> { trackPointRepository }
                        single<LifetimeStatsRepository> { lifetimeStatsRepository }
                        single<GpxExporter> { gpxExporter }
                    },
                ),
        )
    }

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()
}

```

Rule orders start at 1 so the GPX export test can slot `IntentsRule` in at order 0.

- [ ] **Step 3: Write the list tests**

```kotlin
package com.speedevand.inkride.history.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.testing.support.TestRides
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.stringRes
import com.speedevand.inkride.core.testing.support.textOf
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideHistoryScreenTest : HistoryTestHarness() {
    override fun initialRides(): List<RideRecord> =
        listOf(
            TestRides.record(id = 1L, startTimestamp = TestRides.START_MS + 86_400_000L, distanceKm = 25.0),
            TestRides.record(id = 2L, startTimestamp = TestRides.START_MS, distanceKm = 10.0),
        )

    private fun setContent(
        onRideClick: (Long) -> Unit = {},
        onLifetimeStats: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            RideHistoryRoot(
                onNavigateToDetail = onRideClick,
                onNavigateToLifetimeStats = onLifetimeStats,
            )
        }
    }

    @Test
    fun ridesRenderNewestFirstWithTheirDistanceAndSpeed() {
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.row(1L)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HistoryTestTags.row(2L)).assertIsDisplayed()
        assertThat(composeTestRule.textOf(HistoryTestTags.rowDistance(1L))).isEqualTo("25.00 km")
        assertThat(composeTestRule.textOf(HistoryTestTags.rowSpeed(1L))).isEqualTo("30.0 km/h")
    }

    @Test
    fun anEmptyHistoryShowsTheEmptyState() {
        historyRepository.emitRides(emptyList())
        setContent()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(HistoryTestTags.EMPTY_STATE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(HistoryTestTags.EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun clickingARideNavigatesWithItsId() {
        var clickedId: Long? = null
        setContent(onRideClick = { clickedId = it })
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.row(2L)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { clickedId != null }
        assertThat(clickedId).isEqualTo(2L)
    }

    @Test
    fun deletingARideRemovesItFromTheList() {
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.rowDelete(2L)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(HistoryTestTags.row(2L)).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithTag(HistoryTestTags.row(1L)).assertIsDisplayed()
    }

    @Test
    fun undoingADeleteRestoresTheRideWithItsLapsAndTrackPoints() {
        lapRepository.setLaps(rideId = 2L, laps = TestRides.laps(count = 3))
        trackPointRepository.setPoints(rideId = 2L, points = TestRides.trackPoints(count = 5))
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.rowDelete(2L)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithText(stringRes(R.string.history_undo))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithText(stringRes(R.string.history_undo)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { historyRepository.observeAll().first() }.size == 2
        }

        val restoredId =
            runBlocking { historyRepository.observeAll().first() }
                .first { it.id != 1L }
                .id
        assertThat(runBlocking { lapRepository.getLaps(restoredId) })
            .isEqualTo(Result.Success(TestRides.laps(count = 3)))
        assertThat(runBlocking { trackPointRepository.getPoints(restoredId) })
            .isEqualTo(Result.Success(TestRides.trackPoints(count = 5)))
    }

    @Test
    fun deleteAllAsksForConfirmationAndCancellingChangesNothing() {
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.DELETE_ALL_BUTTON).performClick()
        composeTestRule.onNodeWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_DIALOG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_CANCEL).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_DIALOG).assertCountEquals(0)
        composeTestRule.onNodeWithTag(HistoryTestTags.row(1L)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HistoryTestTags.row(2L)).assertIsDisplayed()
    }

    @Test
    fun confirmingDeleteAllEmptiesTheList() {
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.DELETE_ALL_BUTTON).performClick()
        composeTestRule.onNodeWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_ACCEPT).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(HistoryTestTags.EMPTY_STATE).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aFailedDeleteLeavesTheListUnchanged() {
        historyRepository.deleteResult = Result.Error(DataError.Local.UNKNOWN)
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.rowDelete(2L)).performClick()
        composeTestRule.waitForIdle()

        // The error surfaces as a Toast, which is outside the semantics tree;
        // the observable consequence is that the row is still there.
        composeTestRule.onNodeWithTag(HistoryTestTags.row(2L)).assertIsDisplayed()
    }

    @Test
    fun theStatsIconNavigatesToLifetimeStats() {
        var opened = false
        setContent(onLifetimeStats = { opened = true })
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(HistoryTestTags.LIFETIME_STATS_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { opened }
        assertThat(opened).isTrue()
    }

    @Test
    fun imperialUnitsRenderMilesAndMilesPerHour() {
        settingsRepository.emitSettings(TestSettings.default(units = MeasurementUnits.IMPERIAL))
        setContent()
        composeTestRule.waitForIdle()

        assertThat(composeTestRule.textOf(HistoryTestTags.rowDistance(1L)).endsWith("mi")).isTrue()
        assertThat(composeTestRule.textOf(HistoryTestTags.rowSpeed(1L)).endsWith("mph")).isTrue()
    }
}
```

This test is only meaningful because `HistoryTestHarness` wires the lap and track-point fakes into the history fake, so the delete actually clears them. Verify that wiring is in place before trusting a green result here.

The undo assertion reads the restored ride's id back from the repository rather than assuming it: the ViewModel re-saves the ride it buffered, and whether the original row id is reused is an implementation detail of the save path.

Add the imports `androidx.compose.ui.test.onAllNodesWithText`, `androidx.compose.ui.test.onNodeWithText`, `kotlinx.coroutines.flow.first` and `kotlinx.coroutines.runBlocking`. `Result.Success` is a data class, so comparing against `Result.Success(TestRides.laps(3))` compares by value.

Confirm `RideHistoryRoot`'s parameter names against `RideHistoryScreen.kt`, and the undo action's string resource id against `strings.xml`, before running.

- [ ] **Step 4: Run the class**

Run: `./gradlew :feature:history:presentation:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.speedevand.inkride.history.presentation.RideHistoryScreenTest`
Expected: PASS, 10 tests.

If `undoingADeleteRestoresTheRide…` is flaky, the undo snackbar is auto-dismissing before the click. Take manual control of the clock in that test: set `composeTestRule.mainClock.autoAdvance = false` before the delete click, drive the composition with `composeTestRule.mainClock.advanceTimeBy(…)` until the snackbar appears, click undo, then restore `autoAdvance = true`.

- [ ] **Step 5: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/history/presentation/src/androidTest
git commit -m "test: add instrumented ride history list tests"
```

---

### Task 3: `RideDetailScreen` tags and tests

**Files:**
- Create: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailTestTags.kt`
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/RideDetailScreen.kt`
- Create: `feature/history/presentation/src/androidTest/kotlin/com/speedevand/inkride/history/presentation/RideDetailScreenTest.kt`

**Interfaces:**
- Consumes: `HistoryTestHarness` (Task 2).
- Produces: `object RideDetailTestTags` with `const val BACK_BUTTON`, `EXPORT_BUTTON`, `DELETE_BUTTON`, `CONFIRM_DELETE_DIALOG`, `CONFIRM_DELETE_ACCEPT`, `CONFIRM_DELETE_CANCEL`, `NOT_FOUND`, `LAPS_SECTION`, `ELEVATION_CHART`, `ELEVATION_MAX_LABEL`, `ELEVATION_MIN_LABEL`, `SHOW_ROUTE_BUTTON`, `ROUTE_SHEET`, `ROUTE_MAP`, and `fun detail(key: String): String`, `fun lapRow(lapNumber: Int): String`, plus detail key constants `DISTANCE`, `MOVING_TIME`, `ELAPSED_TIME`, `AVG_SPEED`, `MAX_SPEED`, `ELEVATION_GAIN`, `CALORIES`, `AVG_POWER`, `START`, `END`.

---

- [ ] **Step 1: Write the tag object**

```kotlin
package com.speedevand.inkride.history.presentation

/**
 * Stable identifiers for the ride-detail screen. Metric rows all render through
 * the same `DetailRow` composable, so each call site supplies a key.
 */
object RideDetailTestTags {
    const val BACK_BUTTON = "ride_detail_back"
    const val EXPORT_BUTTON = "ride_detail_export"
    const val DELETE_BUTTON = "ride_detail_delete"
    const val CONFIRM_DELETE_DIALOG = "ride_detail_confirm_delete"
    const val CONFIRM_DELETE_ACCEPT = "ride_detail_confirm_delete_accept"
    const val CONFIRM_DELETE_CANCEL = "ride_detail_confirm_delete_cancel"
    const val NOT_FOUND = "ride_detail_not_found"
    const val LAPS_SECTION = "ride_detail_laps"
    const val ELEVATION_CHART = "ride_detail_elevation_chart"
    const val ELEVATION_MAX_LABEL = "ride_detail_elevation_max"
    const val ELEVATION_MIN_LABEL = "ride_detail_elevation_min"
    const val SHOW_ROUTE_BUTTON = "ride_detail_show_route"
    const val ROUTE_SHEET = "ride_detail_route_sheet"
    const val ROUTE_MAP = "ride_detail_route_map"

    const val START = "start"
    const val END = "end"
    const val DISTANCE = "distance"
    const val MOVING_TIME = "moving_time"
    const val ELAPSED_TIME = "elapsed_time"
    const val AVG_SPEED = "avg_speed"
    const val MAX_SPEED = "max_speed"
    const val ELEVATION_GAIN = "elevation_gain"
    const val CALORIES = "calories"
    const val AVG_POWER = "avg_power"

    fun detail(key: String): String = "ride_detail_value_$key"

    fun lapRow(lapNumber: Int): String = "ride_detail_lap_$lapNumber"
}
```

- [ ] **Step 2: Apply the tags**

Add a `key: String` parameter to the private `DetailRow` composable and tag its value `TextMMD` with `RideDetailTestTags.detail(key)`. Pass the ten key constants at the ten call sites (lines 255–273 today: start, end, moving time, session time, distance, avg speed, max speed, elevation gain, calories, avg power).

Tag as well: the back, export and delete `IconButton`s; the confirmation dialog container and its two buttons; the "not found" `TextMMD`; the laps section container and each `LapRow` (`lapRow(lap.lapNumber.toInt())` — `RideLapUi.lapNumber` is a `String`, so convert with `toInt()` at the call site, or key the tag off the string directly and adjust the tag helper's parameter type); the `ElevationChart` and its max/min labels; the "show route" button; the `ModalBottomSheetMMD` container and the `RideRouteMap`.

- [ ] **Step 3: Write the tests**

```kotlin
package com.speedevand.inkride.history.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.testing.support.TestRides
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.textOf
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideDetailScreenTest : HistoryTestHarness() {
    private companion object {
        const val RIDE_ID = 1L
    }

    override fun initialRides(): List<RideRecord> = listOf(TestRides.record(id = RIDE_ID))

    private fun setContent(
        rideId: Long = RIDE_ID,
        onBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            RideDetailRoot(rideId = rideId, onNavigateBack = onBack)
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun everyMetricRendersFromTheStoredRide() {
        setContent()

        assertThat(composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.DISTANCE)))
            .isEqualTo("25.00 km")
        assertThat(composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.AVG_SPEED)))
            .isEqualTo("30.0 km/h")
        assertThat(composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.MAX_SPEED)))
            .isEqualTo("45.5 km/h")
        assertThat(composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.ELEVATION_GAIN)))
            .isEqualTo("320 m")
        assertThat(composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.CALORIES)))
            .isEqualTo("780 kcal")
        assertThat(composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.AVG_POWER)))
            .isEqualTo("165 W")
        assertThat(composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.MOVING_TIME)))
            .isEqualTo("00:50:00")
        assertThat(composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.ELAPSED_TIME)))
            .isEqualTo("01:00:00")
    }

    @Test
    fun lapsRenderWhenTheRideHasThem() {
        lapRepository.setLaps(RIDE_ID, TestRides.laps(count = 3))
        setContent()

        composeTestRule.onNodeWithTag(RideDetailTestTags.LAPS_SECTION).assertIsDisplayed()
        composeTestRule.onNodeWithTag(RideDetailTestTags.lapRow(1)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(RideDetailTestTags.lapRow(3)).assertIsDisplayed()
    }

    @Test
    fun theLapsSectionIsAbsentWhenTheRideHasNoLaps() {
        setContent()

        composeTestRule.onAllNodesWithTag(RideDetailTestTags.LAPS_SECTION).assertCountEquals(0)
    }

    @Test
    fun theElevationChartRendersItsExtremeLabels() {
        trackPointRepository.setPoints(RIDE_ID, TestRides.trackPoints(count = 30))
        setContent()

        composeTestRule.onNodeWithTag(RideDetailTestTags.ELEVATION_CHART).assertIsDisplayed()
        composeTestRule.onNodeWithTag(RideDetailTestTags.ELEVATION_MAX_LABEL).assertIsDisplayed()
        composeTestRule.onNodeWithTag(RideDetailTestTags.ELEVATION_MIN_LABEL).assertIsDisplayed()
    }

    @Test
    fun theElevationChartIsAbsentWithoutTrackPoints() {
        setContent()

        composeTestRule.onAllNodesWithTag(RideDetailTestTags.ELEVATION_CHART).assertCountEquals(0)
    }

    @Test
    fun cancellingTheDeleteDialogKeepsTheRide() {
        var navigatedBack = false
        setContent(onBack = { navigatedBack = true })

        composeTestRule.onNodeWithTag(RideDetailTestTags.DELETE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_DIALOG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_CANCEL).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(RideDetailTestTags.CONFIRM_DELETE_DIALOG).assertCountEquals(0)
        assertThat(navigatedBack).isEqualTo(false)
    }

    @Test
    fun confirmingTheDeleteRemovesTheRideAndNavigatesBack() {
        var navigatedBack = false
        setContent(onBack = { navigatedBack = true })

        composeTestRule.onNodeWithTag(RideDetailTestTags.DELETE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_ACCEPT).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { navigatedBack }
        assertThat(navigatedBack).isTrue()
    }

    @Test
    fun backNavigatesWithoutDeletingAnything() {
        var navigatedBack = false
        setContent(onBack = { navigatedBack = true })

        composeTestRule.onNodeWithTag(RideDetailTestTags.BACK_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { navigatedBack }
        assertThat(navigatedBack).isTrue()
    }

    @Test
    fun imperialUnitsRenderMilesFeetAndMilesPerHour() {
        settingsRepository.emitSettings(TestSettings.default(units = MeasurementUnits.IMPERIAL))
        setContent()

        assertThat(
            composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.DISTANCE)).endsWith("mi"),
        ).isTrue()
        assertThat(
            composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.MAX_SPEED)).endsWith("mph"),
        ).isTrue()
        assertThat(
            composeTestRule.textOf(RideDetailTestTags.detail(RideDetailTestTags.ELEVATION_GAIN)).endsWith("ft"),
        ).isTrue()
    }

    @Test
    fun anUnknownRideIdShowsTheNotFoundStateWithoutCrashing() {
        setContent(rideId = 999L)

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(RideDetailTestTags.NOT_FOUND).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(RideDetailTestTags.NOT_FOUND).assertIsDisplayed()
    }
}
```

`TestRides.record` fixes `movingTimeSeconds = 3_000` and `elapsedTimeSeconds = 3_600`; check `Long.toClockString()` in `:core:presentation` and correct the two expected clock strings if its format differs from `HH:MM:SS`.

- [ ] **Step 4: Run the class**

Run: `./gradlew :feature:history:presentation:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.speedevand.inkride.history.presentation.RideDetailScreenTest`
Expected: PASS, 10 tests.

- [ ] **Step 5: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/history/presentation
git commit -m "test: add instrumented ride detail tests"
```

---

### Task 4: GPX export through `ACTION_SEND`

**Files:**
- Modify: `feature/history/presentation/build.gradle.kts`
- Create: `feature/history/presentation/src/androidTest/kotlin/com/speedevand/inkride/history/presentation/RideDetailGpxExportTest.kt`

**Interfaces:**
- Consumes: `HistoryTestHarness`, `FakeGpxExporter` (Task 2); `RideDetailTestTags` (Task 3).
- Produces: nothing other tasks depend on.

---

- [ ] **Step 1: Add Espresso-Intents**

In `feature/history/presentation/build.gradle.kts`:

```kotlin
    androidTestImplementation(libs.androidx.espresso.intents)
```

`espresso-intents` was added to the version catalog in Plan 1 Task 1 Step 1.

- [ ] **Step 2: Write the export tests**

`IntentsRule` stubs every outgoing intent, so the system chooser never opens — without it the chooser would cover the screen and the next test in the class would fail.

```kotlin
package com.speedevand.inkride.history.presentation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import androidx.test.espresso.intent.rule.IntentsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.testing.support.TestRides
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.not
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideDetailGpxExportTest : HistoryTestHarness() {
    private companion object {
        const val RIDE_ID = 1L
    }

    override fun initialRides(): List<RideRecord> = listOf(TestRides.record(id = RIDE_ID))

    @get:Rule(order = 0)
    val intentsRule = IntentsRule()

    private fun setContent() {
        composeTestRule.setContent {
            RideDetailRoot(rideId = RIDE_ID, onNavigateBack = {})
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun exportingFiresAShareIntentCarryingTheGpxUri() {
        // Swallow the chooser so nothing actually launches.
        Intents.intending(not(isInternal())).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, null),
        )
        setContent()

        composeTestRule.onNodeWithTag(RideDetailTestTags.EXPORT_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                Intents.intended(hasAction(Intent.ACTION_CHOOSER))
            }.isSuccess
        }
        Intents.intended(hasAction(Intent.ACTION_CHOOSER))
        assertThat(gpxExporter.exportedIds).contains(RIDE_ID)
    }

    @Test
    fun aRideWithNoTrackFiresNoIntent() {
        gpxExporter.result = Result.Error(GpxExportError.NO_TRACK)
        setContent()

        composeTestRule.onNodeWithTag(RideDetailTestTags.EXPORT_BUTTON).performClick()
        composeTestRule.waitForIdle()

        // The failure surfaces as a Toast; the observable consequence is that
        // nothing was launched.
        Intents.assertNoUnverifiedIntents()
    }

    @Test
    fun aFailedExportFiresNoIntent() {
        gpxExporter.result = Result.Error(GpxExportError.FAILED)
        setContent()

        composeTestRule.onNodeWithTag(RideDetailTestTags.EXPORT_BUTTON).performClick()
        composeTestRule.waitForIdle()

        Intents.assertNoUnverifiedIntents()
    }
}
```

`RideDetailRoot` wraps the share intent in `Intent.createChooser`, so the intent that leaves the app is `ACTION_CHOOSER` with the `ACTION_SEND` intent as its `EXTRA_INTENT`. If asserting on the inner intent is wanted, match with `hasExtra(Intent.EXTRA_INTENT, allOf(hasAction(Intent.ACTION_SEND), hasType("application/gpx+xml")))`; keep the outer assertion either way, since that is what is actually dispatched.

Remove the `allOf`, `hasType` and `isEmpty` imports if the final assertions do not use them — ktlint fails on unused imports.

- [ ] **Step 3: Run the class**

Run: `./gradlew :feature:history:presentation:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.speedevand.inkride.history.presentation.RideDetailGpxExportTest`
Expected: PASS, 3 tests.

- [ ] **Step 4: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/history/presentation
git commit -m "test: add instrumented GPX export intent tests"
```

---

### Task 5: Route map sheet and rotation

**Files:**
- Create: `feature/history/presentation/src/androidTest/kotlin/com/speedevand/inkride/history/presentation/RideDetailRouteMapTest.kt`
- Create: `feature/history/presentation/src/androidTest/kotlin/com/speedevand/inkride/history/presentation/HistoryRotationTest.kt`

**Interfaces:**
- Consumes: `HistoryTestHarness` (Task 2), `RideDetailTestTags` (Task 3), `HistoryTestTags` (Task 1), and the `rememberSaveable` fix from Task 1.
- Produces: nothing other tasks depend on.

---

- [ ] **Step 1: Write the route map test**

```kotlin
package com.speedevand.inkride.history.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.testing.support.TestRides
import org.junit.Test
import org.junit.runner.RunWith

/**
 * osmdroid fetches map tiles over the network, which the CI emulator has no
 * guaranteed access to, so these assertions are structural only: the sheet
 * opened and a MapView is in the tree. Never assert that a tile rendered.
 */
@RunWith(AndroidJUnit4::class)
class RideDetailRouteMapTest : HistoryTestHarness() {
    private companion object {
        const val RIDE_ID = 1L
    }

    override fun initialRides(): List<RideRecord> = listOf(TestRides.record(id = RIDE_ID))

    private fun setContent() {
        trackPointRepository.setPoints(RIDE_ID, TestRides.trackPoints(count = 20))
        composeTestRule.setContent {
            RideDetailRoot(rideId = RIDE_ID, onNavigateBack = {})
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun theShowRouteButtonIsAbsentWhenThereIsNoTrack() {
        composeTestRule.setContent {
            RideDetailRoot(rideId = RIDE_ID, onNavigateBack = {})
        }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(RideDetailTestTags.SHOW_ROUTE_BUTTON).assertCountEquals(0)
    }

    @Test
    fun showingTheRouteOpensASheetContainingTheMap() {
        setContent()

        composeTestRule.onNodeWithTag(RideDetailTestTags.SHOW_ROUTE_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule.onAllNodesWithTag(RideDetailTestTags.ROUTE_MAP).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(RideDetailTestTags.ROUTE_SHEET).assertIsDisplayed()
        composeTestRule.onNodeWithTag(RideDetailTestTags.ROUTE_MAP).assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Write the rotation tests**

`StateRestorationTester` recreates the composition the way a configuration change does, without restarting the activity — which is what makes it the right tool for `rememberSaveable` state. These tests are the regression guard for Task 1's fix.

```kotlin
package com.speedevand.inkride.history.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.testing.support.TestRides
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryRotationTest : HistoryTestHarness() {
    private companion object {
        const val RIDE_ID = 1L
    }

    override fun initialRides(): List<RideRecord> = listOf(TestRides.record(id = RIDE_ID))

    @Test
    fun theDeleteAllDialogSurvivesARecomposition() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            RideHistoryRoot(
                onNavigateToDetail = {},
                onNavigateToLifetimeStats = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HistoryTestTags.DELETE_ALL_BUTTON).performClick()
        composeTestRule.onNodeWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_DIALOG).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithTag(HistoryTestTags.CONFIRM_DELETE_ALL_DIALOG).assertIsDisplayed()
    }

    @Test
    fun theRideDeleteDialogSurvivesARecomposition() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            RideDetailRoot(rideId = RIDE_ID, onNavigateBack = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RideDetailTestTags.DELETE_BUTTON).performClick()
        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_DIALOG).assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithTag(RideDetailTestTags.CONFIRM_DELETE_DIALOG).assertIsDisplayed()
    }

    @Test
    fun theRouteSheetSurvivesARecomposition() {
        trackPointRepository.setPoints(RIDE_ID, TestRides.trackPoints(count = 20))
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            RideDetailRoot(rideId = RIDE_ID, onNavigateBack = {})
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RideDetailTestTags.SHOW_ROUTE_BUTTON).performClick()
        composeTestRule.waitUntil(timeoutMillis = 10_000L) {
            composeTestRule
                .onAllNodesWithTag(RideDetailTestTags.ROUTE_SHEET)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithTag(RideDetailTestTags.ROUTE_SHEET).assertIsDisplayed()
    }
}
```

Add the import `androidx.compose.ui.test.onAllNodesWithTag`.

To confirm these tests actually guard the fix, temporarily revert one `rememberSaveable` back to `remember`, run the matching test, see it fail, then restore the fix.

- [ ] **Step 3: Run both classes**

Run: `./gradlew :feature:history:presentation:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.speedevand.inkride.history.presentation.RideDetailRouteMapTest,com.speedevand.inkride.history.presentation.HistoryRotationTest`
Expected: PASS, 5 tests.

- [ ] **Step 4: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/history/presentation/src/androidTest
git commit -m "test: add instrumented route map and rotation tests"
```

---

### Task 6: `LifetimeStatsScreen` tests

**Files:**
- Create: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/LifetimeStatsTestTags.kt`
- Modify: `feature/history/presentation/src/main/java/com/speedevand/inkride/history/presentation/LifetimeStatsScreen.kt`
- Create: `feature/history/presentation/src/androidTest/kotlin/com/speedevand/inkride/history/presentation/LifetimeStatsScreenTest.kt`

**Interfaces:**
- Consumes: `HistoryTestHarness` (Task 2), `FakeLifetimeStatsRepository` (Plan 1).
- Produces: `object LifetimeStatsTestTags` with `const val BACK_BUTTON`, `TOTAL_RIDES`, `TOTAL_DISTANCE`, `TOTAL_MOVING_TIME`, `TOTAL_ELEVATION`, `MAX_SPEED`, `TOTAL_CALORIES`.

---

- [ ] **Step 1: Write the tag object and apply it**

```kotlin
package com.speedevand.inkride.history.presentation

/** Stable identifiers for the lifetime-totals screen. */
object LifetimeStatsTestTags {
    const val BACK_BUTTON = "lifetime_stats_back"
    const val TOTAL_RIDES = "lifetime_stats_total_rides"
    const val TOTAL_DISTANCE = "lifetime_stats_total_distance"
    const val TOTAL_MOVING_TIME = "lifetime_stats_total_moving_time"
    const val TOTAL_ELEVATION = "lifetime_stats_total_elevation"
    const val MAX_SPEED = "lifetime_stats_max_speed"
    const val TOTAL_CALORIES = "lifetime_stats_total_calories"
}
```

In `LifetimeStatsScreen.kt`, tag the back `IconButton` and the six value `TextMMD`s. If the values render through a shared stat-row composable, add a `key: String` parameter and tag the value node with it, as `DetailRow` does in Task 3.

- [ ] **Step 2: Write the tests**

```kotlin
package com.speedevand.inkride.history.presentation

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.history.LifetimeStats
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.textOf
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifetimeStatsScreenTest : HistoryTestHarness() {
    private val populated =
        LifetimeStats(
            totalRides = 12,
            totalDistanceKm = 340.5,
            totalMovingTimeSeconds = 45_000L,
            totalElevationGainM = 4_200.0,
            maxSpeedKmh = 61.3,
            totalCaloriesKcal = 9_800.0,
        )

    private fun setContent(onBack: () -> Unit = {}) {
        composeTestRule.setContent { LifetimeStatsRoot(onNavigateBack = onBack) }
        composeTestRule.waitForIdle()
    }

    @Test
    fun totalsRenderFromTheRepository() {
        lifetimeStatsRepository.emit(populated)
        setContent()

        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_RIDES)).isEqualTo("12")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_DISTANCE)).isEqualTo("340.5 km")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_MOVING_TIME)).isEqualTo("12h 30m")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_ELEVATION)).isEqualTo("4200 m")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.MAX_SPEED)).isEqualTo("61.3 km/h")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_CALORIES)).isEqualTo("9800 kcal")
    }

    @Test
    fun anEmptyHistoryRendersZeroes() {
        setContent()

        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_RIDES)).isEqualTo("0")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_DISTANCE)).isEqualTo("0.0 km")
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.MAX_SPEED)).isEqualTo("0.0 km/h")
    }

    @Test
    fun imperialUnitsRenderMilesFeetAndMilesPerHour() {
        settingsRepository.emitSettings(TestSettings.default(units = MeasurementUnits.IMPERIAL))
        lifetimeStatsRepository.emit(populated)
        setContent()

        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_DISTANCE).endsWith("mi")).isTrue()
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.TOTAL_ELEVATION).endsWith("ft")).isTrue()
        assertThat(composeTestRule.textOf(LifetimeStatsTestTags.MAX_SPEED).endsWith("mph")).isTrue()
    }

    @Test
    fun backInvokesItsNavigationCallback() {
        var backPressed = false
        setContent(onBack = { backPressed = true })

        composeTestRule.onNodeWithTag(LifetimeStatsTestTags.BACK_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { backPressed }
        assertThat(backPressed).isTrue()
    }
}
```

45 000 seconds is 12 h 30 m; if `toHoursMinutes()`'s format differs from `"%dh %dm"`, correct the expected string.

- [ ] **Step 3: Run the whole module**

```bash
./gradlew :feature:history:presentation:connectedDebugAndroidTest :feature:history:presentation:testDebugUnitTest
```
Expected: PASS, 32 instrumented tests plus the existing unit tests.

- [ ] **Step 4: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/history/presentation
git commit -m "test: add instrumented lifetime stats tests"
```

---

## Done when

- `./gradlew :feature:history:presentation:connectedDebugAndroidTest` is green — 32 tests.
- The module's unit tests remain green.
- Every `RideHistoryAction`, `RideDetailAction` and `LifetimeStatsAction` is reached by at least one instrumented test.
- Rotating with a delete-confirmation dialog open keeps it open, and a test fails if that regresses.
- `./gradlew ktlintCheck` is green.
