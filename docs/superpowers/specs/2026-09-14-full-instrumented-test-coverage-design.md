# Full instrumented test coverage

Date: 2026-09-14

## Goal

Cover every application feature and user path with instrumented tests, so that
the `connectedDebugAndroidTest` suite — not just the JVM unit suite — proves the
app works. Today instrumented coverage stops at ride tracking and onboarding;
history, settings, bike profiles, BLE pairing, destination-search error paths,
DAO behaviour on real SQLite, cross-feature navigation, and process death have
none.

This is additive. Existing JVM unit tests keep covering business logic
(`RideTracker`, metric math, mappers, ViewModel state transitions); the
instrumented suite proves the wiring those tests stub out — real Compose
recomposition, real Koin graph, real SQLite, real activity recreation.

## Current state

Instrumented tests today live only in `app/src/androidTest/`:

- `tracking/` — 10 classes on `RideTrackingE2ETestBase`, covering the full ride
  lifecycle (happy path, auto-pause, manual pause + multi-ride, laps, goals and
  alerts, GPS quality, BLE sensor loss, foreground-service lifecycle, route
  search, route following).
- `onboarding/OnboardingE2ETest` — 3 paths.

Uncovered by any instrumented test:

| Area | Screens / units |
|---|---|
| `:feature:history:presentation` | `RideHistoryScreen`, `RideDetailScreen`, `LifetimeStatsScreen` |
| `:feature:settings:presentation` | `SettingsScreen` (3 tabs), `BikeProfilesScreen` |
| `:feature:ble:presentation` | `BleSensorsScreen` |
| `:feature:dashboard:presentation` | `DestinationSearchScreen` error paths |
| `:core:database` | all 5 DAOs, schema migrations (only Robolectric today) |
| `:app` | bottom-nav navigation, back stack, ride → history boundary, process death |

## Decisions

Four decisions were settled before design, and the rest of this document
follows from them:

1. **Hybrid placement.** Screen-level paths live in each feature module's own
   `androidTest`; `:app` keeps only flows that genuinely cross module
   boundaries. Module tests are faster and can reach error states that a
   full-stack E2E cannot force.
2. **Test at the `*Root` level** with fake repositories injected through Koin —
   not at the `*Screen` level with hand-built state. A Root test exercises the
   real path (click → Action → ViewModel → repository → State → UI) and the
   one-shot Event path (navigation callbacks, error surfaces), which a Screen
   test cannot.
3. **Everything in scope**: DAO tests on device, rotation and process death,
   GPX export through `ACTION_SEND`, and the osmdroid route-map sheet.
4. **Shared infrastructure as a new `:core:testing` module**, rather than
   Gradle test fixtures or per-module duplication.

## Selector convention

