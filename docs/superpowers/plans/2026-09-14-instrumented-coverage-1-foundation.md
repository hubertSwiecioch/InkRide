# Instrumented Coverage, Plan 1: Test Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the shared `:core:testing` module — fakes, Koin rule, Compose helpers, data builders — and consolidate every duplicated test fake onto it, so plans 2–5 have one place to get test infrastructure from.

**Architecture:** A new Android library module consumed only from test source sets (`androidTestImplementation` / `testImplementation`), so nothing in it reaches a production APK. It holds one fake per `:core:domain` interface (each `MutableStateFlow`-backed with an error knob), sample-data builders, a JUnit4 `KoinTestRule` that starts and stops a Koin container per test class, and the Compose semantics helpers currently trapped in `:app/androidTest`. Feature modules receive the shared `androidTest` dependency set from `AndroidFeatureConventionPlugin` rather than declaring it five times.

**Tech Stack:** Kotlin 2.3.21, AGP 9.2.1, Gradle convention plugins in `build-logic/`, Koin 4.2.1 (+ `koin-test`), Compose BOM 2026.05.01 with `ui-test-junit4`, JUnit4 for instrumented tests / JUnit5 for JVM tests, assertk, Turbine, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-09-14-full-instrumented-test-coverage-design.md`

## Global Constraints

- Kotlin JVM target 11; `compileSdk = 36`, `minSdk = 26`.
- `./gradlew ktlintCheck` must pass; run `./gradlew ktlintFormat` before every commit. This applies to `test` and `androidTest` sources too.
- No Firebase, no GMS, no Google Analytics anywhere, including test code.
- Instrumented tests must never make a network request. Every networked interface (`PlaceSearchService`, `RoutingService`, `CurrentLocationProvider`) is faked.
- JVM unit tests run on JUnit5 (`useJUnitPlatform()`); instrumented tests run on JUnit4 with `androidx.test.runner.AndroidJUnitRunner`. Do not mix annotations.
- `:core:testing` must never depend on a `:feature:*` module. Fakes for interfaces declared inside feature modules (`GpxExporter`, `GpxRouteLoader`) stay in those modules' own test source sets.
- Existing green tests stay green through every consolidation task. A consolidation that changes an assertion is out of scope — it is a refactor, not a rewrite.

---

### Task 1: `:core:testing` module skeleton and build wiring

**Files:**
- Create: `core/testing/build.gradle.kts`
- Create: `core/testing/src/main/AndroidManifest.xml`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/support/StringRes.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/support/ComposeSemantics.kt`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `build-logic/convention/src/main/kotlin/com/speedevand/inkride/convention/AndroidFeatureConventionPlugin.kt`
- Modify: `app/src/androidTest/java/com/speedevand/inkride/tracking/support/ComposeTestHelpers.kt`
- Delete: `app/src/androidTest/java/com/speedevand/inkride/tracking/support/DashboardStrings.kt`
- Modify: every `:app/androidTest` file importing `dashboardString` or the moved helpers

**Interfaces:**
- Consumes: nothing (first task).
- Produces:
  - `fun stringRes(@StringRes id: Int): String`
  - `fun stringRes(@StringRes id: Int, vararg formatArgs: Any): String`
  - `fun SemanticsNodeInteraction.text(): String`
  - `fun ComposeTestRule.textOf(tag: String): String`
  - `fun ComposeTestRule.waitUntilTagText(tag: String, timeoutMillis: Long = 15_000L, predicate: (String) -> Boolean)`
  - `fun SemanticsNodeInteraction.contentDescription(): String`
  - `fun ComposeTestRule.contentDescriptionOf(tag: String): String`
  - `fun ComposeTestRule.waitUntilTagContentDescription(tag: String, timeoutMillis: Long = 15_000L, predicate: (String) -> Boolean)`
  - All of the above in package `com.speedevand.inkride.core.testing.support`.

---

- [ ] **Step 1: Add the version catalog entries**

Add to `[libraries]` in `gradle/libs.versions.toml`:

```toml
koin-test = { group = "io.insert-koin", name = "koin-test", version.ref = "koin" }
androidx-espresso-intents = { group = "androidx.test.espresso", name = "espresso-intents", version.ref = "espressoCore" }
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
```

`espresso-intents` and `room-testing` are unused until plans 2 and 4; they are added here so the catalog is touched once.

- [ ] **Step 2: Register the module**

Add to `settings.gradle.kts`, immediately after the `include(":core:presentation")` line:

```kotlin
include(":core:testing")
```

- [ ] **Step 3: Create the module build file**

Create `core/testing/build.gradle.kts`:

```kotlin
plugins {
    id("inkride.android.library")
    id("inkride.compose")
}

android {
    namespace = "com.speedevand.inkride.core.testing"
}

// This module is only ever consumed from test source sets
// (androidTestImplementation / testImplementation), so nothing here reaches a
// production APK. Dependencies are `api` rather than `implementation` so a
// consumer gets the Compose/JUnit types that appear in these helpers'
// signatures without re-declaring them.
dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.coroutines.core)
    api(libs.junit)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.androidx.test.core)
    api(libs.koin.android)
    api(libs.koin.test)
}
```

- [ ] **Step 4: Create the module manifest**

Create `core/testing/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 5: Verify the module assembles**

Run: `./gradlew :core:testing:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Add the string helper**

Create `core/testing/src/main/java/com/speedevand/inkride/core/testing/support/StringRes.kt`:

