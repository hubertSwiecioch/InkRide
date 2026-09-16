package com.speedevand.inkride.core.domain.history

import com.speedevand.inkride.core.domain.settings.BikeType

data class RideRecord(
    val id: Long,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val distanceKm: Double,
    val movingTimeSeconds: Long,
    val elapsedTimeSeconds: Long,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val elevationGainM: Double,
    val caloriesKcal: Double,
    val averagePowerWatts: Int = 0,
    val bikeWeightKg: Double = 10.0,
    val bikeType: BikeType = BikeType.ROAD,
    // False while the ride is still in progress. Only complete rides appear in
    // history and lifetime totals; an incomplete one is a row a previous process
    // left behind, for [RideTracker.recoverUnfinishedRides] to close or discard.
    val isComplete: Boolean = true,
)
