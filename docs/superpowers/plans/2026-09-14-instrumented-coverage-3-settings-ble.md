# Instrumented Coverage, Plan 3: Settings and BLE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cover every path through `SettingsScreen`, `BikeProfilesScreen` and `BleSensorsScreen` with instrumented tests.

**Architecture:** Each test renders the screen's `*Root` composable inside `createAndroidComposeRule<ComponentActivity>()`, with a `KoinTestRule` binding fake repositories over the module's real `*PresentationModule`. Assertions go through test tags on the reusable row composables, because these screens render the same button labels several times (three `+` steppers on one tab, two "Scan" buttons on the BLE screen) and text selectors would be ambiguous.

**Tech Stack:** Compose `ui-test-junit4`, Koin 4.2.1, JUnit4 + `AndroidJUnit4`, assertk, MMD components (`SwitchMMD`, `TextFieldMMD`, `RadioButtonMMD`, `TabMMD`).

**Spec:** `docs/superpowers/specs/2026-09-14-full-instrumented-test-coverage-design.md`

**Depends on:** Plan 1 in full — `KoinTestRule`, `FakeUserSettingsRepository`, `FakeBikeProfileRepository`, `FakeBleScanner`, `TestSettings`, `TestBikeProfiles`, `TestBleDevices`, `stringRes`, and the shared `androidTest` dependency set on `AndroidFeatureConventionPlugin`.

## Global Constraints

- Kotlin JVM target 11; `compileSdk = 36`, `minSdk = 26`.
- `./gradlew ktlintCheck` must pass; run `./gradlew ktlintFormat` before every commit, including `androidTest` sources.
- Use MMD components; never introduce a raw Material component into production code while adding tags.
- Test tags go on leaf interactive or data-carrying nodes, and localized text is resolved with `stringRes(R.string.…)` — never a hardcoded English literal.
- Instrumented tests make no network requests and touch no real BLE hardware.
- Rule order is fixed: `@get:Rule(order = 0)` for `KoinTestRule`, `@get:Rule(order = 1)` for the Compose rule. Koin must be running before the activity's composition resolves `koinViewModel()`.
- `SettingsScreen` edits numbers through `NumberStepperRow` / `AlertSwitchRow` steppers (a `−` button, a value, a `+` button), not text fields. There is no reachable "non-numeric input" path; the equivalent coverage is clamping at `min`/`max`. `BikeProfilesScreen` is the one screen with real `TextFieldMMD` inputs and therefore the one with validation-error paths.

---

### Task 1: Test tags for `SettingsScreen`

**Files:**
- Create: `feature/settings/presentation/src/main/java/com/speedevand/inkride/settings/presentation/SettingsTestTags.kt`
- Modify: `feature/settings/presentation/src/main/java/com/speedevand/inkride/settings/presentation/SettingsScreen.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object SettingsTestTags` with `fun tab(tab: SettingsTab): String`, `fun stepperValue(key: String): String`, `fun stepperMinus(key: String): String`, `fun stepperPlus(key: String): String`, `fun switch(key: String): String`, `fun radio(key: String): String`, `fun navRow(key: String): String`, and the key constants `WEIGHT`, `AGE`, `BIKE_WEIGHT`, `ALERT_MAX_SPEED`, `ALERT_HR_MIN`, `ALERT_HR_MAX`, `KEEP_SCREEN_ON`, `BIKE_PROFILES`, `BLE_SENSORS`.

---

- [ ] **Step 1: Write the tag object**

Create `feature/settings/presentation/src/main/java/com/speedevand/inkride/settings/presentation/SettingsTestTags.kt`:

```kotlin
package com.speedevand.inkride.settings.presentation

/**
 * Stable identifiers for Compose UI tests. The settings screen renders the same
 * row composables many times over (three steppers on one tab, ten switches on
 * another), so every reusable row takes a `key` and tags its interactive leaves
 * with it — text selectors alone would be ambiguous.
 */
object SettingsTestTags {
    const val WEIGHT = "weight"
    const val AGE = "age"
    const val BIKE_WEIGHT = "bike_weight"
    const val ALERT_MAX_SPEED = "alert_max_speed"
    const val ALERT_HR_MIN = "alert_hr_min"
    const val ALERT_HR_MAX = "alert_hr_max"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val BIKE_PROFILES = "bike_profiles"
    const val BLE_SENSORS = "ble_sensors"

    fun tab(tab: SettingsTab): String = "settings_tab_${tab.name}"

    fun stepperValue(key: String): String = "settings_stepper_value_$key"

    fun stepperMinus(key: String): String = "settings_stepper_minus_$key"

    fun stepperPlus(key: String): String = "settings_stepper_plus_$key"

    fun switch(key: String): String = "settings_switch_$key"

    fun radio(key: String): String = "settings_radio_$key"

    fun navRow(key: String): String = "settings_nav_row_$key"
}
```

- [ ] **Step 2: Thread a `key` through the reusable row composables**

In `SettingsScreen.kt`, add a `key: String` parameter to `SettingNavRow`, `SettingRadioRow`, `DashboardSettingRow`, `NumberStepperRow` and `AlertSwitchRow`, and tag their leaves. Add `import androidx.compose.ui.platform.testTag` if absent.

`SettingNavRow` — tag the clickable `Row`:

```kotlin
@Composable
private fun SettingNavRow(
    key: String,
    label: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(SettingsTestTags.navRow(key))
                .clickable(onClick = onClick)
                .heightIn(min = 52.dp)
                .padding(vertical = 8.dp),
```

`SettingRadioRow` — tag its clickable row with `SettingsTestTags.radio(key)`.

`DashboardSettingRow` — tag the `SwitchMMD`:

```kotlin
        SwitchMMD(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(SettingsTestTags.switch(key)),
        )
```

`NumberStepperRow` — tag the `−` button `SettingsTestTags.stepperMinus(key)`, the value `TextMMD` `SettingsTestTags.stepperValue(key)`, and the `+` button `SettingsTestTags.stepperPlus(key)`.

`AlertSwitchRow` — tag its `SwitchMMD` with `SettingsTestTags.switch(key)` and, inside the `if (enabled)` block, tag the `−`/value/`+` leaves with the same three stepper helpers.

- [ ] **Step 3: Pass keys at every call site**

`ProfileSection`: `key = SettingsTestTags.WEIGHT` on the weight stepper, `SettingsTestTags.AGE` on the age stepper, `key = code` on the two language radio rows (so they tag as `settings_radio_en` / `settings_radio_pl`).

`BikeSection`: `key = SettingsTestTags.BIKE_PROFILES` and `key = SettingsTestTags.BLE_SENSORS` on the two nav rows, `key = SettingsTestTags.BIKE_WEIGHT` on the bike-weight stepper, `key = type.name` on the bike-type radio rows.

`DisplaySection`: `key = unit.name` on the unit radio rows; on each of the ten metric switches use a stable snake_case key matching its setting — `show_distance`, `show_moving_time`, `show_average_speed`, `show_max_speed`, `show_elevation_gain`, `show_calories`, `show_altitude`, `show_grade`, `show_power`, `show_compass` — and `key = SettingsTestTags.KEEP_SCREEN_ON` on the keep-screen-on switch.

`AlertsSection`: `key = SettingsTestTags.ALERT_MAX_SPEED`, `ALERT_HR_MIN`, `ALERT_HR_MAX` on the three alert rows.

- [ ] **Step 4: Tag the tabs**

In the `SettingsTab.entries.forEach` loop, add to the `TabMMD` call:

```kotlin
                        modifier = Modifier.testTag(SettingsTestTags.tab(tab)),
```

- [ ] **Step 5: Verify the module still builds and its unit tests pass**

```bash
./gradlew :feature:settings:presentation:assembleDebug :feature:settings:presentation:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, tests PASS. Adding tags changes no behaviour, so a failure here means a call site was missed.

- [ ] **Step 6: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/settings/presentation/src/main
git commit -m "feat: add test tags to settings rows"
```

---

### Task 2: `SettingsScreen` profile and navigation tests

**Files:**
- Create: `feature/settings/presentation/src/androidTest/kotlin/com/speedevand/inkride/settings/presentation/SettingsTestHarness.kt`
- Create: `feature/settings/presentation/src/androidTest/kotlin/com/speedevand/inkride/settings/presentation/SettingsScreenProfileTest.kt`
- Delete: `feature/settings/presentation/src/androidTest/kotlin/com/speedevand/inkride/settings/presentation/SettingsRootSmokeTest.kt`

**Interfaces:**
- Consumes: `KoinTestRule`, `FakeUserSettingsRepository`, `FakeBikeProfileRepository`, `TestSettings`, `stringRes` (Plan 1); `SettingsTestTags` (Task 1).
- Produces: `abstract class SettingsTestHarness` exposing `val settingsRepository: FakeUserSettingsRepository`, `val bikeProfileRepository: FakeBikeProfileRepository`, `val composeTestRule`, `fun setSettingsContent(onBack: () -> Unit = {}, onOpenBleSensors: () -> Unit = {}, onOpenBikeProfiles: () -> Unit = {})`, `fun selectTab(tab: SettingsTab)`, and `fun awaitSaved(predicate: (UserSettings) -> Boolean)`.

---

- [ ] **Step 1: Write the harness**

The smoke test from Plan 1 Task 5 is superseded by this harness; delete it in Step 6.

```kotlin
package com.speedevand.inkride.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.speedevand.inkride.core.domain.settings.BikeProfile
import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.fakes.FakeBikeProfileRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import com.speedevand.inkride.core.testing.rules.KoinTestRule
import com.speedevand.inkride.core.testing.support.TestSettings
import org.junit.Rule
import org.koin.dsl.module

/**
 * Shared setup for the settings instrumented tests: a Koin container with the
 * real `settingsPresentationModule` plus fakes bound over its two repositories,
 * and a Compose rule hosting the `*Root` composable.
 *
 * Subclasses override [initialSettings] / [initialProfiles] to seed state
 * before the screen is first composed.
 */
abstract class SettingsTestHarness {
    protected open fun initialSettings(): UserSettings = TestSettings.default()

    protected open fun initialProfiles(): List<BikeProfile> = emptyList()

    val settingsRepository by lazy { FakeUserSettingsRepository(initialSettings()) }
    val bikeProfileRepository by lazy { FakeBikeProfileRepository(initialProfiles()) }

    @get:Rule(order = 0)
    val koinRule by lazy {
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
    }

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    fun setSettingsContent(
        onBack: () -> Unit = {},
        onOpenBleSensors: () -> Unit = {},
        onOpenBikeProfiles: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            SettingsRoot(
                onBack = onBack,
                onOpenBleSensors = onOpenBleSensors,
                onOpenBikeProfiles = onOpenBikeProfiles,
            )
        }
    }

    fun selectTab(tab: SettingsTab) {
        composeTestRule.onNodeWithTag(SettingsTestTags.tab(tab)).performClick()
        composeTestRule.waitForIdle()
    }

    /**
     * Waits for a write to land in the repository. Saves travel through the
     * ViewModel's coroutine scope, so they are not visible the instant a click
     * returns.
     */
    fun awaitSaved(predicate: (UserSettings) -> Boolean) {
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.let(predicate) == true
        }
    }
}
```