```kotlin
package com.speedevand.inkride.core.testing.support

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Resolves a string resource through the real app context, so assertions
 * compare against the actual localized string the UI renders instead of a
 * hardcoded English literal that would silently drift from `strings.xml`.
 *
 * In a library module's `androidTest`, the library and its test code share one
 * APK, so `targetContext` resolves that library's own resources.
 */
fun stringRes(
    @StringRes id: Int,
): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

fun stringRes(
    @StringRes id: Int,
    vararg formatArgs: Any,
): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *formatArgs)
```

- [ ] **Step 7: Move the Compose semantics helpers**

Create `core/testing/src/main/java/com/speedevand/inkride/core/testing/support/ComposeSemantics.kt` with the six generic helpers lifted verbatim from `app/src/androidTest/java/com/speedevand/inkride/tracking/support/ComposeTestHelpers.kt`:

```kotlin
package com.speedevand.inkride.core.testing.support

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag

/**
 * The literal text rendered by a single tagged text node (e.g. a `TextMMD`
 * carrying a `Modifier.testTag(...)`). Reading semantics directly, rather
 * than matching on formatted/localized strings, keeps assertions robust to
 * decimal-separator/unit differences.
 */
fun SemanticsNodeInteraction.text(): String =
    fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString(separator = "") { it.text }

fun ComposeTestRule.textOf(tag: String): String = onNodeWithTag(tag).assertIsDisplayed().text()

/**
 * Polls a tagged node's text against [predicate] on a real wall clock, for
 * state that arrives from a background coroutine rather than Compose's test
 * clock.
 */
fun ComposeTestRule.waitUntilTagText(
    tag: String,
    timeoutMillis: Long = 15_000L,
    predicate: (String) -> Boolean,
) {
    waitUntil(timeoutMillis) {
        runCatching { predicate(textOf(tag)) }.getOrDefault(false)
    }
}

/** The content description of a single tagged node (e.g. an `Icon` carrying a `testTag`). */
fun SemanticsNodeInteraction.contentDescription(): String =
    fetchSemanticsNode()
        .config[SemanticsProperties.ContentDescription]
        .joinToString(separator = "") { it }

fun ComposeTestRule.contentDescriptionOf(tag: String): String = onNodeWithTag(tag).assertIsDisplayed().contentDescription()

/** Same real-clock polling as [waitUntilTagText], against a tagged node's content description. */
fun ComposeTestRule.waitUntilTagContentDescription(
    tag: String,
    timeoutMillis: Long = 15_000L,
    predicate: (String) -> Boolean,
) {
    waitUntil(timeoutMillis) {
        runCatching { predicate(contentDescriptionOf(tag)) }.getOrDefault(false)
    }
}
```

- [ ] **Step 8: Shrink `:app`'s helper file to the dashboard-specific parts**

The two swipe helpers reference `DashboardTestTags`, so they cannot move into `:core:testing` (which must not depend on a feature module). Replace the whole contents of `app/src/androidTest/java/com/speedevand/inkride/tracking/support/ComposeTestHelpers.kt` with:

```kotlin
package com.speedevand.inkride.tracking.support

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.speedevand.inkride.dashboard.presentation.DashboardTestTags

fun ComposeTestRule.swipeMetricsPagerToNextPage() {
    onNodeWithTag(DashboardTestTags.METRICS_PAGER).performTouchInput { swipeUp() }
    waitForIdle()
}

fun ComposeTestRule.swipeMetricsPagerToPreviousPage() {
    onNodeWithTag(DashboardTestTags.METRICS_PAGER).performTouchInput { swipeDown() }
    waitForIdle()
}
```

- [ ] **Step 9: Delete the superseded string helper**

```bash
git rm app/src/androidTest/java/com/speedevand/inkride/tracking/support/DashboardStrings.kt
```

- [ ] **Step 10: Add the shared androidTest dependency set to the feature convention plugin**

In `build-logic/convention/src/main/kotlin/com/speedevand/inkride/convention/AndroidFeatureConventionPlugin.kt`, extend the existing `dependencies { ... }` block with:

```kotlin
                // Shared instrumented-test stack. Declared once here rather
                // than in each of the five presentation modules. `:core:testing`
                // is test-only, so none of this reaches a production APK.
                add("androidTestImplementation", project(":core:testing"))
                add("androidTestImplementation", platform(libs.findLibrary("androidx-compose-bom").get()))
                add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
                add("androidTestImplementation", libs.findLibrary("androidx-junit").get())
                add("androidTestImplementation", libs.findLibrary("androidx-test-core").get())
                add("androidTestImplementation", libs.findLibrary("androidx-test-rules").get())
                add("androidTestImplementation", libs.findLibrary("assertk").get())
                add("androidTestImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
                // A library module has no Activity of its own; this supplies the
                // ComponentActivity that createAndroidComposeRule launches.
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
```

- [ ] **Step 11: Point `:app`'s androidTest at the shared module**

In `app/build.gradle.kts`, add to the `dependencies` block next to the other `androidTestImplementation` lines:

```kotlin
    androidTestImplementation(project(":core:testing"))
```

- [ ] **Step 12: Fix imports across `:app/androidTest`**

Every call site of `dashboardString(...)` becomes `stringRes(...)`, and the moved helpers now come from the new package. Apply:

```bash
cd /Users/hubert/AndroidStudioProjects/InkRide
grep -rl 'dashboardString' app/src/androidTest | xargs sed -i '' \
  -e 's/dashboardString(/stringRes(/g' \
  -e 's#import com.speedevand.inkride.tracking.support.stringRes#import com.speedevand.inkride.core.testing.support.stringRes#'
grep -rl 'tracking.support.textOf\|tracking.support.waitUntilTag\|tracking.support.contentDescriptionOf\|tracking.support.text$' app/src/androidTest | xargs sed -i '' \
  -e 's#import com.speedevand.inkride.tracking.support.\(textOf\|text\|waitUntilTagText\|contentDescription\|contentDescriptionOf\|waitUntilTagContentDescription\)#import com.speedevand.inkride.core.testing.support.\1#'
```

