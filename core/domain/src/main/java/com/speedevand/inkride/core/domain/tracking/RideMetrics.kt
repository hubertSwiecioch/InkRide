package com.speedevand.inkride.core.domain.tracking

enum class GpsQuality {
    GOOD,
    FAIR,
    POOR,
}

data class RideMetrics(
    val currentSpeedKmh: Double = 0.0,
    val averageSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val distanceKm: Double = 0.0,
    val movingTimeSeconds: Long = 0L,
    val elapsedTimeSeconds: Long = 0L,
    val altitudeM: Double? = null,
    val elevationGainM: Double = 0.0,
    val gradePercent: Double = 0.0,
    val caloriesKcal: Double = 0.0,
    val powerWatts: Int = 0,
    val averagePowerWatts: Int = 0,
    // Where powerWatts came from. The UI marks estimated power as a model
    // output, and training load refuses to build NP/IF/TSS on top of it.
    val powerSource: PowerSource = PowerSource.ESTIMATED,
    val gpsAccuracyM: Float? = null,
    val bearingDegrees: Float? = null,
    val gpsQuality: GpsQuality = GpsQuality.POOR,
    // True while the calculator considers the rider to be moving. The single
    // source of truth for movement: RideTracker's auto-pause consumes this
    // instead of re-deriving movement from currentSpeedKmh, which disagreed
    // with the calculator whenever displacement confirmed movement that the
    // Doppler speed did not.
    val isMoving: Boolean = false,
    // True when no GPS fix has arrived recently enough to trust the speed
    // readout. The UI shows "--" rather than a frozen number: during a dropout
    // (tunnel, dense cover) the last known speed is not evidence of anything.
    val isSpeedStale: Boolean = false,
    // Live values from paired BLE sensors; null when no sensor is connected.
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
    // Local weather hint from the barometric pressure trend (offline, no API).
    val weatherTrend: WeatherTrend = WeatherTrend.UNKNOWN,
)