- [ ] **Step 2: Write the profile and navigation tests**

```kotlin
package com.speedevand.inkride.settings.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.stringRes
import com.speedevand.inkride.core.testing.support.textOf
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenProfileTest : SettingsTestHarness() {
    @Test
    fun profileTabIsSelectedFirstAndShowsThePersonalSection() {
        setSettingsContent()

        composeTestRule
            .onNodeWithText(stringRes(R.string.settings_section_personal))
            .assertIsDisplayed()
    }

    @Test
    fun switchingTabsSwapsTheSectionOnScreen() {
        setSettingsContent()

        selectTab(SettingsTab.BIKE)
        composeTestRule
            .onNodeWithText(stringRes(R.string.settings_section_bike))
            .assertIsDisplayed()

        selectTab(SettingsTab.DISPLAY)
        composeTestRule
            .onNodeWithText(stringRes(R.string.settings_section_units))
            .assertIsDisplayed()

        selectTab(SettingsTab.PROFILE)
        composeTestRule
            .onNodeWithText(stringRes(R.string.settings_section_personal))
            .assertIsDisplayed()
    }

    @Test
    fun steppingWeightUpPersistsTheNewValue() {
        setSettingsContent()

        composeTestRule.onNodeWithTag(SettingsTestTags.stepperPlus(SettingsTestTags.WEIGHT)).performClick()

        awaitSaved { it.weightKg == 76 }
        assertThat(settingsRepository.lastSaved?.weightKg).isEqualTo(76)
    }

    @Test
    fun steppingAgeDownPersistsTheNewValue() {
        setSettingsContent()

        composeTestRule.onNodeWithTag(SettingsTestTags.stepperMinus(SettingsTestTags.AGE)).performClick()

        awaitSaved { it.age == 29 }
        assertThat(settingsRepository.lastSaved?.age).isEqualTo(29)
    }

    @Test
    fun theWeightStepperClampsAtItsMaximum() {
        settingsRepository.emitSettings(
            TestSettings.default().copy(weightKg = SettingsConstants.WEIGHT_MAX_KG),
        )
        setSettingsContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SettingsTestTags.stepperPlus(SettingsTestTags.WEIGHT)).performClick()
        composeTestRule.waitForIdle()

        assertThat(
            composeTestRule.textOf(SettingsTestTags.stepperValue(SettingsTestTags.WEIGHT)),
        ).isEqualTo("${SettingsConstants.WEIGHT_MAX_KG} kg")
    }

    @Test
    fun choosingALanguagePersistsIt() {
        setSettingsContent()

        composeTestRule.onNodeWithTag(SettingsTestTags.radio("pl")).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.languageCode == "pl"
        }
        assertThat(settingsRepository.lastSaved?.languageCode).isEqualTo("pl")
    }

    @Test
    fun imperialUnitsRenderWeightInPounds() {
        settingsRepository.emitSettings(TestSettings.default(units = MeasurementUnits.IMPERIAL))
        setSettingsContent()
        composeTestRule.waitForIdle()

        assertThat(
            composeTestRule
                .textOf(SettingsTestTags.stepperValue(SettingsTestTags.WEIGHT))
                .endsWith("lbs"),
        ).isTrue()
    }

    @Test
    fun theBikeProfilesRowInvokesItsNavigationCallback() {
        var opened = false
        setSettingsContent(onOpenBikeProfiles = { opened = true })

        selectTab(SettingsTab.BIKE)
        composeTestRule.onNodeWithTag(SettingsTestTags.navRow(SettingsTestTags.BIKE_PROFILES)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { opened }
        assertThat(opened).isTrue()
    }

    @Test
    fun theBluetoothSensorsRowInvokesItsNavigationCallback() {
        var opened = false
        setSettingsContent(onOpenBleSensors = { opened = true })

        selectTab(SettingsTab.BIKE)
        composeTestRule.onNodeWithTag(SettingsTestTags.navRow(SettingsTestTags.BLE_SENSORS)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { opened }
        assertThat(opened).isTrue()
    }

    @Test
    fun theDefaultBikeSectionIsHiddenWhileABikeProfileIsActive() {
        settingsRepository.emitSettings(TestSettings.default(activeBikeProfileId = 1L))
        setSettingsContent()

        selectTab(SettingsTab.BIKE)

        composeTestRule
            .onNodeWithTag(SettingsTestTags.stepperValue(SettingsTestTags.BIKE_WEIGHT))
            .assertIsNotDisplayed()
    }

    @Test
    fun steppingBikeWeightPersistsTheNewValue() {
        setSettingsContent()

        selectTab(SettingsTab.BIKE)
        composeTestRule.onNodeWithTag(SettingsTestTags.stepperPlus(SettingsTestTags.BIKE_WEIGHT)).performClick()

        awaitSaved { it.bikeWeightKg > 10.0 }
        assertThat(settingsRepository.lastSaved?.bikeWeightKg).isEqualTo(10.5)
    }
}
```

Open `SettingsConstants.kt` before running and confirm `WEIGHT_MAX_KG` and `AGE_MIN` exist with those names; adjust the clamp test if they differ.

- [ ] **Step 3: Run the class**

Run: `./gradlew :feature:settings:presentation:connectedDebugAndroidTest --tests "*SettingsScreenProfileTest"`
Expected: PASS, 11 tests.

- [ ] **Step 4: Delete the superseded smoke test**

```bash
git rm feature/settings/presentation/src/androidTest/kotlin/com/speedevand/inkride/settings/presentation/SettingsRootSmokeTest.kt
```

