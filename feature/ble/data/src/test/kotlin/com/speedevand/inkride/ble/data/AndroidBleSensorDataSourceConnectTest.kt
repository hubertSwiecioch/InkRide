package com.speedevand.inkride.ble.data

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
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
        dataSource.connect(hrmAddress = device.address, cadenceAddress = null)
    }
}