Then compile and fix anything the compiler still flags — the import list varies per file, so treat the compiler as the source of truth here:

Run: `./gradlew :app:assembleDebugAndroidTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 13: Run the existing instrumented suite to prove nothing regressed**

Run (emulator must be running): `./gradlew :app:connectedDebugAndroidTest`
Expected: PASS — all 13 existing tests, same as before the move.

- [ ] **Step 14: Format and verify style**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 15: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml core/testing \
        build-logic/convention/src/main/kotlin/com/speedevand/inkride/convention/AndroidFeatureConventionPlugin.kt \
        app/build.gradle.kts app/src/androidTest
git commit -m "test: add :core:testing module with shared Compose and string helpers"
```

---

### Task 2: Settings and BLE fakes plus data builders

**Files:**
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeUserSettingsRepository.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeBikeProfileRepository.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeBleScanner.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/support/TestData.kt`
- Delete: `feature/settings/presentation/src/test/kotlin/com/speedevand/inkride/settings/presentation/FakeUserSettingsRepository.kt`
- Delete: `feature/onboarding/presentation/src/test/kotlin/com/speedevand/inkride/onboarding/presentation/FakeUserSettingsRepository.kt`
- Modify: `feature/settings/presentation/build.gradle.kts`, `feature/onboarding/presentation/build.gradle.kts`
- Modify: `feature/settings/presentation/src/test/kotlin/.../SettingsViewModelTest.kt`, `feature/onboarding/presentation/src/test/kotlin/.../OnboardingViewModelTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1 at compile time; shares its module.
- Produces:
  - `class FakeUserSettingsRepository : UserSettingsRepository` with `val lastSaved: UserSettings?`, `var saveResult: EmptyResult<DataError.Local>`, `fun emitSettings(settings: UserSettings)`
  - `class FakeBikeProfileRepository : BikeProfileRepository` with `var upsertResult: Result<Long, DataError.Local>?`, `var deleteResult: EmptyResult<DataError.Local>`, `fun emitProfiles(profiles: List<BikeProfile>)`, `val deletedIds: List<Long>`
  - `class FakeBleScanner : BleScanner` with `var availability: EmptyResult<BleScanError>`, `fun emitDevice(device: BleDevice)`, `val scanCount: Int`, `val stoppedScans: Int`
  - `object TestSettings` with `fun default(...): UserSettings`
  - `object TestBikeProfiles` with `fun road(...)`, `fun mtb(...)`
  - `object TestBleDevices` with `fun hrm(...)`, `fun cadence(...)`

---

- [ ] **Step 1: Write `FakeUserSettingsRepository`**

Create `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeUserSettingsRepository.kt`. This is the existing duplicated fake, moved and unchanged in behaviour so the tests that already use it keep passing:

```kotlin
package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeUserSettingsRepository(
    initial: UserSettings = UserSettings(weightKg = 75, age = 30),
) : UserSettingsRepository {
    private val settingsFlow = MutableStateFlow(initial)

    var lastSaved: UserSettings? = null
        private set

    /** Set to a `Result.Error` to drive the repository-failure path. */
    var saveResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    override fun observeSettings(): Flow<UserSettings> = settingsFlow

    override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> {
        lastSaved = settings
        // A failed save must not change observable state, or a test asserting
        // the error path would still see the UI update.
        if (saveResult is Result.Success) {
            settingsFlow.value = settings
        }
        return saveResult
    }

    fun emitSettings(settings: UserSettings) {
        settingsFlow.value = settings
    }
}
```

Note the one behavioural difference from the fakes being replaced: a failed save no longer mutates the flow. Verify in Step 6 that no existing test depended on the old behaviour; if one does, that test asserted something incoherent and the fix belongs in the test.

- [ ] **Step 2: Write `FakeBikeProfileRepository`**

```kotlin
package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.settings.BikeProfile
import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeBikeProfileRepository(
    initial: List<BikeProfile> = emptyList(),
) : BikeProfileRepository {
    private val profilesFlow = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    private val _deletedIds = mutableListOf<Long>()
    val deletedIds: List<Long> get() = _deletedIds

    /** When non-null, `upsert` returns this instead of storing the profile. */
    var upsertResult: Result<Long, DataError.Local>? = null
    var deleteResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    override fun observeProfiles(): Flow<List<BikeProfile>> = profilesFlow

    override suspend fun upsert(profile: BikeProfile): Result<Long, DataError.Local> {
        upsertResult?.let { return it }
        val id = if (profile.id == 0L) nextId++ else profile.id
        val stored = profile.copy(id = id)
        profilesFlow.value =
            profilesFlow.value
                .filterNot { it.id == id }
                .plus(stored)
                .sortedBy { it.id }
        return Result.Success(id)
    }

    override suspend fun delete(id: Long): EmptyResult<DataError.Local> {
        if (deleteResult is Result.Success) {
            _deletedIds += id
            profilesFlow.value = profilesFlow.value.filterNot { it.id == id }
        }
        return deleteResult
    }

    fun emitProfiles(profiles: List<BikeProfile>) {
        profilesFlow.value = profiles
    }
}
```

- [ ] **Step 3: Write `FakeBleScanner`**

`BleScanner.scan` returns a `Flow<BleDevice>` that emits until cancelled, so the fake backs it with a `MutableSharedFlow` and records how many scans started and how many were cancelled — that is how a test proves "selecting a device stops the scan".

