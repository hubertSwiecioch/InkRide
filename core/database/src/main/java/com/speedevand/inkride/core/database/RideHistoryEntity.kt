package com.speedevand.inkride.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ride_history")
data class RideHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    val bikeType: String = "ROAD",
    // False while a ride is still in progress. The row is inserted at ride
    // start so a killed process leaves something recoverable behind, and only
    // finished rides belong in history and lifetime totals.
    val isComplete: Boolean = true,
    // Training load for the ride. All nullable: a ride without a power meter or
    // a heart-rate strap simply has none, and an absent metric is not a zero.
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
    val powerSource: String? = null,
    // The thresholds in force when this ride happened. TSS is only meaningful
    // against them, so raising FTP later must not rewrite past training load.
    val ftpAtRideWatts: Int? = null,
    val lthrAtRideBpm: Int? = null,
)