- [ ] **Step 5: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/settings/presentation/src/androidTest
git commit -m "test: add instrumented settings profile and navigation tests"
```

---

### Task 3: `SettingsScreen` display and alert tests

**Files:**
- Create: `feature/settings/presentation/src/androidTest/kotlin/com/speedevand/inkride/settings/presentation/SettingsScreenDisplayTest.kt`
- Create: `feature/settings/presentation/src/androidTest/kotlin/com/speedevand/inkride/settings/presentation/SettingsScreenAlertsTest.kt`

**Interfaces:**
- Consumes: `SettingsTestHarness` (Task 2), `SettingsTestTags` (Task 1).
- Produces: nothing other tasks depend on.

---

- [ ] **Step 1: Write the display tests**

The ten metric switches are covered by one table-driven test rather than ten near-identical ones — the table is the test's content, so nothing is hidden.

```kotlin
package com.speedevand.inkride.settings.presentation

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettings
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenDisplayTest : SettingsTestHarness() {
    private data class MetricSwitch(
        val key: String,
        val read: (UserSettings) -> Boolean,
    )

    private val metricSwitches =
        listOf(
            MetricSwitch("show_distance") { it.showDistance },
            MetricSwitch("show_moving_time") { it.showMovingTime },
            MetricSwitch("show_average_speed") { it.showAverageSpeed },
            MetricSwitch("show_max_speed") { it.showMaxSpeed },
            MetricSwitch("show_elevation_gain") { it.showElevationGain },
            MetricSwitch("show_calories") { it.showCalories },
            MetricSwitch("show_altitude") { it.showAltitude },
            MetricSwitch("show_grade") { it.showGrade },
            MetricSwitch("show_power") { it.showPower },
            MetricSwitch("show_compass") { it.showCompass },
        )

    @Test
    fun everyMetricVisibilitySwitchPersistsWhenTurnedOff() {
        setSettingsContent()
        selectTab(SettingsTab.DISPLAY)

        metricSwitches.forEach { metric ->
            composeTestRule.onNodeWithTag(SettingsTestTags.switch(metric.key)).performClick()
            composeTestRule.waitUntil(timeoutMillis = 5_000L) {
                settingsRepository.lastSaved?.let { !metric.read(it) } == true
            }
            assertThat(metric.read(settingsRepository.lastSaved!!)).isFalse()
        }
    }

    @Test
    fun switchingToImperialUnitsPersists() {
        setSettingsContent()
        selectTab(SettingsTab.DISPLAY)

        composeTestRule.onNodeWithTag(SettingsTestTags.radio(MeasurementUnits.IMPERIAL.name)).performClick()

        awaitSaved { it.units == MeasurementUnits.IMPERIAL }
        assertThat(settingsRepository.lastSaved?.units).isEqualTo(MeasurementUnits.IMPERIAL)
    }

    @Test
    fun switchingBackToMetricUnitsPersists() {
        settingsRepository.emitSettings(
            com.speedevand.inkride.core.testing.support.TestSettings
                .default(units = MeasurementUnits.IMPERIAL),
        )
        setSettingsContent()
        selectTab(SettingsTab.DISPLAY)

        composeTestRule.onNodeWithTag(SettingsTestTags.radio(MeasurementUnits.METRIC.name)).performClick()

        awaitSaved { it.units == MeasurementUnits.METRIC }
        assertThat(settingsRepository.lastSaved?.units).isEqualTo(MeasurementUnits.METRIC)
    }

    @Test
    fun keepScreenOnPersists() {
        setSettingsContent()
        selectTab(SettingsTab.DISPLAY)

        composeTestRule.onNodeWithTag(SettingsTestTags.switch(SettingsTestTags.KEEP_SCREEN_ON)).performClick()

        awaitSaved { !it.keepScreenOn }
        assertThat(settingsRepository.lastSaved?.keepScreenOn).isFalse()
    }
}
```

- [ ] **Step 2: Write the alert tests**

The alert stepper only exists while its switch is on, so "the value is ignored while the alert is off" is asserted as "the stepper is not in the tree".

```kotlin
package com.speedevand.inkride.settings.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.speedevand.inkride.core.testing.support.stringRes
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenAlertsTest : SettingsTestHarness() {
    private fun openAlerts() {
        setSettingsContent()
        selectTab(SettingsTab.DISPLAY)
        composeTestRule
            .onNodeWithText(stringRes(R.string.settings_section_alerts))
            .assertIsDisplayed()
    }

    @Test
    fun theSpeedAlertStepperIsHiddenUntilTheAlertIsEnabled() {
        openAlerts()

        composeTestRule
            .onAllNodesWithTag(SettingsTestTags.stepperValue(SettingsTestTags.ALERT_MAX_SPEED))
            .assertCountEquals(0)
    }

    @Test
    fun enablingTheSpeedAlertPersistsAThresholdAndRevealsItsStepper() {
        openAlerts()

        composeTestRule.onNodeWithTag(SettingsTestTags.switch(SettingsTestTags.ALERT_MAX_SPEED)).performClick()

        awaitSaved { it.alerts.maxSpeedKmh != null }
        assertThat(settingsRepository.lastSaved?.alerts?.maxSpeedKmh).isNotNull()
        composeTestRule
            .onNodeWithTag(SettingsTestTags.stepperValue(SettingsTestTags.ALERT_MAX_SPEED))
            .assertIsDisplayed()
    }

    @Test
    fun steppingTheSpeedAlertThresholdPersists() {
        openAlerts()
        composeTestRule.onNodeWithTag(SettingsTestTags.switch(SettingsTestTags.ALERT_MAX_SPEED)).performClick()
        awaitSaved { it.alerts.maxSpeedKmh != null }
        val before = settingsRepository.lastSaved!!.alerts.maxSpeedKmh!!

        composeTestRule.onNodeWithTag(SettingsTestTags.stepperPlus(SettingsTestTags.ALERT_MAX_SPEED)).performClick()

        awaitSaved { (it.alerts.maxSpeedKmh ?: before) > before }
    }

    @Test
    fun disablingTheSpeedAlertClearsItsThreshold() {
        openAlerts()
        composeTestRule.onNodeWithTag(SettingsTestTags.switch(SettingsTestTags.ALERT_MAX_SPEED)).performClick()
        awaitSaved { it.alerts.maxSpeedKmh != null }

        composeTestRule.onNodeWithTag(SettingsTestTags.switch(SettingsTestTags.ALERT_MAX_SPEED)).performClick()

        awaitSaved { it.alerts.maxSpeedKmh == null }
        assertThat(settingsRepository.lastSaved?.alerts?.maxSpeedKmh).isNull()
    }

    @Test
    fun theMinimumHeartRateAlertPersistsIndependentlyOfTheMaximum() {
        openAlerts()

        composeTestRule.onNodeWithTag(SettingsTestTags.switch(SettingsTestTags.ALERT_HR_MIN)).performClick()

        awaitSaved { it.alerts.hrZoneMinBpm != null }
        assertThat(settingsRepository.lastSaved?.alerts?.hrZoneMinBpm).isNotNull()
        assertThat(settingsRepository.lastSaved?.alerts?.hrZoneMaxBpm).isNull()
    }

    @Test
    fun theMaximumHeartRateAlertPersistsIndependentlyOfTheMinimum() {
        openAlerts()

        composeTestRule.onNodeWithTag(SettingsTestTags.switch(SettingsTestTags.ALERT_HR_MAX)).performClick()

        awaitSaved { it.alerts.hrZoneMaxBpm != null }
        assertThat(settingsRepository.lastSaved?.alerts?.hrZoneMaxBpm).isNotNull()
        assertThat(settingsRepository.lastSaved?.alerts?.hrZoneMinBpm).isNull()
    }
}
```

Add the imports `androidx.compose.ui.test.assertCountEquals`, `androidx.compose.ui.test.onAllNodesWithTag` and `androidx.compose.ui.test.onNodeWithText`. Read `AlertConfig` in `core/domain/src/main/java/com/speedevand/inkride/core/domain/settings/` and correct the property names `maxSpeedKmh` / `hrZoneMinBpm` / `hrZoneMaxBpm` if they differ.

- [ ] **Step 3: Run both classes**

Run: `./gradlew :feature:settings:presentation:connectedDebugAndroidTest --tests "*SettingsScreenDisplayTest" --tests "*SettingsScreenAlertsTest"`
Expected: PASS, 10 tests.

- [ ] **Step 4: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/settings/presentation/src/androidTest
git commit -m "test: add instrumented settings display and alert tests"
```

