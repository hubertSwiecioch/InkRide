package com.speedevand.inkride.core.domain.history

import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.tracking.PowerSource

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
    val normalizedPowerWatts: Int? = null,
    val intensityFactor: Double? = null,
    val trainingStressScore: Double? = null,
    val hrTss: Double? = null,
    val trimp: Double? = null,
    val workKj: Double? = null,
    val decouplingPercent: Double? = null,
    val avgHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val avgCadenceRpm: Int? = null,
    val maxPowerWatts: Int? = null,
    val powerSource: PowerSource? = null,
    // The thresholds in force when this ride happened. TSS is only meaningful
    // against them, so raising FTP later must not rewrite past training load.
    val ftpAtRideWatts: Int? = null,
    val lthrAtRideBpm: Int? = null,
)
