package com.speedevand.inkride.core.domain.settings

enum class AutoLapMode {
    OFF,
    DISTANCE,
    TIME,
}

/**
 * Automatic lap marking. [distanceKm] applies in DISTANCE mode, [intervalMinutes]
 * in TIME mode; the unused one is ignored rather than validated away, so
 * switching modes keeps the other value for when the rider switches back.
 */
data class AutoLapConfig(
    val mode: AutoLapMode = AutoLapMode.OFF,
    val distanceKm: Double? = null,
    val intervalMinutes: Int? = null,
)