```kotlin
package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.ble.BleDevice
import com.speedevand.inkride.core.domain.ble.BleScanError
import com.speedevand.inkride.core.domain.ble.BleScanner
import com.speedevand.inkride.core.domain.ble.BleSensorType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

class FakeBleScanner : BleScanner {
    private val devices = MutableSharedFlow<BleDevice>(replay = 0, extraBufferCapacity = 16)

    /** Set to a `Result.Error` to drive the adapter-off / permission-denied path. */
    var availability: EmptyResult<BleScanError> = Result.Success(Unit)

    var scanCount: Int = 0
        private set

    var stoppedScans: Int = 0
        private set

    var lastScannedType: BleSensorType? = null
        private set

    override fun available(): EmptyResult<BleScanError> = availability

    override fun scan(type: BleSensorType): Flow<BleDevice> =
        devices
            .filter { it.type == type }
            .onStart {
                scanCount++
                lastScannedType = type
            }.onCompletion { stoppedScans++ }

    /** Emits a discovered device to whichever scan is currently collecting. */
    suspend fun emitDevice(device: BleDevice) {
        devices.emit(device)
    }
}
```

- [ ] **Step 4: Write the settings and BLE data builders**

Create `core/testing/src/main/java/com/speedevand/inkride/core/testing/support/TestData.kt`:

```kotlin
package com.speedevand.inkride.core.testing.support

import com.speedevand.inkride.core.domain.ble.BleDevice
import com.speedevand.inkride.core.domain.ble.BleSensorType
import com.speedevand.inkride.core.domain.settings.BikeProfile
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettings

object TestSettings {
    /**
     * A rider with weight and age filled in (both are required for calorie and
     * power estimation) and onboarding already complete, so a screen under test
     * is not redirected to the walkthrough.
     */
    fun default(
        units: MeasurementUnits = MeasurementUnits.METRIC,
        pairedHrmAddress: String? = null,
        pairedCadenceAddress: String? = null,
        activeBikeProfileId: Long? = null,
    ): UserSettings =
        UserSettings(
            weightKg = 75,
            age = 30,
            units = units,
            pairedHrmAddress = pairedHrmAddress,
            pairedCadenceAddress = pairedCadenceAddress,
            activeBikeProfileId = activeBikeProfileId,
            hasCompletedOnboarding = true,
        )
}

object TestBikeProfiles {
    fun road(
        id: Long = 1L,
        name: String = "Road bike",
        weightKg: Double = 8.5,
    ): BikeProfile = BikeProfile(id = id, name = name, weightKg = weightKg, type = BikeType.ROAD)

    fun mtb(
        id: Long = 2L,
        name: String = "MTB",
        weightKg: Double = 13.0,
    ): BikeProfile = BikeProfile(id = id, name = name, weightKg = weightKg, type = BikeType.MTB)
}

object TestBleDevices {
    fun hrm(
        address: String = "AA:BB:CC:DD:EE:01",
        name: String? = "HRM 9000",
    ): BleDevice = BleDevice(address = address, name = name, type = BleSensorType.HEART_RATE)

    fun cadence(
        address: String = "AA:BB:CC:DD:EE:02",
        name: String? = "Cadence 200",
    ): BleDevice = BleDevice(address = address, name = name, type = BleSensorType.CADENCE)
}
```

Before running, confirm the `BleSensorType` entry names by reading `core/domain/src/main/java/com/speedevand/inkride/core/domain/ble/BleSensorType.kt` and correct `HEART_RATE` / `CADENCE` if they differ.

- [ ] **Step 5: Point the settings and onboarding unit tests at the shared fake**

Add to both `feature/settings/presentation/build.gradle.kts` and `feature/onboarding/presentation/build.gradle.kts`, in their `dependencies` block:

```kotlin
    testImplementation(project(":core:testing"))
```

Delete the two duplicated files and fix the imports:

```bash
cd /Users/hubert/AndroidStudioProjects/InkRide
git rm feature/settings/presentation/src/test/kotlin/com/speedevand/inkride/settings/presentation/FakeUserSettingsRepository.kt
git rm feature/onboarding/presentation/src/test/kotlin/com/speedevand/inkride/onboarding/presentation/FakeUserSettingsRepository.kt
```

Add this import to `SettingsViewModelTest.kt` and `OnboardingViewModelTest.kt` (and any other file in those modules that referenced the deleted class):

```kotlin
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
```

- [ ] **Step 6: Run both modules' unit tests**

Run:
```bash
./gradlew :feature:settings:presentation:testDebugUnitTest :feature:onboarding:presentation:testDebugUnitTest
```
Expected: PASS, with the same test count as before the move. If a test fails because of the "failed save no longer mutates the flow" change from Step 1, read that test: it was asserting that a rejected write still updated observable state. Fix the assertion, do not restore the old fake behaviour.

- [ ] **Step 7: Format and verify style**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
```

- [ ] **Step 8: Commit**

```bash
git add core/testing feature/settings/presentation feature/onboarding/presentation
git commit -m "test: move settings/BLE fakes and data builders into :core:testing"
```

---

### Task 3: History fakes plus ride data builders

**Files:**
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeRideHistoryRepository.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeRideLapRepository.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeRideTrackPointRepository.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeLifetimeStatsRepository.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/support/TestRides.kt`
- Modify: `feature/history/presentation/build.gradle.kts`
- Modify: `feature/history/presentation/src/test/kotlin/.../RideHistoryViewModelTest.kt`, `.../RideDetailViewModelTest.kt` (drop their inline fakes)