---

### Task 4: `BikeProfilesScreen` tags and tests

**Files:**
- Create: `feature/settings/presentation/src/main/java/com/speedevand/inkride/settings/presentation/BikeProfilesTestTags.kt`
- Modify: `feature/settings/presentation/src/main/java/com/speedevand/inkride/settings/presentation/BikeProfilesScreen.kt`
- Create: `feature/settings/presentation/src/androidTest/kotlin/com/speedevand/inkride/settings/presentation/BikeProfilesScreenTest.kt`

**Interfaces:**
- Consumes: `KoinTestRule`, `FakeBikeProfileRepository`, `FakeUserSettingsRepository`, `TestBikeProfiles`, `TestSettings` (Plan 1).
- Produces: `object BikeProfilesTestTags` with `const val ADD_BUTTON`, `NAME_FIELD`, `WEIGHT_FIELD`, `SAVE_BUTTON`, `CANCEL_BUTTON`, `EMPTY_STATE`, `BACK_BUTTON`, and `fun row(id: Long)`, `fun activeRadio(id: Long)`, `fun editButton(id: Long)`, `fun deleteButton(id: Long)`, `fun typeRadio(type: BikeType)`.

---

- [ ] **Step 1: Write the tag object**

```kotlin
package com.speedevand.inkride.settings.presentation

import com.speedevand.inkride.core.domain.settings.BikeType

/**
 * Stable identifiers for the bike-profile list and its add/edit form. Rows are
 * tagged by profile id, because every row renders the same "Edit"/"Delete"
 * labels and a text selector could not tell them apart.
 */
object BikeProfilesTestTags {
    const val ADD_BUTTON = "bike_profiles_add"
    const val NAME_FIELD = "bike_profiles_name_field"
    const val WEIGHT_FIELD = "bike_profiles_weight_field"
    const val SAVE_BUTTON = "bike_profiles_save"
    const val CANCEL_BUTTON = "bike_profiles_cancel"
    const val EMPTY_STATE = "bike_profiles_empty"
    const val BACK_BUTTON = "bike_profiles_back"

    fun row(id: Long): String = "bike_profile_row_$id"

    fun activeRadio(id: Long): String = "bike_profile_active_$id"

    fun editButton(id: Long): String = "bike_profile_edit_$id"

    fun deleteButton(id: Long): String = "bike_profile_delete_$id"

    fun typeRadio(type: BikeType): String = "bike_profile_type_${type.name}"
}
```

- [ ] **Step 2: Apply the tags**