Follow what the dashboard already established: a `*TestTags` object in
production code applied to leaf nodes that carry live data or handle
interaction, and `R.string` resolution through the app context for localized
text (generalizing today's `DashboardStrings.kt`). Never assert against a
hardcoded English literal — it silently drifts from `strings.xml`.

New tag objects: `HistoryTestTags`, `RideDetailTestTags`, `LifetimeStatsTestTags`,
`SettingsTestTags`, `BikeProfilesTestTags`, `BleSensorsTestTags`,
`DestinationSearchTestTags`.

## Part 1 — `:core:testing`

A new Android library module (`inkride.android.library` + `inkride.compose`,
namespace `com.speedevand.inkride.core.testing`), consumed only through
`androidTestImplementation` / `testImplementation`, so nothing in it reaches a
production APK.

### Contents

`fakes/` — one fake per `:core:domain` interface, each backed by a
`MutableStateFlow` with a `var nextError: DataError.Local?` knob so tests can
force the failure path that raises a `ShowError` event:

- `FakeUserSettingsRepository`, `FakeRideHistoryRepository`,
  `FakeRideLapRepository`, `FakeRideTrackPointRepository`,
  `FakeLifetimeStatsRepository`, `FakeBikeProfileRepository`
- `FakeBleScanner`, `FakeBleSensorDataSource`, `FakeRideSensorDataSource`
- `FakePlaceSearchService`, `FakeRoutingService`, `FakeCurrentLocationProvider`

`support/` — `stringRes(@StringRes id)`, the wait helpers currently in
`app/…/tracking/support/ComposeTestHelpers.kt` (moved here), and sample data
builders: `TestRides`, `TestLaps`, `TestTrackPoints`, `TestBikeProfiles`,
`TestBleDevices`.

`rules/KoinTestRule` — a JUnit4 `TestRule` that calls `startKoin { androidContext(…); modules(…) }`
in `starting()` and `stopKoin()` in `finished()`. Each test supplies its own
module list: the module's real `*PresentationModule` plus a module binding the
fakes.

### Deliberately excluded

`GpxExporter` and `GpxRouteLoader` are declared in feature modules
(`:feature:history:presentation`, `:feature:dashboard:presentation`), not in
`:core:domain`. Their fakes stay in those modules' own `androidTest` —
`:core:testing` must not depend upward on a feature module.

### Build changes

1. `settings.gradle.kts` — `include(":core:testing")`.
2. `AndroidFeatureConventionPlugin` — add shared `androidTest` dependencies
   (`compose-ui-test-junit4`, `androidx-junit`, `androidx-test-core`,
   `androidx-test-rules`, `assertk`, `kotlinx-coroutines-test`, `koin-test`)
   plus `debugImplementation(compose-ui-test-manifest)`, which a library module
   needs for `createComposeRule()` to find a `ComponentActivity` in the merged
   test manifest. Declaring this once in the convention plugin avoids five
   copies of the same `dependencies` block.
3. `gradle/libs.versions.toml` — add `koin-test` and `espresso-intents`.
4. `:core:database/build.gradle.kts` — add `androidTest` dependencies
   (`room-testing`, `androidx-test-*`); this module uses
   `inkride.android.library`, so the feature convention plugin does not reach it.

### Consolidating existing fakes

`FakeUserSettingsRepository` exists in two copies in JVM unit tests
(`:feature:settings:presentation`, `:feature:onboarding:presentation`), and
`:app/androidTest` has its own sensor fakes. All move to `:core:testing` so
there is one definition per interface. The existing test suites must stay green
through this move — it is a refactor, not a rewrite.

## Part 2 — Test inventory

Roughly 95 cases across 16 new classes.

### `:feature:history:presentation` (~30 cases)

**`RideHistoryScreenTest`** — list renders records from the repository; empty
state; loading is not empty state; row click navigates with the right id; row
delete removes it and shows the undo snackbar; undo restores the ride together
with its laps and track points; letting the snackbar expire makes the delete
permanent; "delete all" opens a confirmation dialog, cancel is a no-op, confirm
empties the list; a repository error raises `ShowError` and leaves the list
unchanged; the stats icon navigates to lifetime stats; imperial units render
mi/mph; rotation.

**`RideDetailScreenTest`** — all 11 metrics render from the repository; lap list
renders, and is absent when the ride has no laps; the elevation chart renders
its min/max labels, and is absent with no points; delete → dialog → confirm
emits `NavigateBack` and removes the record; cancel keeps it; back navigates;
imperial units render ft/mi/mph; a non-existent id degrades without crashing;
rotation.

**`RideDetailGpxExportTest`** (Espresso-Intents) — export fires `ACTION_SEND`
with a FileProvider URI; `GpxExportError.NO_TRACK` shows an error and fires no
intent; `FAILED` shows an error.

**`LifetimeStatsScreenTest`** — totals render from the repository; zeroes on
empty history; imperial units; back navigates.

### `:feature:settings:presentation` (~27 cases)

**`SettingsScreenTest`** — default tab, and switching between PROFILE / BIKE /
DISPLAY swaps the content; weight and age persist, a non-numeric value does
not; language selection; bike weight and bike type persist; navigation to bike
profiles and to BLE sensors; unit switch persists; each of the 10 metric
visibility switches persists (parameterized); keep-screen-on persists; the max-
speed alert (toggle plus value, and that the value is ignored while the toggle
is off); HR min/max alerts; a save error raises `ShowError`; back navigates.

No rotation case: `selectedTab` and the field values all live in
`SettingsState`, i.e. in the ViewModel. See "Where a rotation test is worth
writing" in Part 3.

**`BikeProfilesScreenTest`** — the list renders with the active profile marked;
empty state; adding a profile; an empty name sets `draftNameError` and keeps the
form open; an invalid weight sets `draftWeightError`; cancel discards; editing
an existing profile prefills and updates; deleting removes the row; **deleting
the active profile clears `activeBikeProfileId` in settings**; changing the
active profile moves the marker; imperial units render lbs; a repository error
raises `ShowError`; back navigates.

No rotation case, for the same reason: the draft lives in
`BikeProfilesState`.

### `:feature:ble:presentation` (~8 cases)

**`BleSensorsScreenTest`** — nothing paired shows both slots empty; an HRM scan
enters the scanning state and surfaces discovered devices; stopping the scan
freezes the list and clears the state; selecting a device persists its address
and ends the scan; a cadence scan is independent of HRM; "forget" on HRM leaves
cadence untouched; a scanner error raises `ShowError`; back navigates. No
rotation case — `BleSensorsState` is ViewModel-held.

### `:feature:dashboard:presentation` (~7 cases)

**`DestinationSearchScreenTest`** — the existing `RideTrackingRouteSearchTest`
covers only the happy path, so this class takes the error paths: a query below
the minimum length never calls the service; `NO_RESULTS`; `NETWORK_FAILED`;
routing `NO_ROUTE_FOUND`; routing `NETWORK_FAILED`; `PERMISSION_DENIED` and
`TIMED_OUT` from the location provider; back navigates. No rotation case —
`DestinationSearchState` is ViewModel-held.

### `:core:database` (~18 cases)

In-memory Room on a real device: `RideHistoryDao` (insert/observe, `getById`,
delete, `deleteAll`, lifetime-stats aggregation, empty database); `RideLapDao`
and `RideTrackPointDao` (bulk insert, read back per ride, **cascade delete**,
ordering); `BikeProfileDao` (upsert as insert and as update, observe, delete);
`UserSettingsDao` (singleton upsert, observe emits after a write); and a
`MigrationTest` built on `MigrationTestHelper`.

`exportSchema` was only switched on at version 6, so `core/database/schemas/`
holds just `6.json` and `7.json`. `MigrationTestHelper` needs a snapshot of the
*starting* version, so the instrumented migration test can validate 6 → 7 only.
Migrations 4 → 5 and 5 → 6 stay in the existing Robolectric `MigrationTest`,
which hand-builds the starting schema instead.

The existing Robolectric tests in `src/test` stay — they are faster and run in
CI without an emulator. The instrumented versions add what Robolectric
approximates: real SQLite, foreign-key cascades, and migrations.

### `:app` (~7 cases)

**`AppNavigationE2ETest`** — the bottom bar switches Ride / History / Settings;
`restoreState` preserves a tab's state on return; navigating deeper (Settings →
Bike profiles) hides the bottom bar and back restores it; system back from the
dashboard does not return to onboarding.