**Interfaces:**
- Consumes: `TestSettings` from Task 2.
- Produces:
  - `class FakeRideHistoryRepository : RideHistoryRepository` with `fun emitRides(rides: List<RideRecord>)`, `var getByIdResult: Result<RideRecord, DataError.Local>?`, `var deleteResult: EmptyResult<DataError.Local>`, `var deleteAllResult: EmptyResult<DataError.Local>`, `var saveResult: Result<Long, DataError.Local>?`
  - `class FakeRideLapRepository : RideLapRepository` with `fun setLaps(rideId: Long, laps: List<LapRecord>)`, `var getResult: Result<List<LapRecord>, DataError.Local>?`
  - `class FakeRideTrackPointRepository : RideTrackPointRepository` with `fun setPoints(rideId: Long, points: List<RideTrackPoint>)`, `var getResult: Result<List<RideTrackPoint>, DataError.Local>?`
  - `class FakeLifetimeStatsRepository : LifetimeStatsRepository` with `fun emit(stats: LifetimeStats)`
  - `object TestRides` with `fun record(...): RideRecord`, `fun laps(count: Int): List<LapRecord>`, `fun trackPoints(count: Int): List<RideTrackPoint>`

---

- [ ] **Step 1: Write `FakeRideHistoryRepository`**

```kotlin
package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeRideHistoryRepository(
    initial: List<RideRecord> = emptyList(),
) : RideHistoryRepository {
    private val ridesFlow = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

    /** When non-null, overrides the stored lookup — use for the not-found path. */
    var getByIdResult: Result<RideRecord, DataError.Local>? = null
    var saveResult: Result<Long, DataError.Local>? = null
    var deleteResult: EmptyResult<DataError.Local> = Result.Success(Unit)
    var deleteAllResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    override fun observeAll(): Flow<List<RideRecord>> = ridesFlow

    override suspend fun getById(id: Long): Result<RideRecord, DataError.Local> {
        getByIdResult?.let { return it }
        val ride = ridesFlow.value.firstOrNull { it.id == id }
        return if (ride != null) Result.Success(ride) else Result.Error(DataError.Local.NOT_FOUND)
    }

    override suspend fun save(ride: RideRecord): Result<Long, DataError.Local> {
        saveResult?.let { return it }
        val id = if (ride.id == 0L) nextId++ else ride.id
        ridesFlow.value =
            ridesFlow.value
                .filterNot { it.id == id }
                .plus(ride.copy(id = id))
                .sortedByDescending { it.startTimestamp }
        return Result.Success(id)
    }

    override suspend fun deleteById(id: Long): EmptyResult<DataError.Local> {
        if (deleteResult is Result.Success) {
            ridesFlow.value = ridesFlow.value.filterNot { it.id == id }
        }
        return deleteResult
    }

    override suspend fun deleteAll(): EmptyResult<DataError.Local> {
        if (deleteAllResult is Result.Success) {
            ridesFlow.value = emptyList()
        }
        return deleteAllResult
    }

    fun emitRides(rides: List<RideRecord>) {
        ridesFlow.value = rides
    }
}
```

`observeAll` on the real repository is ordered `startTimestamp DESC` (see `RideHistoryDao.observeAll`); `save` mirrors that so list-order assertions match production.

- [ ] **Step 2: Write `FakeRideLapRepository` and `FakeRideTrackPointRepository`**

```kotlin
package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.tracking.LapRecord

class FakeRideLapRepository : RideLapRepository {
    private val stored = mutableMapOf<Long, List<LapRecord>>()

    var saveResult: EmptyResult<DataError.Local> = Result.Success(Unit)

    /** When non-null, overrides the stored lookup for every ride id. */
    var getResult: Result<List<LapRecord>, DataError.Local>? = null

    override suspend fun saveLaps(
        rideId: Long,
        laps: List<LapRecord>,
    ): EmptyResult<DataError.Local> {
        if (saveResult is Result.Success) {
            stored[rideId] = laps
        }
        return saveResult
    }

    override suspend fun getLaps(rideId: Long): Result<List<LapRecord>, DataError.Local> =
        getResult ?: Result.Success(stored[rideId].orEmpty())

    fun setLaps(
        rideId: Long,
        laps: List<LapRecord>,
    ) {
        stored[rideId] = laps
    }
}
```

```kotlin
package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository

class FakeRideTrackPointRepository : RideTrackPointRepository {
    private val stored = mutableMapOf<Long, List<RideTrackPoint>>()

    var saveResult: EmptyResult<DataError.Local> = Result.Success(Unit)
    var getResult: Result<List<RideTrackPoint>, DataError.Local>? = null

    override suspend fun savePoints(
        rideId: Long,
        points: List<RideTrackPoint>,
    ): EmptyResult<DataError.Local> {
        if (saveResult is Result.Success) {
            stored[rideId] = points
        }
        return saveResult
    }

    override suspend fun getPoints(rideId: Long): Result<List<RideTrackPoint>, DataError.Local> =
        getResult ?: Result.Success(stored[rideId].orEmpty())

    fun setPoints(
        rideId: Long,
        points: List<RideTrackPoint>,
    ) {
        stored[rideId] = points
    }
}
```

- [ ] **Step 3: Write `FakeLifetimeStatsRepository`**

```kotlin
package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.history.LifetimeStats
import com.speedevand.inkride.core.domain.history.LifetimeStatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeLifetimeStatsRepository(
    initial: LifetimeStats =
        LifetimeStats(
            totalRides = 0,
            totalDistanceKm = 0.0,
            totalMovingTimeSeconds = 0L,
            totalElevationGainM = 0.0,
            maxSpeedKmh = 0.0,
            totalCaloriesKcal = 0.0,
        ),
) : LifetimeStatsRepository {
    private val statsFlow = MutableStateFlow(initial)

    override fun observeLifetimeStats(): Flow<LifetimeStats> = statsFlow

    fun emit(stats: LifetimeStats) {
        statsFlow.value = stats
    }
}
```