In `BikeProfilesScreen.kt`, add `Modifier.testTag(...)` to: the back `IconButton` (`BACK_BUTTON`); the empty-state `TextMMD` (`EMPTY_STATE`); the add `ButtonMMD` (`ADD_BUTTON`); in the profile-row composable, the row container (`row(profile.id)`), the `RadioButtonMMD` (`activeRadio(profile.id)`), and the edit and delete `OutlinedButtonMMD`s (`editButton(profile.id)`, `deleteButton(profile.id)`); in the form, the name and weight `TextFieldMMD`s (`NAME_FIELD`, `WEIGHT_FIELD`), the type `RadioButtonMMD`s (`typeRadio(type)`), and the save and cancel buttons (`SAVE_BUTTON`, `CANCEL_BUTTON`).

The row composable takes `profile: BikeProfileUi`, so `profile.id` is in scope; if the id is not threaded into the form's own composable, pass the tag strings in from the call site rather than widening any signature.

- [ ] **Step 3: Write the tests**

```kotlin
package com.speedevand.inkride.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.settings.BikeProfileRepository
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.fakes.FakeBikeProfileRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import com.speedevand.inkride.core.testing.rules.KoinTestRule
import com.speedevand.inkride.core.testing.support.TestBikeProfiles
import com.speedevand.inkride.core.testing.support.TestSettings
import com.speedevand.inkride.core.testing.support.stringRes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module

@RunWith(AndroidJUnit4::class)
class BikeProfilesScreenTest {
    private val settingsRepository = FakeUserSettingsRepository(TestSettings.default())
    private val profileRepository = FakeBikeProfileRepository()

    @get:Rule(order = 0)
    val koinRule =
        KoinTestRule(
            modules =
                listOf(
                    settingsPresentationModule,
                    module {
                        single<UserSettingsRepository> { settingsRepository }
                        single<BikeProfileRepository> { profileRepository }
                    },
                ),
        )

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(onBack: () -> Unit = {}) {
        composeTestRule.setContent { BikeProfilesRoot(onNavigateBack = onBack) }
    }

    @Test
    fun anEmptyListShowsTheEmptyState() {
        setContent()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun storedProfilesRenderWithTheirNameAndWeight() {
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road(), TestBikeProfiles.mtb()))
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.row(1L)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Road bike").assertIsDisplayed()
        composeTestRule.onNodeWithText("MTB").assertIsDisplayed()
    }

    @Test
    fun addingAProfileStoresItAndShowsItInTheList() {
        setContent()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.ADD_BUTTON).performClick()
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.NAME_FIELD).performTextInput("Gravel")
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.WEIGHT_FIELD).performTextInput("9.5")
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.typeRadio(BikeType.CITY)).performClick()
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.SAVE_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(BikeProfilesTestTags.ADD_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Gravel").assertIsDisplayed()
    }

    @Test
    fun savingWithAnEmptyNameKeepsTheFormOpenAndStoresNothing() {
        setContent()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.ADD_BUTTON).performClick()
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.WEIGHT_FIELD).performTextInput("9.5")
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.SAVE_BUTTON).performClick()
        composeTestRule.waitForIdle()

        // The form is still on screen, so nothing was committed.
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.SAVE_BUTTON).assertIsDisplayed()
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun savingWithANonNumericWeightKeepsTheFormOpen() {
        setContent()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.ADD_BUTTON).performClick()
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.NAME_FIELD).performTextInput("Gravel")
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.WEIGHT_FIELD).performTextInput("abc")
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.SAVE_BUTTON).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.SAVE_BUTTON).assertIsDisplayed()
    }

    @Test
    fun cancellingClosesTheFormWithoutStoringAnything() {
        setContent()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.ADD_BUTTON).performClick()
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.NAME_FIELD).performTextInput("Gravel")
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.CANCEL_BUTTON).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(BikeProfilesTestTags.SAVE_BUTTON).assertCountEquals(0)
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.EMPTY_STATE).assertIsDisplayed()
    }

    @Test
    fun editingAProfileUpdatesItInPlace() {
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road()))
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.editButton(1L)).performClick()
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.NAME_FIELD).performTextClearance()
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.NAME_FIELD).performTextInput("Road bike v2")
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.SAVE_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithText("Road bike v2").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onAllNodesWithTag(BikeProfilesTestTags.row(1L)).assertCountEquals(1)
    }

    @Test
    fun deletingAProfileRemovesItFromTheList() {
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road(), TestBikeProfiles.mtb()))
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.deleteButton(2L)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag(BikeProfilesTestTags.row(2L)).fetchSemanticsNodes().isEmpty()
        }
        assertThat(profileRepository.deletedIds).contains(2L)
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.row(1L)).assertIsDisplayed()
    }

    @Test
    fun deletingTheActiveProfileClearsTheActiveIdInSettings() {
        settingsRepository.emitSettings(TestSettings.default(activeBikeProfileId = 1L))
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road()))
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.deleteButton(1L)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.activeBikeProfileId == null
        }
        assertThat(settingsRepository.lastSaved?.activeBikeProfileId).isNull()
    }

    @Test
    fun choosingAProfileMakesItActive() {
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road(), TestBikeProfiles.mtb()))
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.activeRadio(2L)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.activeBikeProfileId == 2L
        }
        assertThat(settingsRepository.lastSaved?.activeBikeProfileId).isEqualTo(2L)
        composeTestRule.onNodeWithTag(BikeProfilesTestTags.activeRadio(2L)).assertIsSelected()
    }

    @Test
    fun imperialUnitsLabelWeightInPounds() {
        settingsRepository.emitSettings(TestSettings.default(units = MeasurementUnits.IMPERIAL))
        profileRepository.emitProfiles(listOf(TestBikeProfiles.road()))
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(stringRes(R.string.bike_profiles_weight)).assertIsDisplayed()
        assertThat(
            composeTestRule
                .onAllNodesWithText("lbs", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        ).isTrue()
    }

    @Test
    fun backInvokesItsNavigationCallback() {
        var backPressed = false
        setContent(onBack = { backPressed = true })

        composeTestRule.onNodeWithTag(BikeProfilesTestTags.BACK_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { backPressed }
        assertThat(backPressed).isTrue()
    }
}
```

