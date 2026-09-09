# Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a first-run onboarding walkthrough (3 value-prop pages + location/notification permission priming) that shows once on first launch and never again, per the Android onboarding-walkthrough + permission-priming guidelines.

**Architecture:** A new presentation-only `:feature:onboarding:presentation` module (MVI: State/Action/Event/ViewModel + Root/Screen split) drives a single-route screen with internal step state. Completion is persisted as a `hasCompletedOnboarding` flag on the existing `UserSettings`/Room row. `AppNavigation`'s start destination becomes dynamic, computed once the first `UserSettings` emission arrives.

**Tech Stack:** Kotlin, Jetpack Compose (`HorizontalPager`, `rememberLauncherForActivityResult`), Koin DI, Room (migration), MMD components (`TextMMD`, `ButtonMMD`, `OutlinedButtonMMD`), JUnit5 + Turbine + AssertK for unit tests, Compose UI test + `ActivityScenario` for instrumented tests.

**Spec:** `docs/superpowers/specs/2026-09-08-onboarding-design.md`

## Global Constraints

- ktlint must pass: run `./gradlew ktlintCheck` after every Kotlin edit; run `./gradlew ktlintFormat` first if it fails, per `CLAUDE.md`.
- Every application feature needs both unit tests (JVM) and instrumented tests (device/emulator), per `CLAUDE.md`.
- MMD components (`TextMMD`, `ButtonMMD`, `OutlinedButtonMMD`, etc.) are used wherever an equivalent exists; the pager and its dot indicator are the one deliberate custom-UI exception (MMD has no pager/indicator component), matching the precedent already recorded for the app's custom scrollbar.
- No fluid animations: programmatic pager transitions use `snap()` as the animation spec (E-Ink constraint).
- All new user-facing strings go in both `values/strings.xml` and `values-pl/strings.xml` (the app's two supported locales).
- New Koin modules are registered in `InkRideApp.onCreate()`; new Gradle modules are registered in `settings.gradle.kts`.

---

## Task 1: `hasCompletedOnboarding` flag (domain + database)

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/settings/UserSettings.kt`
- Modify: `core/database/src/main/java/com/speedevand/inkride/core/database/UserSettingsEntity.kt`
- Modify: `core/database/src/main/java/com/speedevand/inkride/core/database/AppDatabase.kt`
- Modify: `core/database/src/main/java/com/speedevand/inkride/core/database/DatabaseModule.kt`
- Modify: `feature/settings/data/src/main/java/com/speedevand/inkride/settings/data/RoomUserSettingsRepository.kt`
- Test: `core/database/src/test/java/com/speedevand/inkride/core/database/MigrationTest.kt`
- Test: `feature/settings/data/src/test/kotlin/com/speedevand/inkride/settings/data/RoomUserSettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `UserSettings.hasCompletedOnboarding: Boolean` (default `false`) — consumed by Task 3 (`OnboardingViewModel`) and Task 7 (`MainActivity`'s start-destination decision).

- [ ] **Step 1: Add the field to the domain model**

Edit `core/domain/src/main/java/com/speedevand/inkride/core/domain/settings/UserSettings.kt` — add after `activeBikeProfileId`:

```kotlin
    val activeBikeProfileId: Long? = null,
    // First-run onboarding walkthrough completion. False only for a genuinely
    // fresh install (no row yet); an upgrading user is grandfathered to true
    // by MIGRATION_6_7 so onboarding never appears retroactively.
    val hasCompletedOnboarding: Boolean = false,
)
```

- [ ] **Step 2: Add the field to the Room entity**

Edit `core/database/src/main/java/com/speedevand/inkride/core/database/UserSettingsEntity.kt` — add after `activeBikeProfileId`:

```kotlin
    val activeBikeProfileId: Long? = null,
    val hasCompletedOnboarding: Boolean = false,
)
```

- [ ] **Step 3: Write the failing migration test**

Add to `core/database/src/test/java/com/speedevand/inkride/core/database/MigrationTest.kt`, inside the `MigrationTest` class, after the `migration 5 to 6 ...` test:

```kotlin
    @Test
    fun `migration 6 to 7 adds hasCompletedOnboarding column defaulting existing rows to true`() {
        val db =
            openHelper(
                dbName = "migration_6_7_test",
                version = 6,
                createSql =
                    listOf(
                        "CREATE TABLE `user_settings` (`id` INTEGER PRIMARY KEY NOT NULL, `weightKg` INTEGER NOT NULL)",
                        "INSERT INTO `user_settings` (`id`, `weightKg`) VALUES (1, 75)",
                    ),
            ).writableDatabase

        MIGRATION_6_7.migrate(db)

        db.query("SELECT hasCompletedOnboarding FROM user_settings WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }
        db.close()
    }
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./gradlew :core:database:testDebugUnitTest --tests "com.speedevand.inkride.core.database.MigrationTest"`
Expected: FAIL — `MIGRATION_6_7` is unresolved (doesn't exist yet).

- [ ] **Step 5: Add the migration and bump the database version**

Edit `core/database/src/main/java/com/speedevand/inkride/core/database/DatabaseModule.kt` — add after `MIGRATION_5_6`:

```kotlin
/**
 * v6 → v7: adds the `hasCompletedOnboarding` flag to `user_settings`, backing
 * the first-run onboarding walkthrough. Defaults existing rows to `true` — an
 * upgrading user has already used the app and must never see onboarding
 * retroactively. Only a genuinely fresh install with no row yet falls back to
 * the Kotlin-side `UserSettings.hasCompletedOnboarding = false` default.
 */
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `user_settings` ADD COLUMN `hasCompletedOnboarding` INTEGER NOT NULL DEFAULT 1",
            )
        }
    }
```

Then change `.addMigrations(MIGRATION_4_5, MIGRATION_5_6)` to:

```kotlin
                ).addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
```

Edit `core/database/src/main/java/com/speedevand/inkride/core/database/AppDatabase.kt` — change `version = 6` to `version = 7`.

- [ ] **Step 6: Run the migration test to verify it passes**

Run: `./gradlew :core:database:testDebugUnitTest --tests "com.speedevand.inkride.core.database.MigrationTest"`
Expected: PASS (all migration tests, including the new one).

- [ ] **Step 7: Write the failing repository mapping tests**

Add to `feature/settings/data/src/test/kotlin/com/speedevand/inkride/settings/data/RoomUserSettingsRepositoryTest.kt`, after the `observeSettings maps entity to domain model` test:

```kotlin
    @Test
    fun `observeSettings maps hasCompletedOnboarding flag`() =
        runTest {
            dao.setEntity(
                UserSettingsEntity(
                    id = 1,
                    weightKg = 80,
                    age = 25,
                    bikeWeightKg = 12.0,
                    bikeType = "ROAD",
                    languageCode = "en",
                    units = "METRIC",
                    showDistance = true,
                    showMovingTime = true,
                    showAverageSpeed = true,
                    showMaxSpeed = true,
                    showElevationGain = true,
                    showCalories = true,
                    showAltitude = true,
                    showGrade = true,
                    showCompass = true,
                    showPower = true,
                    hasCompletedOnboarding = true,
                ),
            )

            val settings = repository.observeSettings().first()
            assertThat(settings.hasCompletedOnboarding).isEqualTo(true)
        }

    @Test
    fun `save persists hasCompletedOnboarding flag`() =
        runTest {
            val settings = UserSettings(weightKg = 80, age = 25, hasCompletedOnboarding = true)
            repository.save(settings)
            assertThat(dao.lastUpsert?.hasCompletedOnboarding).isEqualTo(true)
        }
```

- [ ] **Step 8: Run the tests to verify they fail**

Run: `./gradlew :feature:settings:data:test --tests "com.speedevand.inkride.settings.data.RoomUserSettingsRepositoryTest"`
Expected: FAIL — `settings.hasCompletedOnboarding` is always `false` (repository doesn't map it yet) and `dao.lastUpsert?.hasCompletedOnboarding` is `false`.

- [ ] **Step 9: Wire the field through the repository**

Edit `feature/settings/data/src/main/java/com/speedevand/inkride/settings/data/RoomUserSettingsRepository.kt` — in `observeSettings()`'s `if (entity != null)` branch, add after `activeBikeProfileId = entity.activeBikeProfileId,`:

```kotlin
                    activeBikeProfileId = entity.activeBikeProfileId,
                    hasCompletedOnboarding = entity.hasCompletedOnboarding,
                )
```

In `save()`'s `UserSettingsEntity(...)` construction, add after `activeBikeProfileId = settings.activeBikeProfileId,`:

```kotlin
                    activeBikeProfileId = settings.activeBikeProfileId,
                    hasCompletedOnboarding = settings.hasCompletedOnboarding,
                ),
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `./gradlew :feature:settings:data:test --tests "com.speedevand.inkride.settings.data.RoomUserSettingsRepositoryTest"`
Expected: PASS (all tests in the file).

- [ ] **Step 11: Regenerate the exported Room schema**

Run: `./gradlew :core:database:assembleDebug`

This generates `core/database/schemas/com.speedevand.inkride.core.database.AppDatabase/7.json` (via KSP, `exportSchema = true`). Confirm the file now exists:

Run: `ls core/database/schemas/com.speedevand.inkride.core.database.AppDatabase/`
Expected: both `6.json` and `7.json` are listed.

- [ ] **Step 12: Format and lint**

Run: `./gradlew ktlintFormat`
Run: `./gradlew ktlintCheck`
Expected: both succeed with no remaining violations.

- [ ] **Step 13: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/settings/UserSettings.kt \
        core/database/src/main/java/com/speedevand/inkride/core/database/UserSettingsEntity.kt \
        core/database/src/main/java/com/speedevand/inkride/core/database/AppDatabase.kt \
        core/database/src/main/java/com/speedevand/inkride/core/database/DatabaseModule.kt \
        core/database/src/test/java/com/speedevand/inkride/core/database/MigrationTest.kt \
        core/database/schemas/com.speedevand.inkride.core.database.AppDatabase/7.json \
        feature/settings/data/src/main/java/com/speedevand/inkride/settings/data/RoomUserSettingsRepository.kt \
        feature/settings/data/src/test/kotlin/com/speedevand/inkride/settings/data/RoomUserSettingsRepositoryTest.kt
git commit -m "$(cat <<'EOF'
Add hasCompletedOnboarding flag to UserSettings

Foundation for the onboarding walkthrough: a v6->v7 Room migration
adds the column, defaulting existing rows to true so upgrading users
are never shown onboarding retroactively.
EOF
)"
```

---

## Task 2: Scaffold `:feature:onboarding:presentation` + step model

**Files:**
- Modify: `settings.gradle.kts`
- Create: `feature/onboarding/presentation/build.gradle.kts`
- Create: `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingStep.kt`
- Test: `feature/onboarding/presentation/src/test/kotlin/com/speedevand/inkride/onboarding/presentation/OnboardingStepsTest.kt`

**Interfaces:**
- Produces: `enum class OnboardingStep` (with `icon: ImageVector`, `titleRes: Int`, `bodyRes: Int`, `permissions: List<String>`), `val OnboardingStep.isPermissionStep: Boolean`, `fun buildOnboardingSteps(sdkInt: Int = Build.VERSION.SDK_INT): List<OnboardingStep>` — consumed by Task 3 (`OnboardingContract`/`OnboardingViewModel`) and Task 5 (UI components).

- [ ] **Step 1: Register the new Gradle module**

Edit `settings.gradle.kts` — add right after `include(":feature:dashboard:presentation")`:

```kotlin
include(":feature:dashboard:presentation")
include(":feature:onboarding:presentation")
```

- [ ] **Step 2: Create the module's build file**

Create `feature/onboarding/presentation/build.gradle.kts`:

```kotlin
plugins {
    id("inkride.android.feature")
}

android {
    namespace = "com.speedevand.inkride.onboarding.presentation"
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.mudita.mmd)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.compose.ui)
}
```

- [ ] **Step 3: Write the failing step-model tests**

Create `feature/onboarding/presentation/src/test/kotlin/com/speedevand/inkride/onboarding/presentation/OnboardingStepsTest.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation

import android.os.Build
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import org.junit.jupiter.api.Test

class OnboardingStepsTest {
    @Test
    fun `steps include notification permission on API 33 and above`() {
        val steps = buildOnboardingSteps(sdkInt = Build.VERSION_CODES.TIRAMISU)
        assertThat(steps).contains(OnboardingStep.NOTIFICATION_PERMISSION)
    }

    @Test
    fun `steps exclude notification permission below API 33`() {
        val steps = buildOnboardingSteps(sdkInt = Build.VERSION_CODES.S)
        assertThat(steps).doesNotContain(OnboardingStep.NOTIFICATION_PERMISSION)
    }

    @Test
    fun `steps always start with the three value-prop pages then location permission`() {
        val steps = buildOnboardingSteps(sdkInt = Build.VERSION_CODES.S)
        assertThat(steps).containsExactly(
            OnboardingStep.VALUE_PROP_EINK,
            OnboardingStep.VALUE_PROP_PRIVACY,
            OnboardingStep.VALUE_PROP_TRACKING,
            OnboardingStep.LOCATION_PERMISSION,
        )
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./gradlew :feature:onboarding:presentation:testDebugUnitTest`
Expected: FAIL to compile — `OnboardingStep` and `buildOnboardingSteps` don't exist yet.

- [ ] **Step 5: Implement the step model**

Create `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingStep.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation

import android.Manifest
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

enum class OnboardingStep(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
    val permissions: List<String> = emptyList(),
) {
    VALUE_PROP_EINK(Icons.Filled.WbSunny, R.string.onboarding_eink_title, R.string.onboarding_eink_body),
    VALUE_PROP_PRIVACY(Icons.Filled.Lock, R.string.onboarding_privacy_title, R.string.onboarding_privacy_body),
    VALUE_PROP_TRACKING(
        Icons.Filled.DirectionsBike,
        R.string.onboarding_tracking_title,
        R.string.onboarding_tracking_body,
    ),
    LOCATION_PERMISSION(
        Icons.Filled.LocationOn,
        R.string.onboarding_location_title,
        R.string.onboarding_location_body,
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ),
    NOTIFICATION_PERMISSION(
        Icons.Filled.Notifications,
        R.string.onboarding_notification_title,
        R.string.onboarding_notification_body,
        listOf(Manifest.permission.POST_NOTIFICATIONS),
    ),
}

val OnboardingStep.isPermissionStep: Boolean get() = permissions.isNotEmpty()

/**
 * The walkthrough's pages, in order. [NOTIFICATION_PERMISSION] is included
 * only from API 33 (TIRAMISU) onward -- POST_NOTIFICATIONS doesn't exist as
 * a runtime permission before that, matching the same SDK check
 * DashboardScreen already uses for its own permission request.
 */
fun buildOnboardingSteps(sdkInt: Int = Build.VERSION.SDK_INT): List<OnboardingStep> =
    buildList {
        add(OnboardingStep.VALUE_PROP_EINK)
        add(OnboardingStep.VALUE_PROP_PRIVACY)
        add(OnboardingStep.VALUE_PROP_TRACKING)
        add(OnboardingStep.LOCATION_PERMISSION)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(OnboardingStep.NOTIFICATION_PERMISSION)
        }
    }
```

This references `R.string.onboarding_*`, which don't exist yet — that's expected; Task 4 adds them. The module won't fully compile until then, so skip straight to running the *this task's* test after Task 4 is done, or add the strings now if you want Step 6 below to pass immediately. To keep this task self-contained, add a placeholder `strings.xml` now (Task 4 will replace it with the full set):

Create `feature/onboarding/presentation/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="onboarding_eink_title">Built for E-Ink</string>
    <string name="onboarding_eink_body">Placeholder</string>
    <string name="onboarding_privacy_title">Your rides stay yours</string>
    <string name="onboarding_privacy_body">Placeholder</string>
    <string name="onboarding_tracking_title">Track every ride</string>
    <string name="onboarding_tracking_body">Placeholder</string>
    <string name="onboarding_location_title">Location access</string>
    <string name="onboarding_location_body">Placeholder</string>
    <string name="onboarding_notification_title">Ride notifications</string>
    <string name="onboarding_notification_body">Placeholder</string>
</resources>
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :feature:onboarding:presentation:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Format and lint**

Run: `./gradlew ktlintFormat`
Run: `./gradlew ktlintCheck`
Expected: both succeed.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts feature/onboarding
git commit -m "$(cat <<'EOF'
Scaffold :feature:onboarding:presentation module

Adds the onboarding step model (value-prop pages + SDK-gated
permission-priming steps) as the module's first testable unit.
EOF
)"
```

---

## Task 3: `OnboardingContract` + `OnboardingViewModel`

**Files:**
- Create: `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingContract.kt`
- Create: `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingViewModel.kt`
- Create: `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingPresentationModule.kt`
- Test: `feature/onboarding/presentation/src/test/kotlin/com/speedevand/inkride/onboarding/presentation/FakeUserSettingsRepository.kt`
- Test: `feature/onboarding/presentation/src/test/kotlin/com/speedevand/inkride/onboarding/presentation/OnboardingViewModelTest.kt`

**Interfaces:**
- Consumes: `OnboardingStep`, `isPermissionStep`, `buildOnboardingSteps()` (Task 2); `UserSettingsRepository.observeSettings(): Flow<UserSettings>` / `.save(UserSettings): EmptyResult<DataError.Local>` (`core:domain`); `UserSettings.hasCompletedOnboarding` (Task 1).
- Produces: `data class OnboardingState(val steps: List<OnboardingStep>, val pageIndex: Int)`, `sealed interface OnboardingAction { OnNextClicked, OnSkipValuePropClicked }`, `sealed interface OnboardingEvent { NavigateToDashboard }`, `class OnboardingViewModel`, `val onboardingPresentationModule` — consumed by Task 5 (`OnboardingScreen`) and Task 6 (Koin wiring in `InkRideApp`).

- [ ] **Step 1: Create the module-local fake repository**

Create `feature/onboarding/presentation/src/test/kotlin/com/speedevand/inkride/onboarding/presentation/FakeUserSettingsRepository.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeUserSettingsRepository : UserSettingsRepository {
    private val settingsFlow = MutableStateFlow(UserSettings(weightKg = 75, age = 30))
    var lastSaved: UserSettings? = null
        private set
    var saveResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    override fun observeSettings(): Flow<UserSettings> = settingsFlow

    override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> {
        lastSaved = settings
        settingsFlow.value = settings
        return saveResult
    }

    fun emitSettings(settings: UserSettings) {
        settingsFlow.value = settings
    }
}
```

- [ ] **Step 2: Write the failing ViewModel tests**

Create `feature/onboarding/presentation/src/test/kotlin/com/speedevand/inkride/onboarding/presentation/OnboardingViewModelTest.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.settings.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository = FakeUserSettingsRepository()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = OnboardingViewModel(SavedStateHandle(), repository)

    @Test
    fun `initial state starts on the first value-prop page`() {
        val state = viewModel().state.value
        assertThat(state.pageIndex).isEqualTo(0)
        assertThat(state.steps.first()).isEqualTo(OnboardingStep.VALUE_PROP_EINK)
    }

    @Test
    fun `next click advances one page at a time`() {
        val vm = viewModel()
        vm.onAction(OnboardingAction.OnNextClicked)
        assertThat(vm.state.value.pageIndex).isEqualTo(1)
    }

    @Test
    fun `skip value prop jumps straight to the first permission step`() {
        val vm = viewModel()
        vm.onAction(OnboardingAction.OnSkipValuePropClicked)
        val state = vm.state.value
        assertThat(state.steps[state.pageIndex]).isEqualTo(OnboardingStep.LOCATION_PERMISSION)
    }

    @Test
    fun `advancing past the last step marks onboarding complete and navigates to dashboard`() =
        runTest {
            val vm = viewModel()
            val lastIndex = vm.state.value.steps.lastIndex

            vm.events.test {
                repeat(lastIndex + 1) { vm.onAction(OnboardingAction.OnNextClicked) }
                val event = awaitItem()
                assertThat(event).isEqualTo(OnboardingEvent.NavigateToDashboard)
            }
            assertThat(repository.lastSaved?.hasCompletedOnboarding).isEqualTo(true)
        }

    @Test
    fun `completing onboarding preserves previously saved settings`() =
        runTest {
            repository.emitSettings(UserSettings(weightKg = 82, age = 40))
            val vm = viewModel()
            val lastIndex = vm.state.value.steps.lastIndex

            vm.events.test {
                repeat(lastIndex + 1) { vm.onAction(OnboardingAction.OnNextClicked) }
                awaitItem()
            }

            assertThat(repository.lastSaved?.weightKg).isEqualTo(82)
            assertThat(repository.lastSaved?.hasCompletedOnboarding).isEqualTo(true)
        }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :feature:onboarding:presentation:testDebugUnitTest --tests "com.speedevand.inkride.onboarding.presentation.OnboardingViewModelTest"`
Expected: FAIL to compile — `OnboardingViewModel`, `OnboardingAction`, `OnboardingEvent`, `OnboardingState` don't exist yet.

- [ ] **Step 4: Implement the contract**

Create `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingContract.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation

data class OnboardingState(
    val steps: List<OnboardingStep> = buildOnboardingSteps(),
    val pageIndex: Int = 0,
)

sealed interface OnboardingAction {
    data object OnNextClicked : OnboardingAction

    data object OnSkipValuePropClicked : OnboardingAction
}

sealed interface OnboardingEvent {
    data object NavigateToDashboard : OnboardingEvent
}
```

- [ ] **Step 5: Implement the ViewModel**

Create `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingViewModel.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val userSettingsRepository: UserSettingsRepository,
) : ViewModel() {
    private val steps = buildOnboardingSteps()

    private val _state =
        MutableStateFlow(
            OnboardingState(steps = steps, pageIndex = savedStateHandle[PAGE_INDEX_KEY] ?: 0),
        )
    val state = _state.asStateFlow()

    private val _events = Channel<OnboardingEvent>()
    val events = _events.receiveAsFlow()

    fun onAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.OnNextClicked -> advance(_state.value.pageIndex + 1)
            OnboardingAction.OnSkipValuePropClicked -> advance(steps.indexOfFirst { it.isPermissionStep })
        }
    }

    private fun advance(nextIndex: Int) {
        if (nextIndex >= steps.size) {
            completeOnboarding()
        } else {
            savedStateHandle[PAGE_INDEX_KEY] = nextIndex
            _state.update { it.copy(pageIndex = nextIndex) }
        }
    }

    // Failure here just means onboarding may show again next launch -- not
    // data loss -- so the write is fire-and-forget rather than surfaced as
    // an error to the user.
    private fun completeOnboarding() {
        viewModelScope.launch {
            val current = userSettingsRepository.observeSettings().first()
            userSettingsRepository.save(current.copy(hasCompletedOnboarding = true))
            _events.send(OnboardingEvent.NavigateToDashboard)
        }
    }

    private companion object {
        const val PAGE_INDEX_KEY = "onboarding_page_index"
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :feature:onboarding:presentation:testDebugUnitTest --tests "com.speedevand.inkride.onboarding.presentation.OnboardingViewModelTest"`
Expected: PASS (all 5 tests).

- [ ] **Step 7: Add the Koin module**

Create `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingPresentationModule.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val onboardingPresentationModule =
    module {
        viewModelOf(::OnboardingViewModel)
    }
```

- [ ] **Step 8: Run the full module test suite**

Run: `./gradlew :feature:onboarding:presentation:testDebugUnitTest`
Expected: PASS (step-model tests from Task 2 + ViewModel tests from this task).

- [ ] **Step 9: Format and lint**

Run: `./gradlew ktlintFormat`
Run: `./gradlew ktlintCheck`
Expected: both succeed.

- [ ] **Step 10: Commit**

```bash
git add feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingContract.kt \
        feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingViewModel.kt \
        feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingPresentationModule.kt \
        feature/onboarding/presentation/src/test/kotlin/com/speedevand/inkride/onboarding/presentation/FakeUserSettingsRepository.kt \
        feature/onboarding/presentation/src/test/kotlin/com/speedevand/inkride/onboarding/presentation/OnboardingViewModelTest.kt
git commit -m "$(cat <<'EOF'
Add OnboardingViewModel and MVI contract

Page-index-driven state (restored via SavedStateHandle), advancing
through value-prop and permission-priming steps and persisting
hasCompletedOnboarding on completion.
EOF
)"
```

---

## Task 4: String resources

**Files:**
- Modify: `feature/onboarding/presentation/src/main/res/values/strings.xml`
- Create: `feature/onboarding/presentation/src/main/res/values-pl/strings.xml`

**Interfaces:**
- Produces: `R.string.onboarding_*` (17 keys) — consumed by Task 5 (`OnboardingScreen` and its components).

- [ ] **Step 1: Replace the placeholder English strings**

Replace the full contents of `feature/onboarding/presentation/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="onboarding_eink_title">Built for E-Ink</string>
    <string name="onboarding_eink_body">A sunlight-readable, low-power display made for the handlebars — not a repurposed phone screen.</string>
    <string name="onboarding_privacy_title">Your rides stay yours</string>
    <string name="onboarding_privacy_body">No accounts, no cloud, no tracking. Every ride is stored only on this device.</string>
    <string name="onboarding_tracking_title">Track every ride</string>
    <string name="onboarding_tracking_body">GPS speed, distance, elevation and calories — plus optional heart-rate and cadence sensors.</string>
    <string name="onboarding_location_title">Location access</string>
    <string name="onboarding_location_body">InkRide uses your location to track rides via GPS. This data never leaves your device.</string>
    <string name="onboarding_notification_title">Ride notifications</string>
    <string name="onboarding_notification_body">A notification keeps ride tracking running reliably while your screen is off.</string>
    <string name="onboarding_next">Next</string>
    <string name="onboarding_skip">Skip</string>
    <string name="onboarding_permission_continue">Continue</string>
    <string name="onboarding_permission_open_settings">Open Settings</string>
    <string name="onboarding_permission_skip">Skip for now</string>
</resources>
```

- [ ] **Step 2: Add the Polish translation**

Create `feature/onboarding/presentation/src/main/res/values-pl/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="onboarding_eink_title">Stworzone dla E-Ink</string>
    <string name="onboarding_eink_body">Czytelny w słońcu, energooszczędny ekran zaprojektowany na kierownicę — a nie odświeżony ekran telefonu.</string>
    <string name="onboarding_privacy_title">Twoje przejażdżki należą tylko do Ciebie</string>
    <string name="onboarding_privacy_body">Bez konta, bez chmury, bez śledzenia. Każda przejażdżka jest zapisywana wyłącznie na tym urządzeniu.</string>
    <string name="onboarding_tracking_title">Śledź każdą przejażdżkę</string>
    <string name="onboarding_tracking_body">Prędkość GPS, dystans, przewyższenie i kalorie — a także opcjonalne czujniki tętna i kadencji.</string>
    <string name="onboarding_location_title">Dostęp do lokalizacji</string>
    <string name="onboarding_location_body">InkRide używa Twojej lokalizacji do śledzenia przejażdżek przez GPS. Te dane nigdy nie opuszczają urządzenia.</string>
    <string name="onboarding_notification_title">Powiadomienia o przejażdżce</string>
    <string name="onboarding_notification_body">Powiadomienie utrzymuje niezawodne śledzenie przejażdżki, gdy ekran jest wyłączony.</string>
    <string name="onboarding_next">Dalej</string>
    <string name="onboarding_skip">Pomiń</string>
    <string name="onboarding_permission_continue">Kontynuuj</string>
    <string name="onboarding_permission_open_settings">Otwórz ustawienia</string>
    <string name="onboarding_permission_skip">Pomiń na razie</string>
</resources>
```

- [ ] **Step 3: Verify the module still compiles**

Run: `./gradlew :feature:onboarding:presentation:testDebugUnitTest`
Expected: PASS (unchanged from Task 3 — this confirms the new string keys don't collide with anything and `R` regenerates cleanly).

- [ ] **Step 4: Commit**

```bash
git add feature/onboarding/presentation/src/main/res
git commit -m "$(cat <<'EOF'
Add onboarding copy (EN + PL)

Replaces the placeholder strings.xml from Task 2 with final
value-prop and permission-priming copy in both supported locales.
EOF
)"
```

---

## Task 5: Onboarding UI components + screen

**Files:**
- Create: `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingTestTags.kt`
- Create: `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/components/OnboardingPageIndicator.kt`
- Create: `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/components/ValuePropPage.kt`
- Create: `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/components/PermissionPrimingPage.kt`
- Create: `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingScreen.kt`

**Interfaces:**
- Consumes: `OnboardingState`, `OnboardingAction`, `OnboardingEvent`, `OnboardingViewModel` (Task 3); `OnboardingStep`, `isPermissionStep` (Task 2); `R.string.onboarding_*` (Task 4).
- Produces: `@Composable fun OnboardingRoot(onOnboardingComplete: () -> Unit)` — consumed by Task 6 (`OnboardingNavigation`).

- [ ] **Step 1: Add test tags**

Create `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingTestTags.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation

/**
 * Stable identifiers for Compose UI tests, matching the pattern in
 * DashboardTestTags.
 */
object OnboardingTestTags {
    const val PAGER = "onboarding_pager"
    const val PAGE_TITLE = "onboarding_page_title"
    const val NEXT_BUTTON = "onboarding_next_button"
    const val SKIP_BUTTON = "onboarding_skip_button"
    const val PERMISSION_PRIMARY_BUTTON = "onboarding_permission_primary_button"
    const val PERMISSION_SKIP_BUTTON = "onboarding_permission_skip_button"
}
```

- [ ] **Step 2: Add the dot page indicator**

Create `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/components/OnboardingPageIndicator.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Horizontal counterpart to VerticalPagerIndicator (dashboard module). MMD
 * has no page-indicator component, so -- like that one -- this is a
 * deliberate custom-UI exception. Dots update in a single discrete state
 * change, no animation, per the E-Ink no-fluid-motion rule.
 */
@Composable
fun OnboardingPageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isActive = index == currentPage
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .then(
                            if (isActive) {
                                Modifier.background(MaterialTheme.colorScheme.primary)
                            } else {
                                Modifier
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            },
                        ),
            )
        }
    }
}
```

- [ ] **Step 3: Add the value-prop page content**

Create `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/components/ValuePropPage.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.onboarding.presentation.OnboardingStep
import com.speedevand.inkride.onboarding.presentation.OnboardingTestTags

@Composable
fun ValuePropPage(step: OnboardingStep) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = step.icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        TextMMD(
            text = stringResource(step.titleRes),
            modifier =
                Modifier
                    .padding(top = DesignConstants.PADDING_LARGE)
                    .testTag(OnboardingTestTags.PAGE_TITLE),
        )
        TextMMD(
            text = stringResource(step.bodyRes),
            modifier = Modifier.padding(top = DesignConstants.PADDING_MEDIUM),
        )
    }
}
```

- [ ] **Step 4: Add the permission-priming page**

Create `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/components/PermissionPrimingPage.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.onboarding.presentation.OnboardingStep
import com.speedevand.inkride.onboarding.presentation.OnboardingTestTags
import com.speedevand.inkride.onboarding.presentation.R

/**
 * Rationale + adaptive request button for one permission-priming step. Fully
 * self-contained (launcher, live grant status, permanently-denied
 * detection) since none of this is shared state the ViewModel needs --
 * [onContinue] only signals "the user is done with this step", identical to
 * DashboardScreen's own self-contained permission-launcher pattern.
 */
@Composable
fun PermissionPrimingPage(
    step: OnboardingStep,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // Disambiguates "never asked" from "permanently denied" -- both make
    // shouldShowRequestPermissionRationale return false, so only having
    // asked once already tells them apart.
    var hasRequestedOnce by rememberSaveable(step) { mutableStateOf(false) }

    // Bumped on ON_RESUME so returning from the system Settings screen
    // re-reads live permission state instead of showing stale button text.
    var refreshTick by rememberSaveable(step) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshTick++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allGranted =
        remember(step, refreshTick) {
            step.permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    val permanentlyDenied =
        hasRequestedOnce && !allGranted && activity != null &&
            step.permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            hasRequestedOnce = true
            onContinue()
        }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = step.icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        TextMMD(
            text = stringResource(step.titleRes),
            modifier =
                Modifier
                    .padding(top = DesignConstants.PADDING_LARGE)
                    .testTag(OnboardingTestTags.PAGE_TITLE),
        )
        TextMMD(
            text = stringResource(step.bodyRes),
            modifier = Modifier.padding(top = DesignConstants.PADDING_MEDIUM),
        )

        Column(
            modifier = Modifier.padding(top = DesignConstants.PADDING_LARGE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ButtonMMD(
                onClick = {
                    when {
                        allGranted -> onContinue()
                        permanentlyDenied -> context.openAppSettings()
                        else -> launcher.launch(step.permissions.toTypedArray())
                    }
                },
                modifier = Modifier.testTag(OnboardingTestTags.PERMISSION_PRIMARY_BUTTON),
            ) {
                TextMMD(
                    text =
                        stringResource(
                            if (permanentlyDenied) {
                                R.string.onboarding_permission_open_settings
                            } else {
                                R.string.onboarding_permission_continue
                            },
                        ),
                )
            }
            OutlinedButtonMMD(
                onClick = onContinue,
                modifier =
                    Modifier
                        .padding(top = DesignConstants.PADDING_SMALL)
                        .testTag(OnboardingTestTags.PERMISSION_SKIP_BUTTON),
            ) {
                TextMMD(text = stringResource(R.string.onboarding_permission_skip))
            }
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null)),
    )
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
```

- [ ] **Step 5: Add the Root/Screen composables**

Create `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingScreen.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation

import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.design_system.InkRideTheme
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.core.presentation.ObserveAsEvents
import com.speedevand.inkride.onboarding.presentation.components.OnboardingPageIndicator
import com.speedevand.inkride.onboarding.presentation.components.PermissionPrimingPage
import com.speedevand.inkride.onboarding.presentation.components.ValuePropPage
import org.koin.androidx.compose.koinViewModel

@Composable
fun OnboardingRoot(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            OnboardingEvent.NavigateToDashboard -> onOnboardingComplete()
        }
    }

    OnboardingScreen(
        state = state,
        onAction = viewModel::onAction,
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onAction: (OnboardingAction) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { state.steps.size })

    LaunchedEffect(state.pageIndex) {
        if (pagerState.currentPage != state.pageIndex) {
            pagerState.animateScrollToPage(page = state.pageIndex, animationSpec = snap())
        }
    }

    val currentStep = state.steps[state.pageIndex]

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(DesignConstants.PADDING_LARGE),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            HorizontalPager(
                state = pagerState,
                // Page changes are button-driven only, keeping step order
                // and permission gating deterministic (no accidental swipe
                // past an unhandled permission step).
                userScrollEnabled = false,
                modifier = Modifier.weight(1f).testTag(OnboardingTestTags.PAGER),
            ) { pageIndex ->
                val step = state.steps[pageIndex]
                if (step.isPermissionStep) {
                    PermissionPrimingPage(
                        step = step,
                        onContinue = { onAction(OnboardingAction.OnNextClicked) },
                    )
                } else {
                    ValuePropPage(step = step)
                }
            }

            OnboardingPageIndicator(
                pageCount = state.steps.size,
                currentPage = state.pageIndex,
                modifier = Modifier.padding(vertical = DesignConstants.PADDING_MEDIUM),
            )

            if (!currentStep.isPermissionStep) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OutlinedButtonMMD(
                        onClick = { onAction(OnboardingAction.OnSkipValuePropClicked) },
                        modifier = Modifier.testTag(OnboardingTestTags.SKIP_BUTTON),
                    ) {
                        TextMMD(text = stringResource(R.string.onboarding_skip))
                    }
                    ButtonMMD(
                        onClick = { onAction(OnboardingAction.OnNextClicked) },
                        modifier = Modifier.testTag(OnboardingTestTags.NEXT_BUTTON),
                    ) {
                        TextMMD(text = stringResource(R.string.onboarding_next))
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun OnboardingScreenPreview() {
    InkRideTheme {
        OnboardingScreen(state = OnboardingState(), onAction = {})
    }
}
```

- [ ] **Step 6: Verify the module compiles**

Run: `./gradlew :feature:onboarding:presentation:testDebugUnitTest`
Expected: PASS (unchanged test count — this task adds no new unit tests, it's UI wiring verified visually in Task 8's instrumented tests).

- [ ] **Step 7: Format and lint**

Run: `./gradlew ktlintFormat`
Run: `./gradlew ktlintCheck`
Expected: both succeed.

- [ ] **Step 8: Commit**

```bash
git add feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingTestTags.kt \
        feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/components \
        feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingScreen.kt
git commit -m "$(cat <<'EOF'
Add onboarding screen UI

Custom HorizontalPager (MMD has no pager/indicator component) with
snap() page transitions, value-prop pages, and adaptive
permission-priming pages (Continue -> Open Settings once
permanently denied, with an always-available Skip for now).
EOF
)"
```

---

## Task 6: Navigation route + graph + DI wiring

**Files:**
- Modify: `core/domain/src/main/java/com/speedevand/inkride/core/domain/navigation/NavigationRoutes.kt`
- Create: `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingNavigation.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/speedevand/inkride/InkRideApp.kt`

**Interfaces:**
- Consumes: `OnboardingRoot` (Task 5), `onboardingPresentationModule` (Task 3).
- Produces: `data object OnboardingRoute`, `fun NavGraphBuilder.onboardingGraph(navController: NavController)` — consumed by Task 7 (`AppNavigation`).

- [ ] **Step 1: Add the route**

Edit `core/domain/src/main/java/com/speedevand/inkride/core/domain/navigation/NavigationRoutes.kt` — add after `DashboardRoute`:

```kotlin
@Serializable
data object DashboardRoute

@Serializable
data object OnboardingRoute
```

- [ ] **Step 2: Add the nav graph extension**

Create `feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingNavigation.kt`:

```kotlin
package com.speedevand.inkride.onboarding.presentation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.speedevand.inkride.core.domain.navigation.DashboardGraph
import com.speedevand.inkride.core.domain.navigation.OnboardingRoute

fun NavGraphBuilder.onboardingGraph(navController: NavController) {
    composable<OnboardingRoute> {
        OnboardingRoot(
            onOnboardingComplete = {
                navController.navigate(DashboardGraph) {
                    popUpTo(OnboardingRoute) { inclusive = true }
                }
            },
        )
    }
}
```

- [ ] **Step 3: Add the module dependency in `:app`**

Edit `app/build.gradle.kts` — add after `implementation(project(":feature:dashboard:presentation"))`:

```kotlin
    implementation(project(":feature:dashboard:presentation"))
    implementation(project(":feature:onboarding:presentation"))
```

- [ ] **Step 4: Register the Koin module**

Edit `app/src/main/java/com/speedevand/inkride/InkRideApp.kt` — add the import:

```kotlin
import com.speedevand.inkride.onboarding.presentation.onboardingPresentationModule
```

And add `onboardingPresentationModule` to the `modules(...)` list, after `dashboardPresentationModule`:

```kotlin
                dashboardPresentationModule,
                onboardingPresentationModule,
```

- [ ] **Step 5: Verify the app module compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`AppNavigation`/`MainActivity` don't reference `onboardingGraph`/`OnboardingRoute` yet — that's Task 7 — so this only confirms the new module wires into `:app` cleanly.)

- [ ] **Step 6: Format and lint**

Run: `./gradlew ktlintFormat`
Run: `./gradlew ktlintCheck`
Expected: both succeed.

- [ ] **Step 7: Commit**

```bash
git add core/domain/src/main/java/com/speedevand/inkride/core/domain/navigation/NavigationRoutes.kt \
        feature/onboarding/presentation/src/main/java/com/speedevand/inkride/onboarding/presentation/OnboardingNavigation.kt \
        app/build.gradle.kts \
        app/src/main/java/com/speedevand/inkride/InkRideApp.kt
git commit -m "$(cat <<'EOF'
Wire onboarding route and DI into the app module

OnboardingRoute + onboardingGraph, and onboardingPresentationModule
registered in InkRideApp. AppNavigation itself is wired up next.
EOF
)"
```

---

## Task 7: Dynamic start destination

**Files:**
- Modify: `app/src/main/java/com/speedevand/inkride/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/speedevand/inkride/MainActivity.kt`

**Interfaces:**
- Consumes: `onboardingGraph`, `OnboardingRoute` (Task 6); `UserSettings.hasCompletedOnboarding` (Task 1).

- [ ] **Step 1: Make `AppNavigation`'s start destination a parameter**

Edit `app/src/main/java/com/speedevand/inkride/navigation/AppNavigation.kt`:

Add the import:

```kotlin
import com.speedevand.inkride.onboarding.presentation.onboardingGraph
```

Change the function signature from `fun AppNavigation()` to:

```kotlin
@Composable
fun AppNavigation(startDestination: Any) {
```

Change the `NavHost`'s `startDestination = DashboardGraph` to:

```kotlin
        NavHost(
            navController = navController,
            startDestination = startDestination,
```

Add `onboardingGraph(navController)` to the `NavHost` content block, alongside the other graphs:

```kotlin
            dashboardGraph(navController, TrackingService::class.java)
            onboardingGraph(navController)
            historyGraph(navController)
            settingsGraph(navController)
            bleGraph(navController)
```

- [ ] **Step 2: Gate `MainActivity` on the first settings emission and compute the start destination**

Edit `app/src/main/java/com/speedevand/inkride/MainActivity.kt`:

Add the imports:

```kotlin
import com.speedevand.inkride.core.domain.navigation.DashboardGraph
import com.speedevand.inkride.core.domain.navigation.OnboardingRoute
```

Change the final block from:

```kotlin
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalOnBackPressedDispatcherOwner provides this@MainActivity,
            ) {
                InkRideTheme {
                    AppNavigation()
                }
            }
```

to:

```kotlin
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalOnBackPressedDispatcherOwner provides this@MainActivity,
            ) {
                InkRideTheme {
                    // Waits for the first UserSettings emission before
                    // composing AppNavigation at all -- NavHost's
                    // startDestination is fixed at first composition, so it
                    // can't be changed reactively once set. This is a
                    // deliberate, brief blank frame on cold start (a
                    // single-row Room read is fast); see the onboarding
                    // design doc for the tradeoff.
                    userSettings?.let { settings ->
                        AppNavigation(
                            startDestination =
                                if (settings.hasCompletedOnboarding) DashboardGraph else OnboardingRoute,
                        )
                    }
                }
            }
```

- [ ] **Step 3: Build the app module**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Format and lint**

Run: `./gradlew ktlintFormat`
Run: `./gradlew ktlintCheck`
Expected: both succeed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/speedevand/inkride/navigation/AppNavigation.kt \
        app/src/main/java/com/speedevand/inkride/MainActivity.kt
git commit -m "$(cat <<'EOF'
Route first-run users to onboarding

AppNavigation's start destination is now computed once the first
UserSettings emission arrives: OnboardingRoute when
hasCompletedOnboarding is false, DashboardGraph otherwise.
EOF
)"
```

---

## Task 8: Instrumented end-to-end tests

**Files:**
- Create: `app/src/androidTest/java/com/speedevand/inkride/onboarding/OnboardingE2ETest.kt`

**Interfaces:**
- Consumes: `OnboardingTestTags` (Task 5), `DashboardTestTags.METRICS_PAGER` (existing), `UserSettingsRepository`, `MainActivity`.

- [ ] **Step 1: Write the instrumented tests**

Create `app/src/androidTest/java/com/speedevand/inkride/onboarding/OnboardingE2ETest.kt`:

```kotlin
package com.speedevand.inkride.onboarding

import androidx.compose.ui.test.assertExists
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
```

- [ ] **Step 2: Run the instrumented tests on a connected device or emulator**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.speedevand.inkride.onboarding.OnboardingE2ETest"`
Expected: PASS (both tests). Requires a connected device/emulator, per `CLAUDE.md`'s instrumented-test requirement — if none is available, note that explicitly rather than claiming this step passed.

- [ ] **Step 3: Format and lint**

Run: `./gradlew ktlintFormat`
Run: `./gradlew ktlintCheck`
Expected: both succeed.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/com/speedevand/inkride/onboarding/OnboardingE2ETest.kt
git commit -m "$(cat <<'EOF'
Add onboarding instrumented E2E tests

Covers the first-launch walkthrough (skip through to dashboard,
hasCompletedOnboarding persisted) and a returning user's direct
route to the dashboard with onboarding never shown.
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** all six spec sections have a task — flow/module structure (Tasks 2, 6, 7), UI/E-Ink constraints (Task 5), data model (Task 1), MVI contract (Task 3), edge cases (SavedStateHandle in Task 3, permanently-denied handling in Task 5, locale coverage in Task 4), testing (Tasks 1, 2, 3 unit tests; Task 8 instrumented).
- **Placeholder scan:** none — every step has literal file content; the one deferred detail (final strings, Task 2 → Task 4) is explicitly called out as a placeholder *in the plan itself*, not left vague, and is a genuine two-step sequence (module must compile before real copy is worth writing) rather than an unresolved TODO.
- **Type consistency:** `OnboardingState`/`OnboardingAction`/`OnboardingEvent` (Task 3) match their usage in `OnboardingScreen`/`OnboardingRoot` (Task 5) and `OnboardingViewModelTest` (Task 3); `OnboardingStep`'s `icon`/`titleRes`/`bodyRes`/`permissions`/`isPermissionStep` (Task 2) match every consumer in Tasks 3 and 5; `hasCompletedOnboarding` is named identically across `UserSettings`, `UserSettingsEntity`, and both repository mapping directions (Task 1).
