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
