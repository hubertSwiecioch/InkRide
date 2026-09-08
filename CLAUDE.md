# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew testDebugUnitTest      # Run all unit tests
./gradlew lintDebug              # Run lint on debug variant
./gradlew :app:installDebug      # Build and install on connected device
./gradlew ktlintCheck            # Check Kotlin code style (must pass on CI for every PR)
./gradlew ktlintFormat           # Auto-fix Kotlin code style violations
```

Always run `./gradlew ktlintCheck` after writing or editing Kotlin code (including test/androidTest sources), and run `./gradlew ktlintFormat` to auto-fix any violations before considering the work done.

**Single module / single test:**
```bash
# Pure-Kotlin modules (inkride.kotlin.library, e.g. :core:domain) use the `test` task:
./gradlew :core:domain:test                                                 # All tests in one module
./gradlew :core:domain:test --tests "com.speedevand.inkride.core.domain.ResultTest"  # Single test class

# Android modules (inkride.android.library/feature) use the variant-aware task:
./gradlew :core:database:testDebugUnitTest                                  # All tests in one module
./gradlew :core:database:testDebugUnitTest --tests "com.speedevand.inkride.core.database.SomeTest"  # Single test class
```

## Architecture

This is an **E-Ink bicycle computer** app following **multi-module Clean Architecture** with MVI presentation. There are 13 Gradle modules across 3 groups (`:app`, `:core:*`, `:feature:*`):

- **`:app`** — entry point, `InkRideApp` (Koin `startKoin`), `AppNavigation` (bottom nav + NavHost wiring)
- **`:core:*`** — shared cross-cutting code
  - `:core:domain` — `Result<D,E>` sealed interface, `DataError`, `SensorError`, navigation route objects
  - `:core:database` — Room `AppDatabase`, DAOs, entities
  - `:core:presentation` — `UiText`, `ObserveAsEvents`, error-to-UiText mappers, scrollbar
  - `:core:design-system` — `InkRideTheme`, `Color`, `Typography`, `DesignConstants` (wraps MMD `ThemeMMD`)
- **`:feature:*`** — feature modules, split into `:domain` / `:data` / `:presentation` submodules (not every feature has all three)
  - `:feature:dashboard` (`:presentation`) — real-time speed/compass/metrics display
  - `:feature:history` (`:data`, `:presentation`) — ride history list and detail views
  - `:feature:settings` (`:data`, `:presentation`) — user preferences (bike type, units, visible metrics)
  - `:feature:tracking` (`:data`) — foreground `TrackingService`, GPS via `LocationManager`. Ride-metrics logic (`RideMetricsCalculator`, `CaloriesEstimator`, `PowerEstimator`) lives in `:core:domain` (`com.speedevand.inkride.core.domain.tracking`) since it is shared by both `:feature:tracking` and `:feature:dashboard`
  - `:feature:ble` (`:data`, `:presentation`) — BLE heart-rate/cadence sensor pairing and connection state

### MVI Presentation Pattern

Every screen follows a strict contract:

- **State** — `data class` with immutable screen state (e.g., `DashboardState`)
- **Action** — `sealed interface` for user/system intents (e.g., `DashboardAction`)
- **Event** — `sealed interface` for one-shot side effects (e.g., `DashboardEvent`)

ViewModel exposes `state: StateFlow<State>` and `events: Flow<Event>` (via `Channel`), consumed with `ObserveAsEvents`. Composables use `collectAsStateWithLifecycle()` and receive `onAction: (Action) -> Unit` callbacks. Each screen is split into a `*Root` composable (wires VM + lifecycle) and a `*Screen` composable (pure UI).

### Data Layer: Result + Repository

All data operations return `Result<T, DataError>` or `Result<T, SensorError>`. Repositories are defined as interfaces in `:domain` and implemented in `:data` using Room. Flows are used for observable data. Mappers from DAO entities to domain models live as extension functions in the repository implementation files.

### Dependency Injection (Koin)

Each module defines its own Koin module (e.g., `databaseModule`, `trackingDataModule`, `dashboardPresentationModule`). ViewModels are registered with `viewModelOf(::MyViewModel)` and injected in composables via `koinViewModel()` (or `koinViewModel { parametersOf(id) }` for parameterized VMs). All modules are wired in `InkRideApp.onCreate()`.

### Navigation

Type-safe Compose Navigation routes (`@Serializable` objects/data classes) defined in `:core:domain` under `com.speedevand.inkride.core.domain.navigation`. Each feature exposes a `NavGraphBuilder` extension function (e.g., `dashboardGraph`). Transitions are `None` for E-Ink compatibility.

## Key Design Constraints

1. **E-Ink first**: no fluid animations, discrete state updates (compass in 2° steps), high-contrast monochrome palette, `snap()` animation specs, no overscroll
2. **Mudita Mindful Design (MMD)**: use MMD components (`TextMMD`, `ButtonMMD`, `NavigationBarMMD`, `SwitchMMD`, etc.) before writing custom UI. Theme wraps `ThemeMMD`.
3. **De-googled**: no Firebase, no GMS, no Google Analytics. Location via standard `android.location.LocationManager` with `GPS_PROVIDER`.
4. **Privacy-first**: no cloud dependencies, no telemetry, all data stored locally via Room.

## Testing Requirements

Every application feature must be covered by both **unit tests** and **instrumented tests**:
- **Unit tests** — ViewModels, use cases, mappers, and other business logic (JVM, run via `test`/`testDebugUnitTest`)
- **Instrumented tests** — UI/Compose screens, DAOs, and other Android-framework-dependent code (run via `connectedDebugAndroidTest` on a device/emulator)

No new functionality should be merged without both test types where applicable. See `android-testing` skill for patterns.

## Convention Plugins

Custom Gradle plugins in `build-logic/convention/` (5 total):

| Plugin ID | Applies |
|-----------|---------|
| `inkride.kotlin.library` | `kotlin.jvm`, JVM target 11 |
| `inkride.android.library` | `com.android.library`, compileSdk 36, minSdk 26 |
| `inkride.compose` | `kotlin.plugin.compose`, Compose BOM `2026.05.01` |
| `inkride.android.feature` | Android lib + Compose + Koin + default deps on `:core:domain`, `:core:presentation`, `:core:design-system` |
| `inkride.room` | Room + KSP, schema directory config |

Dependency versions are centralized in `gradle/libs.versions.toml`.

## Project-Specific Skills

Agent skills live in `.claude/skills/` and `.agents/skills/` (8 domains):
`android-compose-ui`, `android-data-layer`, `android-di-koin`, `android-error-handling`, `android-module-structure`, `android-navigation`, `android-presentation-mvi`, `android-testing`

## Context Files

Always read before starting work:
- `project_overview.md` — product constraints, E-Ink guidelines, MMD mandate, de-googled requirements
- `AGENTS.md` — shared AI agent instructions and skill mapping
- `README.md` — public project documentation and design philosophy
