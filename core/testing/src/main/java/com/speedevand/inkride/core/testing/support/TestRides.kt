package com.speedevand.inkride.core.testing.support

import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.tracking.LapRecord

object TestRides {
    /** A fixed wall-clock start so date assertions are reproducible. */
    const val START_MS = 1_750_000_000_000L

    fun record(
        id: Long = 1L,
        startTimestamp: Long = START_MS,
        endTimestamp: Long = START_MS + 3_600_000L,
        distanceKm: Double = 25.0,
        movingTimeSeconds: Long = 3_000L,
        elapsedTimeSeconds: Long = 3_600L,
        averageSpeedKmh: Double = 30.0,
        maxSpeedKmh: Double = 45.5,
        elevationGainM: Double = 320.0,
        caloriesKcal: Double = 780.0,
        averagePowerWatts: Int = 165,
    ): RideRecord =
        RideRecord(
            id = id,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            distanceKm = distanceKm,
            movingTimeSeconds = movingTimeSeconds,
            elapsedTimeSeconds = elapsedTimeSeconds,
            averageSpeedKmh = averageSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            elevationGainM = elevationGainM,
            caloriesKcal = caloriesKcal,
            averagePowerWatts = averagePowerWatts,
            bikeWeightKg = 9.0,
            bikeType = BikeType.ROAD,
        )

    fun laps(count: Int): List<LapRecord> =
        (1..count).map { n ->
            LapRecord(
                lapNumber = n,
                distanceKm = 5.0,
                movingTimeSeconds = 600L,
                averageSpeedKmh = 30.0,
                elevationGainM = 60.0,
            )
        }

    /** A straight northward track, one point per second. */
    fun trackPoints(count: Int): List<RideTrackPoint> =
        (0 until count).map { i ->
            RideTrackPoint(
                timestampMs = START_MS + i * 1_000L,
                latitude = 52.2297 + i * 0.0001,
                longitude = 21.0122,
                altitudeM = 100.0 + i,
                accuracyM = 5f,
            )
        }
}
