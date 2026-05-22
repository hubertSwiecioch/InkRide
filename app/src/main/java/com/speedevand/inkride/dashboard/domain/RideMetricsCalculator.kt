package com.speedevand.inkride.dashboard.domain

import com.speedevand.inkride.dashboard.model.RideMetrics
import com.speedevand.inkride.dashboard.model.RideSensorSample
import com.speedevand.inkride.dashboard.model.UserProfile
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class RideMetricsCalculator(
    private val caloriesEstimator: CaloriesEstimator = CaloriesEstimator(),
    private val autoPauseThresholdKmh: Double = 1.5,
    private val minGradeDistanceM: Double = 4.0,
    private val elevationNoiseThresholdM: Double = 1.0
) {
    private var sessionStartMs: Long? = null
    private var lastSample: RideSensorSample? = null
    private var movingTimeMs: Long = 0L
    private var totalDistanceM: Double = 0.0
    private var maxSpeedMps: Double = 0.0
    private var elevationGainM: Double = 0.0
    private var lastAltitudeM: Double? = null
    private var caloriesKcal: Double = 0.0

    fun reset() {
        sessionStartMs = null
        lastSample = null
        movingTimeMs = 0L
        totalDistanceM = 0.0
        maxSpeedMps = 0.0
        elevationGainM = 0.0
        lastAltitudeM = null
        caloriesKcal = 0.0
    }

    fun process(sample: RideSensorSample, userProfile: UserProfile): RideMetrics {
        val startTime = sessionStartMs ?: sample.timestampMs.also { sessionStartMs = it }
        val previous = lastSample

        if (previous == null) {
            lastAltitudeM = sample.altitudeFromBarometerM ?: sample.altitudeFromGpsM
            lastSample = sample
            return RideMetrics(
                altitudeM = lastAltitudeM,
                elapsedTimeSeconds = 0L,
                isAutoPaused = true,
                gpsAccuracyM = sample.accuracyM
            )
        }

        val dtMs = (sample.timestampMs - previous.timestampMs).coerceAtLeast(0L)
        val segmentDistanceM = haversineDistanceMeters(
            previous.latitude,
            previous.longitude,
            sample.latitude,
            sample.longitude
        )

        val speedMpsFromDistance = if (dtMs > 0L) segmentDistanceM / (dtMs / 1000.0) else 0.0
        val speedMps = sample.speedFromGpsMps ?: speedMpsFromDistance
        val speedKmh = speedMps * 3.6

        val autoPaused = speedKmh < autoPauseThresholdKmh
        if (!autoPaused) {
            movingTimeMs += dtMs
            totalDistanceM += segmentDistanceM
            maxSpeedMps = max(maxSpeedMps, speedMps)
            caloriesKcal += caloriesEstimator.estimateKcal(speedKmh, dtMs, userProfile)
        }

        val altitudeM = sample.altitudeFromBarometerM ?: sample.altitudeFromGpsM
        val previousAltitude = lastAltitudeM
        if (altitudeM != null && previousAltitude != null) {
            val altitudeDelta = altitudeM - previousAltitude
            if (altitudeDelta > elevationNoiseThresholdM) {
                elevationGainM += altitudeDelta
            }
        }
        if (altitudeM != null) {
            lastAltitudeM = altitudeM
        }

        val altitudeDeltaForGrade = if (altitudeM != null && previousAltitude != null) {
            altitudeM - previousAltitude
        } else {
            0.0
        }

        val gradePercent = if (segmentDistanceM >= minGradeDistanceM) {
            ((altitudeDeltaForGrade / segmentDistanceM) * 100.0).coerceIn(-35.0, 35.0)
        } else {
            0.0
        }

        lastSample = sample

        val elapsedSeconds = ((sample.timestampMs - startTime) / 1000L).coerceAtLeast(0L)
        val movingSeconds = movingTimeMs / 1000L
        val avgSpeedKmh = if (movingTimeMs > 0L) {
            (totalDistanceM / (movingTimeMs / 1000.0)) * 3.6
        } else {
            0.0
        }

        return RideMetrics(
            currentSpeedKmh = speedKmh,
            averageSpeedKmh = avgSpeedKmh,
            maxSpeedKmh = maxSpeedMps * 3.6,
            distanceKm = totalDistanceM / 1000.0,
            movingTimeSeconds = movingSeconds,
            elapsedTimeSeconds = elapsedSeconds,
            altitudeM = altitudeM,
            elevationGainM = elevationGainM,
            gradePercent = gradePercent,
            caloriesKcal = caloriesKcal,
            isAutoPaused = autoPaused,
            gpsAccuracyM = sample.accuracyM
        )
    }

    private fun haversineDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()

        val a = sin(dLat / 2).pow(2) +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) *
            sin(dLon / 2).pow(2)

        val c = 2 * asin(min(1.0, sqrt(a)))
        return earthRadiusM * c
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}