**`RideToHistoryE2ETest`** — a recorded ride appears in history, its detail
shows the same metrics, and deleting it removes it from the list. The only test
that crosses the real tracking → Room → history boundary.

**`ProcessDeathE2ETest`** — an active ride survives `recreate()`; onboarding
resumes on the same page.

### Out of scope, deliberately

`:core:presentation` and `:core:design-system` have no screens of their own;
`UiText` and the error mappers already have unit tests. The `:feature:*:data`
modules are covered by JVM tests using `ktor-client-mock` and Robolectric — the
only thing an emulator would add is real GPS/BLE hardware, which an emulator
does not have.

## Part 3 — Determinism and CI

**No network.** `PlaceSearchService`, `RoutingService` and
`CurrentLocationProvider` are always faked, so no test reaches Nominatim or
OSRM; the CI emulator has no guaranteed internet. The osmdroid map is the only
component that would fetch tiles, so `RideDetailRouteMapTest` asserts only that
the bottom sheet opened and a `MapView` is in the tree — never that a tile
painted.

**No `Thread.sleep`.** The existing E2E tests must sleep because they simulate
GPS samples in real time. Module tests drive `MutableStateFlow`-backed fakes,
so state is immediate: use `waitUntil` / `waitForIdle`. That is safe because
the app has no continuous animations by design (E-Ink constraint #1), which is
what makes `waitForIdle` hang elsewhere. One exception: the undo snackbar uses
`SnackbarDurationMMD.Long`, so that test takes `mainClock.autoAdvance = false`
and advances the clock manually instead of racing the auto-dismiss.

**Koin isolation.** `KoinTestRule` calls `stopKoin()` in `finished()`, so each
test class in a module starts from a clean container. No Android Test
Orchestrator: per-test process isolation would roughly double runtime to solve
a problem `KoinTestRule` already solves.

**Permissions.** `BleSensorsRoot` requests `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`
from a `LaunchedEffect` on API 31+. Without a `GrantPermissionRule` the system
dialog would cover the screen and hang the test, so that rule goes into
`BleSensorsScreenTest`.

**Where a rotation test is worth writing.** Only where state lives in the
composition. Every MVI screen here keeps its state in a ViewModel, which
survives rotation by construction, so asserting on it would be an empty test.
The exception is state held in a `remember` inside a `*Screen` — that is
precisely what rotation loses. It narrows module-level rotation coverage to two
places: the delete-confirmation dialog in `RideHistoryScreen`, and the delete
dialog plus route-map sheet in `RideDetailScreen`.

Those use `StateRestorationTester` from `compose-ui-test`, which recreates the
composition without restarting an activity. `:app` uses
`ActivityScenario.recreate()` instead, because the paths it covers — the
`SavedStateHandle` page index in onboarding, and an active ride — only happen
on full activity recreation.

**Production fix found while designing.** Three dialog/sheet visibility flags
use plain `remember` rather than `rememberSaveable`, so rotating with the
delete-confirmation dialog open silently closes it:

- `RideHistoryScreen.kt:116` — `showConfirmDeleteAll`
- `RideDetailScreen.kt:110` — `showConfirmDelete`
- `RideDetailScreen.kt:111` — `showRouteMap`

These change to `rememberSaveable`. Writing a rotation test against the current
behaviour would cement a small bug instead of fixing it.

`DashboardScreen.kt:153,162,180` (`showGoalSheet`, `showClearRouteConfirm`,
`showRouteSourceSheet`) and `LapGoalControls.kt:97,98` (the goal draft) have the
same shape. They are noted here but left alone: the dashboard is the one area
with existing instrumented coverage, and changing it is a separate decision.

**CI time.** `connectedDebugAndroidTest` currently builds and installs one test
APK; afterwards there will be six (`:app`, four feature modules,
`:core:database`). The module tests themselves are fast, but APK install and
Gradle overhead grow. Raise `timeout-minutes` on the instrumented job from 30
to 60 and add `--parallel`. The AVD snapshot is already cached, so emulator
boot is not part of this cost.

## Part 4 — Order of work

1. **Foundation** — `:core:testing` with fakes, `KoinTestRule` and builders;
   changes to `AndroidFeatureConventionPlugin`, the version catalog and
   `settings.gradle.kts`; migrate the duplicated fakes onto the new module.
   Nothing else compiles without this. Verify: existing unit and instrumented
   tests still green.
2. **`:core:database`** — DAO and migration tests. Least coupled to anything
   else, and a good check that the infrastructure works on the emulator.
3. **`:feature:settings:presentation`** — two screens, the most paths, and the
   place where the `*TestTags` pattern for the other modules gets established.
4. **`:feature:history:presentation`** — including GPX export via
   Espresso-Intents and the map sheet.
5. **`:feature:ble:presentation`** and **`:feature:dashboard:presentation`**
   (destination search) — independent of each other.
6. **`:app`** — the three cross-module flow classes.
7. **CI** — raise the timeout, add `--parallel`, verify a full run.

Each step ends with `./gradlew ktlintFormat && ./gradlew ktlintCheck` and the
module's own test task before moving on. Tests are written TDD-first where it
applies: a test that fails against a missing `TestTag`, then the tag in
production code, then green.

## Success criteria

- Every screen listed in "Current state" has an instrumented test class.
- Every `*Action` in every MVI contract is reached by at least one instrumented
  test, and every `*Event` is observed at least once.
- Every DAO method has an instrumented test.
- `./gradlew connectedDebugAndroidTest` is green across all six modules, and
  green on a second consecutive run (no order dependence, no flakes).
- `./gradlew ktlintCheck` and `./gradlew testDebugUnitTest` stay green.