Add the import `androidx.compose.ui.test.onAllNodesWithText`. If `BikeProfilesRoot`'s parameter is not named `onNavigateBack`, read the composable and match it.

- [ ] **Step 4: Run the class**

Run: `./gradlew :feature:settings:presentation:connectedDebugAndroidTest --tests "*BikeProfilesScreenTest"`
Expected: PASS, 12 tests.

- [ ] **Step 5: Run the whole module and its unit tests**

```bash
./gradlew :feature:settings:presentation:connectedDebugAndroidTest :feature:settings:presentation:testDebugUnitTest
```
Expected: PASS, 33 instrumented tests plus the existing unit tests.

- [ ] **Step 6: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/settings/presentation
git commit -m "test: add instrumented bike profiles tests"
```

---

### Task 5: `BleSensorsScreen` tags and tests

**Files:**
- Create: `feature/ble/presentation/src/main/java/com/speedevand/inkride/ble/presentation/BleSensorsTestTags.kt`
- Modify: `feature/ble/presentation/src/main/java/com/speedevand/inkride/ble/presentation/BleSensorsScreen.kt`
- Create: `feature/ble/presentation/src/androidTest/kotlin/com/speedevand/inkride/ble/presentation/BleSensorsScreenTest.kt`

**Interfaces:**
- Consumes: `KoinTestRule`, `FakeBleScanner`, `FakeUserSettingsRepository`, `TestBleDevices`, `TestSettings` (Plan 1).
- Produces: `object BleSensorsTestTags` with `const val BACK_BUTTON`, and `fun scanButton(type: BleSensorType)`, `fun stopScanButton(type: BleSensorType)`, `fun scanningLabel(type: BleSensorType)`, `fun forgetButton(type: BleSensorType)`, `fun pairedLabel(type: BleSensorType)`, `fun nonePairedLabel(type: BleSensorType)`, `fun deviceRow(address: String)`.

---

- [ ] **Step 1: Write the tag object**

The screen renders the same section composable twice, once per sensor kind, so every tag is keyed by `BleSensorType`.

```kotlin
package com.speedevand.inkride.ble.presentation

import com.speedevand.inkride.core.domain.ble.BleSensorType

/**
 * Stable identifiers for the BLE pairing screen. The heart-rate and cadence
 * sections render the same composable with the same button labels, so tags are
 * keyed by sensor type; device rows are keyed by MAC address.
 */
object BleSensorsTestTags {
    const val BACK_BUTTON = "ble_back"

    fun scanButton(type: BleSensorType): String = "ble_scan_${type.name}"

    fun stopScanButton(type: BleSensorType): String = "ble_stop_scan_${type.name}"

    fun scanningLabel(type: BleSensorType): String = "ble_scanning_${type.name}"

    fun forgetButton(type: BleSensorType): String = "ble_forget_${type.name}"

    fun pairedLabel(type: BleSensorType): String = "ble_paired_${type.name}"

    fun nonePairedLabel(type: BleSensorType): String = "ble_none_paired_${type.name}"

    fun deviceRow(address: String): String = "ble_device_$address"
}
```

- [ ] **Step 2: Apply the tags**

In `BleSensorsScreen.kt`, the section composable already receives the sensor `type`. Tag: the back `IconButton` (`BACK_BUTTON`); the "paired to" `TextMMD` (`pairedLabel(type)`); the forget `OutlinedButtonMMD` (`forgetButton(type)`); the "none paired" `TextMMD` (`nonePairedLabel(type)`); the stop-scan `ButtonMMD` (`stopScanButton(type)`); the "scanning" `TextMMD` (`scanningLabel(type)`); the scan `OutlinedButtonMMD` (`scanButton(type)`). In `DiscoveredDeviceRow`, tag the clickable row with `deviceRow(device.address)`.

- [ ] **Step 3: Write the tests**

```kotlin
package com.speedevand.inkride.ble.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.ble.BleScanError
import com.speedevand.inkride.core.domain.ble.BleScanner
import com.speedevand.inkride.core.domain.ble.BleSensorType
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.fakes.FakeBleScanner
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import com.speedevand.inkride.core.testing.rules.KoinTestRule
import com.speedevand.inkride.core.testing.support.TestBleDevices
import com.speedevand.inkride.core.testing.support.TestSettings
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module