Read `core/domain/src/main/java/com/speedevand/inkride/core/domain/history/LifetimeStats.kt` first and match its exact property names and types before compiling.

- [ ] **Step 4: Write the ride data builders**

Create `core/testing/src/main/java/com/speedevand/inkride/core/testing/support/TestRides.kt`:

```kotlin
package com.speedevand.inkride.core.testing.support

import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.tracking.LapRecord

object TestRides {
    /** A fixed wall-clock start so date assertions are reproducible. */
    const val START_MS = 1_750_000_000_000L

    fun record(
        id: Long = 1L,
        startTimestamp: Long = START_MS,
        endTimestamp: Long = START_MS + 3_600_000L,
        distanceKm: Double = 25.0,
        movingTimeSeconds: Long = 3_000L,
        elapsedTimeSeconds: Long = 3_600L,
        averageSpeedKmh: Double = 30.0,
        maxSpeedKmh: Double = 45.5,
        elevationGainM: Double = 320.0,
        caloriesKcal: Double = 780.0,
        averagePowerWatts: Int = 165,
    ): RideRecord =
        RideRecord(
            id = id,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            distanceKm = distanceKm,
            movingTimeSeconds = movingTimeSeconds,
            elapsedTimeSeconds = elapsedTimeSeconds,
            averageSpeedKmh = averageSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            elevationGainM = elevationGainM,
            caloriesKcal = caloriesKcal,
            averagePowerWatts = averagePowerWatts,
            bikeWeightKg = 9.0,
            bikeType = BikeType.ROAD,
        )

    fun laps(count: Int): List<LapRecord> =
        (1..count).map { n ->
            LapRecord(
                lapNumber = n,
                distanceKm = 5.0,
                movingTimeSeconds = 600L,
                averageSpeedKmh = 30.0,
                elevationGainM = 60.0,
            )
        }

    /** A straight northward track, one point per second. */
    fun trackPoints(count: Int): List<RideTrackPoint> =
        (0 until count).map { i ->
            RideTrackPoint(
                timestampMs = START_MS + i * 1_000L,
                latitude = 52.2297 + i * 0.0001,
                longitude = 21.0122,
                altitudeM = 100.0 + i,
                accuracyM = 5f,
            )
        }
}
```

- [ ] **Step 5: Point the history unit tests at the shared fakes**

Add to `feature/history/presentation/build.gradle.kts`:

```kotlin
    testImplementation(project(":core:testing"))
```

Delete the inline fake classes from `RideHistoryViewModelTest.kt` (`FakeRideHistoryRepository`, `FakeUserSettingsRepository`, `FakeTrackPointRepository`, `FakeLapRepository`) and from `RideDetailViewModelTest.kt` (`FakeRideHistoryRepository`, `FakeUserSettingsRepository`, `FakeRideTrackPointRepository`, `FakeRideLapRepository`), then import the shared ones:

```kotlin
import com.speedevand.inkride.core.testing.fakes.FakeLifetimeStatsRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideHistoryRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideLapRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideTrackPointRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
```

`FakeGpxExporter` stays where it is: `GpxExporter` is declared in `:feature:history:presentation`, so `:core:testing` cannot see it.

Rename call sites where the old inline names differed (`FakeTrackPointRepository` → `FakeRideTrackPointRepository`, `FakeLapRepository` → `FakeRideLapRepository`). Where a test seeded a fake through a constructor argument the shared fake does not have, switch to the `emitRides` / `setLaps` / `setPoints` seeding methods.

- [ ] **Step 6: Run the history unit tests**

Run: `./gradlew :feature:history:presentation:testDebugUnitTest`
Expected: PASS, same test count as before.

- [ ] **Step 7: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/testing feature/history/presentation
git commit -m "test: move history fakes and ride data builders into :core:testing"
```

---

### Task 4: Tracking and search fakes, consolidated from `:app`

**Files:**
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeRideSensorDataSource.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeBleSensorDataSource.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakePlaceSearchService.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeRoutingService.kt`
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/FakeCurrentLocationProvider.kt`
- Delete: `app/src/androidTest/java/com/speedevand/inkride/tracking/fakes/FakeRideSensorDataSource.kt`
- Delete: `app/src/androidTest/java/com/speedevand/inkride/tracking/fakes/FakeBleSensorDataSource.kt`
- Modify: `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingE2ETestBase.kt`
- Modify: `feature/dashboard/presentation/src/test/kotlin/.../DestinationSearchViewModelTest.kt`
- Modify: `feature/dashboard/presentation/build.gradle.kts`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `class FakeRideSensorDataSource : RideSensorDataSource` with `fun emit(sample: RideSensorSample)`, `var startResult: EmptyResult<SensorError>`, `val started: Boolean`
  - `class FakeBleSensorDataSource : BleSensorDataSource` with `fun emit(sample: BleSample)`, `val connectedHrm: String?`, `val connectedCadence: String?`, `val isConnected: Boolean`
  - `class FakePlaceSearchService : PlaceSearchService` with `var result: Result<List<PlaceResult>, PlaceSearchError>`, `val queries: List<String>`
  - `class FakeRoutingService : RoutingService` with `var result: Result<PlannedRoute, RoutingError>`, `val callCount: Int`
  - `class FakeCurrentLocationProvider : CurrentLocationProvider` with `var result: Result<LocationFix, LocationError>`

---

- [ ] **Step 1: Move the two sensor fakes verbatim**

Copy `app/src/androidTest/java/com/speedevand/inkride/tracking/fakes/FakeRideSensorDataSource.kt` and `FakeBleSensorDataSource.kt` into `core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/`, changing only the package declaration to `com.speedevand.inkride.core.testing.fakes`. Do not alter their behaviour: ten existing instrumented tests depend on exactly how they buffer and replay samples.

```bash
cd /Users/hubert/AndroidStudioProjects/InkRide
for f in FakeRideSensorDataSource FakeBleSensorDataSource; do
  sed 's/^package .*/package com.speedevand.inkride.core.testing.fakes/' \
    "app/src/androidTest/java/com/speedevand/inkride/tracking/fakes/$f.kt" \
    > "core/testing/src/main/java/com/speedevand/inkride/core/testing/fakes/$f.kt"
  git rm "app/src/androidTest/java/com/speedevand/inkride/tracking/fakes/$f.kt"
