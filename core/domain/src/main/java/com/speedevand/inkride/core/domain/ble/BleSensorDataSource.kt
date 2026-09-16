package com.speedevand.inkride.core.domain.ble

import kotlinx.coroutines.flow.Flow

/**
 * The sensors the rider has paired. A value object rather than a parameter per
 * sensor: the contract gained a third slot with power and would gain more
 * (speed, radar) if every kind needed its own parameter.
 */
data class PairedSensors(
    val hrmAddress: String? = null,
    val cadenceAddress: String? = null,
    val powerAddress: String? = null,
) {
    val addresses: Set<String> get() = setOfNotNull(hrmAddress, cadenceAddress, powerAddress)
}

/**
 * Live BLE sensor stream consumed by `RideTracker` during a ride. The Android
 * implementation maintains GATT connections to the paired sensors and folds
 * their notifications into a single [BleSample] flow.
 *
 * [connect] is idempotent: calling it again with the same addresses is a no-op,
 * so it can be driven from a settings flow that re-emits on every change.
 */
interface BleSensorDataSource {
    fun observeSamples(): Flow<BleSample>

    fun connect(sensors: PairedSensors)

    fun disconnect()
}
