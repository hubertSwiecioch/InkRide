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