done
```

- [ ] **Step 2: Repoint `RideTrackingE2ETestBase`**

In `app/src/androidTest/java/com/speedevand/inkride/tracking/RideTrackingE2ETestBase.kt`, replace:

```kotlin
import com.speedevand.inkride.tracking.fakes.FakeBleSensorDataSource
import com.speedevand.inkride.tracking.fakes.FakeRideSensorDataSource
```

with:

```kotlin
import com.speedevand.inkride.core.testing.fakes.FakeBleSensorDataSource
import com.speedevand.inkride.core.testing.fakes.FakeRideSensorDataSource
```

- [ ] **Step 3: Write `FakePlaceSearchService`, `FakeRoutingService`, `FakeCurrentLocationProvider`**

```kotlin
package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService

class FakePlaceSearchService : PlaceSearchService {
    private val _queries = mutableListOf<String>()

    /** Every query the ViewModel actually sent, in order — lets a test prove
     *  that a too-short query never reached the service at all. */
    val queries: List<String> get() = _queries

    var result: Result<List<PlaceResult>, PlaceSearchError> =
        Result.Success(
            listOf(PlaceResult(displayName = "Warsaw, Poland", latitude = 52.2297, longitude = 21.0122)),
        )

    override suspend fun search(query: String): Result<List<PlaceResult>, PlaceSearchError> {
        _queries += query
        return result
    }
}
```

```kotlin
package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.domain.tracking.RoutingService
import com.speedevand.inkride.core.domain.tracking.PlannedRoute

class FakeRoutingService(
    defaultRoute: PlannedRoute = TestRoutes.straightLine(),
) : RoutingService {
    var callCount: Int = 0
        private set

    var result: Result<PlannedRoute, RoutingError> = Result.Success(defaultRoute)

    override suspend fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<PlannedRoute, RoutingError> {
        callCount++
        return result
    }
}
```

`TestRoutes.straightLine()` must exist before this compiles. `PlannedRoute` carries route geometry specific to the routing feature, so read `core/domain/src/main/java/com/speedevand/inkride/core/domain/tracking/PlannedRoute.kt` and write `core/testing/src/main/java/com/speedevand/inkride/core/testing/support/TestRoutes.kt` to match its real shape — a short straight-line route with at least two geometry points and one turn instruction, so `RouteFollower` has something to follow. The defaulted constructor parameter is what lets every consumer write `FakeRoutingService()` and override only `result`.

Also add the import `com.speedevand.inkride.core.testing.support.TestRoutes` to `FakeRoutingService.kt`.

```kotlin
package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.LocationFix

class FakeCurrentLocationProvider : CurrentLocationProvider {
    var result: Result<LocationFix, LocationError> =
        Result.Success(LocationFix(latitude = 52.2297, longitude = 21.0122))

    override suspend fun getCurrentLocation(): Result<LocationFix, LocationError> = result
}
```

- [ ] **Step 4: Point the dashboard unit test at the shared fakes**

Add to `feature/dashboard/presentation/build.gradle.kts`:

```kotlin
    testImplementation(project(":core:testing"))
```

Delete the three inline fakes from `DestinationSearchViewModelTest.kt` and import:

```kotlin
import com.speedevand.inkride.core.testing.fakes.FakeCurrentLocationProvider
import com.speedevand.inkride.core.testing.fakes.FakePlaceSearchService
import com.speedevand.inkride.core.testing.fakes.FakeRoutingService
```

Adjust the test's seeding calls to the `var result` knobs above.

- [ ] **Step 5: Run the dashboard unit tests**

Run: `./gradlew :feature:dashboard:presentation:testDebugUnitTest`
Expected: PASS, same test count as before.

- [ ] **Step 6: Run the full instrumented suite**

Run (emulator running): `./gradlew :app:connectedDebugAndroidTest`
Expected: PASS — all 13 tests. This is the gate proving the sensor fakes survived the move intact.

- [ ] **Step 7: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/testing app feature/dashboard/presentation
git commit -m "test: move sensor and search fakes into :core:testing"
```

---

### Task 5: `KoinTestRule` and first module-level instrumented test

This task proves the whole foundation works end to end: a feature module's `androidTest` starting its own Koin container, rendering a `*Root` composable with fakes behind it, and asserting on real rendered output. Plans 2–5 all copy this shape.

**Files:**
- Create: `core/testing/src/main/java/com/speedevand/inkride/core/testing/rules/KoinTestRule.kt`
- Create: `feature/settings/presentation/src/androidTest/kotlin/com/speedevand/inkride/settings/presentation/SettingsRootSmokeTest.kt`

