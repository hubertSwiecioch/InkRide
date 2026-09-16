package com.speedevand.inkride.ble.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import com.speedevand.inkride.core.domain.ble.PairedSensors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * GATT client that keeps live connections to the paired HRM/cadence sensors and
 * folds their notifications into a single [BleSample] flow. The UI requests
 * BLUETOOTH_CONNECT (12+) before pairing, but [connect] can also be driven
 * later by RideTracker's background settings collector with an
 * already-paired address and no UI in between, so it re-checks the
 * permission itself and fails soft (like an adapter that's off) rather than
 * relying solely on that earlier request.
 */
@SuppressLint("MissingPermission")
class AndroidBleSensorDataSource(
    private val context: Context,
) : BleSensorDataSource {
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val samples = MutableStateFlow(BleSample(timestampMs = 0L))

    // One GATT per connected address (an address may serve both HR and cadence).
    private val gatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val cadenceTrackers = ConcurrentHashMap<String, CscCadenceTracker>()

    // Separate from [cadenceTrackers]: a power meter's crank counters are its
    // own, and diffing them against a CSC sensor's would produce nonsense.
    private val powerCrankTrackers = ConcurrentHashMap<String, CrankRevolutionTracker>()

    // Per-address queue of characteristics still awaiting a CCCD-enable write.
    // Android GATT permits only one outstanding operation, so notifications are
    // enabled one at a time, advancing on each onDescriptorWrite. Accessed only
    // from the (serial) GATT callback thread.
    private val pendingNotifications = ConcurrentHashMap<String, ArrayDeque<BluetoothGattCharacteristic>>()

    // Desired addresses currently requested, so connect() can be idempotent.
    @Volatile
    private var connectedAddresses: Set<String> = emptySet()

    // Addresses with an actual live GATT connection right now (a subset of
    // connectedAddresses — a desired address may still be reconnecting).
    // Mutated from the GATT callback thread (onConnectionStateChange) and from
    // disconnect() (called from the settings-driven connect() coroutine and
    // from RideTracker.stop()), so this is genuinely cross-thread. A
    // ConcurrentHashMap-backed set keeps individual add/remove/clear calls
    // safe from corruption; the narrow remaining race — a stray
    // STATE_CONNECTED from a gatt being torn down landing just after
    // disconnect()'s clear() — can leave `connected` transiently stale for
    // one BLE cycle, self-correcting on that gatt's own STATE_DISCONNECTED.
    // Acceptable for a best-effort UI indicator, not safety-critical state.
    private val liveAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var latestHeartRate: Int? = null

    @Volatile
    private var latestCadence: Int? = null

    @Volatile
    private var lastCadenceUpdateAtMs: Long? = null

    @Volatile
    private var latestWheelRevolutions: Long? = null

    @Volatile
    private var latestPowerWatts: Int? = null

    @Volatile
    private var latestPedalBalanceLeftPercent: Int? = null

    @Volatile
    private var lastPowerUpdateAtMs: Long? = null

    override fun observeSamples(): Flow<BleSample> = samples

    override fun connect(sensors: PairedSensors) {
        val desired = sensors.addresses
        if (desired == connectedAddresses) return
        disconnect()
        if (desired.isEmpty()) return

        val adapter = bluetoothManager?.adapter ?: return
        if (!adapter.isEnabled) return
        if (!hasConnectPermission()) return

        connectedAddresses = desired
        desired.forEach { address ->
            val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return@forEach
            cadenceTrackers[address] = CscCadenceTracker()
            powerCrankTrackers[address] = CrankRevolutionTracker()
            // autoConnect = true
            val gatt = device.connectGatt(context, true, gattCallback)
            if (gatt != null) gatts[address] = gatt
        }
    }

    override fun disconnect() {
        gatts.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gatts.clear()
        cadenceTrackers.clear()
        powerCrankTrackers.clear()
        pendingNotifications.clear()
        liveAddresses.clear()
        connectedAddresses = emptySet()
        clearLatestReadings()
        emit()
    }

    /**
     * Drops every cached sensor reading. A sensor that has dropped must not
     * leave its last value on screen forever — the rider should see that it is
     * gone, not its final number. Kept in one place so a newly added reading
     * cannot be cleared on one disconnect path and forgotten on the other.
     */
    private fun clearLatestReadings() {
        latestHeartRate = null
        latestCadence = null
        lastCadenceUpdateAtMs = null
        latestWheelRevolutions = null
        latestPowerWatts = null
        latestPedalBalanceLeftPercent = null
        lastPowerUpdateAtMs = null
    }

    private fun emit() {
        samples.value =
            BleSample(
                timestampMs = System.currentTimeMillis(),
                heartRateBpm = latestHeartRate,
                cadenceRpm = latestCadence,
                wheelRevolutions = latestWheelRevolutions,
                connected = liveAddresses.isNotEmpty(),
                cadenceUpdatedAtMs = lastCadenceUpdateAtMs,
                powerWatts = latestPowerWatts,
                pedalBalanceLeftPercent = latestPedalBalanceLeftPercent,
                powerUpdatedAtMs = lastPowerUpdateAtMs,
            )
    }

    private val gattCallback =
        object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                val address = gatt.device?.address
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        address?.let { liveAddresses.add(it) }
                        gatt.discoverServices()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        address?.let { liveAddresses.remove(it) }
                        // Don't let a stale reading from the now-gone sensor
                        // linger — the rider should see it's disconnected, not
                        // its last value forever.
                        clearLatestReadings()
                        emit()
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                val address = gatt.device?.address ?: return
                val queue = ArrayDeque<BluetoothGattCharacteristic>()
                gatt
                    .getService(BleGatt.HEART_RATE_SERVICE)
                    ?.getCharacteristic(BleGatt.HEART_RATE_MEASUREMENT)
                    ?.let { queue.add(it) }
                gatt
                    .getService(BleGatt.CSC_SERVICE)
                    ?.getCharacteristic(BleGatt.CSC_MEASUREMENT)
                    ?.let { queue.add(it) }
                gatt
                    .getService(BleGatt.CYCLING_POWER_SERVICE)
                    ?.getCharacteristic(BleGatt.CYCLING_POWER_MEASUREMENT)
                    ?.let { queue.add(it) }
                pendingNotifications[address] = queue
                enableNextNotification(gatt, address)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                // Previous CCCD write finished — enable the next characteristic, if any.
                gatt.device?.address?.let { enableNextNotification(gatt, it) }
            }

            @Deprecated("Deprecated in API 33; the pre-33 overload remains for broad device support")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                handleCharacteristic(gatt.device?.address, characteristic.uuid, characteristic.value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleCharacteristic(gatt.device?.address, characteristic.uuid, value)
            }
        }

    private fun handleCharacteristic(
        address: String?,
        uuid: java.util.UUID,
        data: ByteArray?,
    ) {
        if (data == null) return
        when (uuid) {
            BleGatt.HEART_RATE_MEASUREMENT -> {
                parseHeartRate(data)?.let {
                    latestHeartRate = it
                    emit()
                }
            }

            BleGatt.CYCLING_POWER_MEASUREMENT -> {
                val result = parseCyclingPower(data) ?: return
                val now = System.currentTimeMillis()
                latestPowerWatts = result.powerWatts
                latestPedalBalanceLeftPercent = result.pedalBalanceLeftPercent
                lastPowerUpdateAtMs = now
                // A power meter that reports crank data supplies cadence too,
                // so a rider with a meter needs no separate CSC sensor.
                val crank = result.crank
                val powerTracker = address?.let { powerCrankTrackers[it] }
                if (crank != null && powerTracker != null) {
                    powerTracker.cadenceFrom(crank.revolutions, crank.eventTime, now)?.let {
                        latestCadence = it
                        lastCadenceUpdateAtMs = now
                    }
                }
                emit()
            }

            BleGatt.CSC_MEASUREMENT -> {
                val tracker = address?.let { cadenceTrackers[it] } ?: return
                val now = System.currentTimeMillis()
                val result = tracker.update(data, now) ?: return
                result.cadenceRpm?.let {
                    latestCadence = it
                    lastCadenceUpdateAtMs = now
                }
                result.wheelRevolutions?.let { latestWheelRevolutions = it }
                emit()
            }
        }
    }

    /**
     * Feeds a characteristic value straight into the notification handling that
     * [gattCallback] would otherwise drive. A unit test has no real GATT server
     * to notify it, and the decoding this covers — which parser runs, which
     * tracker it diffs against, what reaches the emitted sample — is the part
     * worth testing, not Android's callback plumbing.
     */
    @VisibleForTesting
    internal fun deliverCharacteristicForTest(
        address: String,
        uuid: java.util.UUID,
        value: ByteArray,
    ) = handleCharacteristic(address, uuid, value)

    /**
     * Enables the next queued characteristic's notifications and writes its CCCD,
     * one at a time. Skips characteristics with no CCCD by recursing immediately.
     */
    private fun enableNextNotification(
        gatt: BluetoothGatt,
        address: String,
    ) {
        val queue = pendingNotifications[address] ?: return
        val characteristic = queue.removeFirstOrNull() ?: return
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(BleGatt.CCCD)
        if (descriptor == null) {
            enableNextNotification(gatt, address)
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    private fun hasConnectPermission(): Boolean {
        // BLUETOOTH_CONNECT is a runtime permission only on Android 12+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
