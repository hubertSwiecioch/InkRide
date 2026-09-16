package com.speedevand.inkride.ble.data

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.ble.PairedSensors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidBleSensorDataSourceConnectTest {
    // Declared as ContextWrapper for the same reason as
    // AndroidRideSensorDataSourceStartTest: shadowOf(context) only resolves an
    // overload carrying grantPermissions/denyPermissions for ContextWrapper.
    private val context = ApplicationProvider.getApplicationContext<ContextWrapper>()
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    @Test
    fun `connect does not crash when BLUETOOTH_CONNECT permission is missing`() {
        shadowOf(bluetoothManager.adapter).setEnabled(true)
        val device = bluetoothManager.adapter.getRemoteDevice("00:11:22:33:44:55")
        // Mirrors what a real device does on API 31+ when BLUETOOTH_CONNECT
        // hasn't been granted: connectGatt() throws SecurityException from
        // the system service, as seen in the crash this test reproduces
        // (AndroidBleSensorDataSource.connect -> BluetoothDevice.connectGatt).
        shadowOf(device).setShouldThrowSecurityExceptions(true)

        val dataSource = AndroidBleSensorDataSource(context)

        // A ride can (re)enter this path from RideTracker's background
        // settings collector with an already-paired address, with no UI
        // permission prompt in between -- so this must fail soft, not throw.
        dataSource.connect(PairedSensors(hrmAddress = device.address))
    }

    @Test
    fun `a cycling power notification reaches the emitted sample`() =
        runTest {
            val dataSource = connectedTo(PairedSensors(powerAddress = POWER_ADDRESS))

            // flags = 0, power = 243 W.
            dataSource.deliverCharacteristicForTest(
                address = POWER_ADDRESS,
                uuid = BleGatt.CYCLING_POWER_MEASUREMENT,
                value = byteArrayOf(0x00, 0x00, 0xF3.toByte(), 0x00),
            )

            val sample = dataSource.observeSamples().first()
            assertThat(sample.powerWatts).isEqualTo(243)
            assertThat(sample.powerUpdatedAtMs).isNotNull()
        }

    @Test
    fun `a power meter reporting crank data supplies cadence with no CSC sensor paired`() =
        runTest {
            val dataSource = connectedTo(PairedSensors(powerAddress = POWER_ADDRESS))

            // flags bit 5 (crank data), power = 200 W, revs = 10, event time = 1024.
            dataSource.deliverCharacteristicForTest(
                address = POWER_ADDRESS,
                uuid = BleGatt.CYCLING_POWER_MEASUREMENT,
                value = byteArrayOf(0x20, 0x00, 0xC8.toByte(), 0x00, 0x0A, 0x00, 0x00, 0x04),
            )
            // First crank reading is only a baseline, so cadence is still unknown.
            assertThat(dataSource.observeSamples().first().cadenceRpm).isNull()

            // +1 revolution, +1024 ticks (= 1 s) -> 60 rpm.
            dataSource.deliverCharacteristicForTest(
                address = POWER_ADDRESS,
                uuid = BleGatt.CYCLING_POWER_MEASUREMENT,
                value = byteArrayOf(0x20, 0x00, 0xC8.toByte(), 0x00, 0x0B, 0x00, 0x00, 0x08),
            )

            val sample = dataSource.observeSamples().first()
            assertThat(sample.cadenceRpm).isEqualTo(60)
            assertThat(sample.cadenceUpdatedAtMs).isNotNull()
        }

    @Test
    fun `disconnecting clears the last measured watts`() =
        runTest {
            val dataSource = connectedTo(PairedSensors(powerAddress = POWER_ADDRESS))
            dataSource.deliverCharacteristicForTest(
                address = POWER_ADDRESS,
                uuid = BleGatt.CYCLING_POWER_MEASUREMENT,
                value = byteArrayOf(0x00, 0x00, 0xF3.toByte(), 0x00),
            )

            dataSource.disconnect()

            // A dropped meter must not leave the rider staring at its last
            // reading forever -- the same rule heart rate and cadence follow.
            val sample = dataSource.observeSamples().first()
            assertThat(sample.powerWatts).isNull()
            assertThat(sample.powerUpdatedAtMs).isNull()
        }

    /**
     * A data source that has actually been through [connect]'s happy path.
     * Both are required at API 33 or connect() bails before registering the
     * per-address crank tracker, and the meter-derived cadence then has nothing
     * to diff against.
     */
    private fun connectedTo(sensors: PairedSensors): AndroidBleSensorDataSource {
        shadowOf(bluetoothManager.adapter).setEnabled(true)
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        return AndroidBleSensorDataSource(context).apply { connect(sensors) }
    }

    private companion object {
        const val POWER_ADDRESS = "00:11:22:33:44:66"
    }
}