**Interfaces:**
- Consumes: `FakeUserSettingsRepository`, `FakeBikeProfileRepository` (Task 2); `stringRes` (Task 1).
- Produces: `class KoinTestRule(modules: List<Module>) : ExternalResource` in `com.speedevand.inkride.core.testing.rules`.

---

- [ ] **Step 1: Write the failing smoke test**

Create `feature/settings/presentation/src/androidTest/kotlin/com/speedevand/inkride/settings/presentation/SettingsRootSmokeTest.kt`:

```kotlin
package com.speedevand.inkride.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.fakes.FakeBikeProfileRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import com.speedevand.inkride.core.testing.rules.KoinTestRule
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.stringRes
import org.junit.Rule
import org.junit.Test
import org.koin.dsl.module

/**
 * Proves the module-level instrumented setup works: a Koin container started by
 * the test, fakes bound in place of the real repositories, and a `*Root`
 * composable resolving its ViewModel through `koinViewModel()`.
 */
class SettingsRootSmokeTest {
    private val settingsRepository = FakeUserSettingsRepository(TestSettings.default())
    private val bikeProfileRepository = FakeBikeProfileRepository()

    @get:Rule(order = 0)
    val koinRule =
        KoinTestRule(
            modules =
                listOf(
                    settingsPresentationModule,
                    module {
                        single<UserSettingsRepository> { settingsRepository }
                        single<BikeProfileRepository> { bikeProfileRepository }
                    },
                ),
        )

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsRootRendersItsTitle() {
        composeTestRule.setContent {
            SettingsRoot(
                onBack = {},
                onOpenBleSensors = {},
                onOpenBikeProfiles = {},
            )
        }

        composeTestRule
            .onNodeWithText(stringRes(R.string.settings_title))
            .assertIsDisplayed()
    }
}
```

Rule order matters: Koin must be running before the Compose rule launches the activity whose composition resolves `koinViewModel()`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :feature:settings:presentation:connectedDebugAndroidTest`
Expected: FAIL to compile — `Unresolved reference: KoinTestRule`.

- [ ] **Step 3: Write `KoinTestRule`**

Create `core/testing/src/main/java/com/speedevand/inkride/core/testing/rules/KoinTestRule.kt`:

```kotlin
package com.speedevand.inkride.core.testing.rules

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module

/**
 * Starts a Koin container for one test class and tears it down afterwards, so
 * a library module's `androidTest` can resolve `koinViewModel()` without an
 * Application class of its own.
 *
 * Pass the module under test's real `*PresentationModule` plus a module binding
 * fakes over its repositories — a later definition wins, so the fakes override
 * the production bindings.
 *
 * Declare this with `@get:Rule(order = 0)`, before the Compose rule: the
 * container must exist before the activity's composition resolves a ViewModel.
 */
class KoinTestRule(
    private val modules: List<Module>,
) : ExternalResource() {
    override fun before() {
        // Defensive: a test class that crashed mid-run can leave a container
        // standing, and startKoin would then throw for every later class.
        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        startKoin {
            androidContext(ApplicationProvider.getApplicationContext<Application>())
            modules(this@KoinTestRule.modules)
        }
    }

    override fun after() {
        stopKoin()
    }
}
```

- [ ] **Step 4: Run the smoke test again**

Run: `./gradlew :feature:settings:presentation:connectedDebugAndroidTest`
Expected: PASS.

If it fails with a Koin "already started" error, a previous run left a container behind — that is what the `getOrNull()` guard handles, so re-check the guard is present. If it fails with "Could not find ComponentActivity", the `debugImplementation(ui-test-manifest)` line from Task 1 Step 10 is missing.

- [ ] **Step 5: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add core/testing feature/settings/presentation
git commit -m "test: add KoinTestRule and first module-level instrumented test"
```

---

### Task 6: Wire the new modules into CI

**Files:**
- Modify: `.github/workflows/ci.yml:38` (the `timeout-minutes` of the `instrumented` job) and `:80-87` (the run step)

**Interfaces:**
- Consumes: nothing.
- Produces: a CI job that runs `connectedDebugAndroidTest` across every module.

---

- [ ] **Step 1: Raise the instrumented job timeout**

In `.github/workflows/ci.yml`, in the instrumented job, change:

```yaml
    timeout-minutes: 30
```

to:

```yaml
    # Six test APKs (:app, four :feature:*:presentation modules, :core:database)
    # rather than one, so install and Gradle overhead grew.
    timeout-minutes: 60
```

- [ ] **Step 2: Parallelize the run**

In the "Run instrumented tests" step, change:

```yaml
          script: ./gradlew connectedDebugAndroidTest --no-daemon
```

to:

```yaml
          script: ./gradlew connectedDebugAndroidTest --no-daemon --parallel
```

The unqualified `connectedDebugAndroidTest` already fans out to every module, so no module list needs maintaining.

- [ ] **Step 3: Verify locally that the fan-out reaches the new modules**

Run: `./gradlew connectedDebugAndroidTest --dry-run | grep -c connectedDebugAndroidTest`
Expected: the task list includes `:core:testing`, `:feature:settings:presentation` and `:app` entries. `:core:testing` has no tests of its own and will report as up-to-date — that is fine.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: raise instrumented job timeout and parallelize for multi-module tests"
```

---

## Done when

- `./gradlew testDebugUnitTest` is green, with no test lost during fake consolidation.
- `./gradlew :app:connectedDebugAndroidTest` is green — all 13 pre-existing tests.
- `./gradlew :feature:settings:presentation:connectedDebugAndroidTest` is green — the smoke test.
- `./gradlew ktlintCheck` is green.
- `FakeUserSettingsRepository` exists exactly once in the repository.