@RunWith(AndroidJUnit4::class)
class BleSensorsScreenTest {
    /**
     * `BleSensorsRoot` requests the scan/connect permissions from a
     * `LaunchedEffect` on API 31+. Without this rule the system dialog would
     * cover the screen and every interaction would time out.
     */
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
        )

    private val settingsRepository = FakeUserSettingsRepository(TestSettings.default())
    private val scanner = FakeBleScanner()

    @get:Rule(order = 1)
    val koinRule =
        KoinTestRule(
            modules =
                listOf(
                    blePresentationModule,
                    module {
                        single<UserSettingsRepository> { settingsRepository }
                        single<BleScanner> { scanner }
                    },
                ),
        )

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(onBack: () -> Unit = {}) {
        composeTestRule.setContent { BleSensorsRoot(onNavigateBack = onBack) }
    }

    private fun startScan(type: BleSensorType) {
        composeTestRule.onNodeWithTag(BleSensorsTestTags.scanButton(type)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) { scanner.scanCount > 0 }
    }

    @Test
    fun bothSlotsShowAsUnpairedWhenNothingIsStored() {
        setContent()

        composeTestRule
            .onNodeWithTag(BleSensorsTestTags.nonePairedLabel(BleSensorType.HEART_RATE))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(BleSensorsTestTags.nonePairedLabel(BleSensorType.CADENCE))
            .assertIsDisplayed()
    }

    @Test
    fun startingAHeartRateScanShowsTheScanningStateAndDiscoveredDevices() {
        setContent()

        startScan(BleSensorType.HEART_RATE)
        composeTestRule
            .onNodeWithTag(BleSensorsTestTags.scanningLabel(BleSensorType.HEART_RATE))
            .assertIsDisplayed()

        runBlocking { scanner.emitDevice(TestBleDevices.hrm()) }

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag(BleSensorsTestTags.deviceRow("AA:BB:CC:DD:EE:01"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun stoppingAScanClearsTheScanningState() {
        setContent()
        startScan(BleSensorType.HEART_RATE)

        composeTestRule.onNodeWithTag(BleSensorsTestTags.stopScanButton(BleSensorType.HEART_RATE)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag(BleSensorsTestTags.scanningLabel(BleSensorType.HEART_RATE))
                .fetchSemanticsNodes()
                .isEmpty()
        }
        assertThat(scanner.stoppedScans).isGreaterThan(0)
    }

    @Test
    fun selectingADeviceStoresItsAddressAndEndsTheScan() {
        setContent()
        startScan(BleSensorType.HEART_RATE)
        runBlocking { scanner.emitDevice(TestBleDevices.hrm()) }
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag(BleSensorsTestTags.deviceRow("AA:BB:CC:DD:EE:01"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule.onNodeWithTag(BleSensorsTestTags.deviceRow("AA:BB:CC:DD:EE:01")).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.pairedHrmAddress == "AA:BB:CC:DD:EE:01"
        }
        assertThat(settingsRepository.lastSaved?.pairedHrmAddress).isEqualTo("AA:BB:CC:DD:EE:01")
        assertThat(scanner.stoppedScans).isGreaterThan(0)
    }

    @Test
    fun aCadenceScanIsIndependentOfTheHeartRateSection() {
        setContent()

        startScan(BleSensorType.CADENCE)

        assertThat(scanner.lastScannedType).isEqualTo(BleSensorType.CADENCE)
        composeTestRule
            .onNodeWithTag(BleSensorsTestTags.scanningLabel(BleSensorType.CADENCE))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithTag(BleSensorsTestTags.scanningLabel(BleSensorType.HEART_RATE))
            .assertCountEquals(0)
    }

    @Test
    fun aCadenceScanOnlySurfacesCadenceDevices() {
        setContent()
        startScan(BleSensorType.CADENCE)

        runBlocking {
            scanner.emitDevice(TestBleDevices.hrm())
            scanner.emitDevice(TestBleDevices.cadence())
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag(BleSensorsTestTags.deviceRow("AA:BB:CC:DD:EE:02"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithTag(BleSensorsTestTags.deviceRow("AA:BB:CC:DD:EE:01"))
            .assertCountEquals(0)
    }

    @Test
    fun forgettingTheHeartRateSensorLeavesTheCadenceOnePaired() {
        settingsRepository.emitSettings(
            TestSettings.default(
                pairedHrmAddress = "AA:BB:CC:DD:EE:01",
                pairedCadenceAddress = "AA:BB:CC:DD:EE:02",
            ),
        )
        setContent()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(BleSensorsTestTags.forgetButton(BleSensorType.HEART_RATE)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.pairedHrmAddress == null
        }
        assertThat(settingsRepository.lastSaved?.pairedHrmAddress).isNull()
        assertThat(settingsRepository.lastSaved?.pairedCadenceAddress).isEqualTo("AA:BB:CC:DD:EE:02")
    }

    @Test
    fun anUnavailableAdapterLeavesTheScanningStateOff() {
        scanner.availability = Result.Error(BleScanError.BLUETOOTH_OFF)
        setContent()

        composeTestRule.onNodeWithTag(BleSensorsTestTags.scanButton(BleSensorType.HEART_RATE)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onAllNodesWithTag(BleSensorsTestTags.scanningLabel(BleSensorType.HEART_RATE))
            .assertCountEquals(0)
        assertThat(scanner.scanCount).isEqualTo(0)
    }

    @Test
    fun backInvokesItsNavigationCallback() {
        var backPressed = false
        setContent(onBack = { backPressed = true })

        composeTestRule.onNodeWithTag(BleSensorsTestTags.BACK_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) { backPressed }
        assertThat(backPressed).isTrue()
    }
}
```

The error path surfaces as a `Toast` (see `BleSensorsRoot`), which Compose's semantics tree cannot see — hence `anUnavailableAdapterLeavesTheScanningStateOff` asserts the observable consequence (no scan started, no scanning state) rather than the toast text.

- [ ] **Step 4: Run the class**

Run: `./gradlew :feature:ble:presentation:connectedDebugAndroidTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Format, verify style, commit**

```bash
./gradlew ktlintFormat && ./gradlew ktlintCheck
git add feature/ble/presentation
git commit -m "test: add instrumented BLE sensor pairing tests"
```

---

## Done when

- `./gradlew :feature:settings:presentation:connectedDebugAndroidTest` is green — 33 tests.
- `./gradlew :feature:ble:presentation:connectedDebugAndroidTest` is green — 9 tests.
- Both modules' unit tests remain green.
- Every `SettingsAction`, `BikeProfilesAction` and `BleSensorsAction` is reached by at least one instrumented test.
- `./gradlew ktlintCheck` is green.
