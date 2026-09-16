package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import com.speedevand.inkride.core.domain.ble.PairedSensors
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test double for [BleSensorDataSource], swapped in via Koin override in
 * instrumented tests so an emulator's absent BLE HRM/cadence hardware isn't a
 * blocker. `connect` only records what it was asked for (readable via
 * [connectedSensors]); tests drive the sample flow directly with [emit].
 */
class FakeBleSensorDataSource : BleSensorDataSource {
    private val samplesFlow =
        MutableSharedFlow<BleSample>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override fun observeSamples(): Flow<BleSample> = samplesFlow.asSharedFlow()

    /** The sensors the last [connect] call asked for; null until one happens. */
    var connectedSensors: PairedSensors? = null
        private set

    override fun connect(sensors: PairedSensors) {
        connectedSensors = sensors
    }

    override fun disconnect() {
        connectedSensors = null
    }

    fun emit(sample: BleSample) {
        check(samplesFlow.tryEmit(sample)) { "Failed to emit $sample" }
    }
}
