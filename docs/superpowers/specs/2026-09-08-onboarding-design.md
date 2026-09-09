# Onboarding design

## Context

InkRide has no first-run onboarding today. Runtime permissions (location,
notifications) are requested ad-hoc and eagerly the first time
`DashboardScreen` composes, with no explanation shown before the system
dialog appears — the exact anti-pattern the [Android onboarding
guidelines](https://developer.android.com/design/ui/mobile/guides/patterns/onboarding#onboarding-walkthrough)
warn against. The app has no accounts or sign-in (privacy-first, local-only
storage), so the "signed-out state" onboarding pattern from that doc doesn't
apply — this design follows the **walkthrough** and **permission priming**
patterns only.

Goal: a first-run flow that (1) briefly explains what the app is and why it's
different, and (2) primes the user before requesting the permissions the app
actually needs on every ride, so the system dialogs don't appear out of
nowhere.

Explicitly out of scope for this design: an initial setup wizard (bike type /
units / weight) — that stays discoverable in Settings as it is today. A
"replay onboarding" entry point is also out of scope for v1.

## Flow

Single screen (`OnboardingRoute`) with internal step state — not a
multi-destination nav graph, since no step needs to be independently
deep-linkable or back-stack-addressable.

1. **Value-prop pages (3, skippable as a group)**
   - E-Ink optimized display — sunlight-readable, low power, discrete updates.
   - Privacy-first, no cloud — no accounts, no telemetry, no Firebase/GMS, all
     data stays on-device.
   - Ride tracking & metrics — GPS speed/distance/elevation/calories, BLE
     heart-rate & cadence sensor support, ride history.
   - A "Skip" action jumps straight to step 2.
2. **Location permission priming (not skippable as a step, but grantable-or-not)**
   - Rationale screen explaining GPS is used for ride tracking and never
     leaves the device.
   - Always shown, regardless of platform version.
3. **Notification permission priming (not skippable as a step, but grantable-or-not)**
   - Rationale screen explaining the foreground tracking-service notification.
   - Only included when `Build.VERSION.SDK_INT >= 33` (`POST_NOTIFICATIONS`
     doesn't exist before API 33) — on older devices this step doesn't
     appear at all, matching the existing conditional in
     `DashboardScreen`.
4. **Completion**
   - Persist `hasCompletedOnboarding = true`.
   - Navigate to the dashboard, popping onboarding off the back stack so it's
     unreachable via back-press afterward.

Permission steps never hard-block progress: each has a "Skip for now" action.
`DashboardScreen`'s existing permission-check-on-compose logic
(`feature/dashboard/presentation/.../DashboardScreen.kt:75-95`) already
re-prompts if a required permission is still missing on next visit, so
onboarding can safely let the user move on without granting.

## UI

- Pages: `HorizontalPager` (Compose Foundation). MMD has no pager or
  page-indicator component, so this is a deliberate custom-UI exception —
  the same kind of exception already recorded for the custom scrollbar (see
  project memory: MMD scrollbar exception). Content per page is `TextMMD`
  (title + body) plus a simple monochrome icon/illustration.
- Step indicator: a custom dot row, since MMD has no equivalent. Updates in a
  single discrete state change per page — no fade/slide — consistent with the
  E-Ink "discrete state updates" rule already applied to the compass.
- Programmatic page transitions (tapping "Next", not a manual swipe) use
  `snap()` as the `pagerState.animateScrollToPage` animation spec, per the
  no-fluid-animation E-Ink rule. Manual swipes are finger-driven, not an
  animation, so they're unaffected.
- Buttons: `ButtonMMD` for primary actions ("Next", "Continue", "Allow",
  "Get Started"), `OutlinedButtonMMD` for secondary ones ("Skip", "Skip for
  now").
- Permission priming button is adaptive:
  - Not yet requested → "Continue" → triggers the system permission dialog.
  - Denied, can re-ask → "Continue" → triggers the system dialog again.
  - Permanently denied (`shouldShowRequestPermissionRationale` returns
    `false` after a prior denial) → label becomes "Open Settings" → launches
    `ACTION_APPLICATION_DETAILS_SETTINGS` for the app's package.

## Data model

`UserSettings` / `UserSettingsEntity` gain one new field:

```kotlin
val hasCompletedOnboarding: Boolean = false
```

Same style as the existing `keepScreenOn` flag. `RoomUserSettingsRepository`'s
no-row default branch (used when the settings table is empty, i.e. genuinely
first launch) also returns `false`. This is a normal Room schema bump
(`:core:database` schema directory gets a new version); no data migration
logic needed beyond the default.

## Module & navigation wiring

New module: **`:feature:onboarding:presentation`**, created via the
`inkride.android.feature` convention plugin (pulls in `:core:domain`,
`:core:presentation`, `:core:design-system`). Presentation-only — no `:data`
submodule, since the flow reads/writes through the existing
`UserSettingsRepository` rather than introducing a second persistence
mechanism for one boolean.

- `OnboardingRoute` added to
  `core/domain/.../navigation/NavigationRoutes.kt`.
- `onboardingGraph(navController)` extension wired into `AppNavigation`'s
  `NavHost`, following the same `dashboardGraph`/`historyGraph`/
  `settingsGraph`/`bleGraph` pattern.
- `AppNavigation` takes a dynamic `startDestination` instead of the current
  hardcoded `DashboardGraph`. The app waits for the first
  `UserSettingsRepository.observeSettings()` emission
  (`collectAsStateWithLifecycle(initialValue = null)`, the same pattern
  `MainActivity` already uses for `languageCode`) before composing the
  `NavHost` at all, then picks `OnboardingRoute` or `DashboardGraph` based on
  `hasCompletedOnboarding`. Because this is a single-row Room table, the
  resulting blank frame before that first emission is negligible — accepted
  as a deliberate tradeoff rather than adding a splash-screen API for it.
- Because `OnboardingRoute` isn't in `AppNavigation`'s bottom-nav `items`
  list, the existing `showBottomBar` check already hides the bottom nav for
  it with no further change (same as `BleSensorsRoute` today).
- On completion, navigate to `DashboardGraph` with
  `popUpTo(OnboardingRoute) { inclusive = true }`.

## MVI contract

New `OnboardingContract` file, following the existing
`SettingsContract`/`BikeProfilesContract` split-file pattern:

```kotlin
data class OnboardingState(
    val pageIndex: Int = 0,
    val pages: List<OnboardingStep>, // ValueProp x3 + LocationPermission + (NotificationPermission if API >= 33)
    val permissionButtonLabel: PermissionButtonLabel, // Continue | OpenSettings, derived from live permission status
)

sealed interface OnboardingAction {
    data object NextClicked : OnboardingAction
    data object SkipClicked : OnboardingAction
    data object PermissionButtonClicked : OnboardingAction
    data class PermissionResultReceived(val granted: Boolean) : OnboardingAction
}

sealed interface OnboardingEvent {
    data class RequestPermission(val permission: String) : OnboardingEvent
    data object OpenAppSettings : OnboardingEvent
    data object NavigateToDashboard : OnboardingEvent
}
```

`OnboardingViewModel` depends only on `UserSettingsRepository`. On the final
step's completion it writes `hasCompletedOnboarding = true` and fires
`NavigateToDashboard`. `pageIndex` is restored via `SavedStateHandle` (the
standard pattern from the `android-presentation-mvi` skill) so a
process-death-and-restore mid-flow resumes on the same page rather than
restarting the walkthrough.

Screen split, matching every other feature:
- `OnboardingRoot` — wires the VM, holds the
  `rememberLauncherForActivityResult(RequestPermission)` launcher, calls
  `onAction(PermissionResultReceived(...))` from its callback, observes
  `OnboardingEvent`s (via `ObserveAsEvents`) to trigger the launcher / open-
  settings intent / navigation.
- `OnboardingScreen` — pure UI: pager, dot indicator, buttons.

DI: new `onboardingPresentationModule` (Koin), same shape as
`settingsPresentationModule`, wired into `InkRideApp.onCreate()`.

## Edge cases

- Cold-start blank frame before the first `UserSettings` emission — accepted
  tradeoff, see "Module & navigation wiring" above.
- Process death mid-onboarding — `pageIndex` restored via `SavedStateHandle`.
- User denies a permission permanently during onboarding, then later grants
  it manually via system Settings — no new handling needed;
  `DashboardScreen`'s existing permission-check-on-compose logic already
  picks this up on next dashboard visit.
- Locale: all new copy goes through `strings.xml` in both `values/` and
  `values-pl/`, the two locales the app currently supports.

## Testing

Both required per `CLAUDE.md` and the `android-testing` skill:

- **Unit tests** — `OnboardingViewModelTest`: page advancement, skip
  behavior, permission-result handling (granted / denied /
  permanently-denied → correct next state/event), `hasCompletedOnboarding`
  write and `NavigateToDashboard` firing on completion. Uses
  `UnconfinedTestDispatcher` + a fake `UserSettingsRepository`, matching
  existing ViewModel test conventions.
- **Instrumented tests** — Compose UI test for the full walkthrough (page
  swipe/skip navigation, permission-button label changes), plus a
  cross-feature test verifying the dynamic start destination actually routes
  to `OnboardingRoute` vs `DashboardGraph` based on the persisted flag,
  mirroring the existing `RideTrackingE2ETestBase` style.

No existing tests are affected — this is a new module, new coverage only.
