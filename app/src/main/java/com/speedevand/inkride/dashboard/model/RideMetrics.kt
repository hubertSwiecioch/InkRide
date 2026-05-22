package com.speedevand.inkride.dashboard.model

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
    val isAutoPaused: Boolean = true,
    val gpsAccuracyM: Float? = null
)

