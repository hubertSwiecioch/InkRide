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

    private fun awaitDeviceRow(address: String) {
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule
                .onAllNodesWithTag(BleSensorsTestTags.deviceRow(address))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
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

        awaitDeviceRow("AA:BB:CC:DD:EE:01")
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
        awaitDeviceRow("AA:BB:CC:DD:EE:01")

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

        awaitDeviceRow("AA:BB:CC:DD:EE:02")
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

    @Test
    fun pairingAPowerMeterPersistsItsAddress() {
        setContent()
        startScan(BleSensorType.POWER)
        runBlocking { scanner.emitDevice(TestBleDevices.power()) }
        awaitDeviceRow("AA:BB:CC:DD:EE:03")

        composeTestRule.onNodeWithTag(BleSensorsTestTags.deviceRow("AA:BB:CC:DD:EE:03")).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.pairedPowerAddress == "AA:BB:CC:DD:EE:03"
        }
        assertThat(settingsRepository.lastSaved?.pairedPowerAddress).isEqualTo("AA:BB:CC:DD:EE:03")
        assertThat(scanner.stoppedScans).isGreaterThan(0)
    }

    @Test
    fun thePowerSlotShowsAsUnpairedWhenNothingIsStored() {
        setContent()

        composeTestRule
            .onNodeWithTag(BleSensorsTestTags.nonePairedLabel(BleSensorType.POWER))
            .assertIsDisplayed()
    }

    @Test
    fun forgettingThePowerMeterLeavesTheOtherSensorsPaired() {
        runBlocking {
            settingsRepository.save(
                TestSettings.default().copy(
                    pairedHrmAddress = "AA:BB:CC:DD:EE:01",
                    pairedCadenceAddress = "AA:BB:CC:DD:EE:02",
                    pairedPowerAddress = "AA:BB:CC:DD:EE:03",
                ),
            )
        }
        setContent()

        composeTestRule.onNodeWithTag(BleSensorsTestTags.forgetButton(BleSensorType.POWER)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            settingsRepository.lastSaved?.pairedPowerAddress == null
        }
        // Forgetting one slot must not disturb the others.
        assertThat(settingsRepository.lastSaved?.pairedHrmAddress).isEqualTo("AA:BB:CC:DD:EE:01")
        assertThat(settingsRepository.lastSaved?.pairedCadenceAddress).isEqualTo("AA:BB:CC:DD:EE:02")
        assertThat(settingsRepository.lastSaved?.pairedPowerAddress).isNull()
    }
}
