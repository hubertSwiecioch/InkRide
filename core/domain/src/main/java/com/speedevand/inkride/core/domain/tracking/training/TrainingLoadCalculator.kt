package com.speedevand.inkride.core.domain.tracking.training

import com.speedevand.inkride.core.domain.tracking.PowerSource
import kotlin.math.pow

/**
 * Training load for an in-progress ride, computed once per elapsed second.
 *
 * Deliberately independent of `RideMetricsCalculator`: that class owns the
 * physics of riding (speed, distance, altitude, grade), this one owns the
 * physiology of the effort. They share only the sample stream and are composed
 * in `RideTracker`.
 *
 * Only seconds where the rider is moving are accumulated, so a traffic light
 * neither dilutes NP nor adds minutes to a zone.
 */
class TrainingLoadCalculator(
    private val resampler: SampleResampler = SampleResampler(),
    private val normalizedPowerWindowSeconds: Int = 30,
) {
    private val powerWindow = ArrayDeque<Int>()

    // A running sum rather than every rolling value: this is recomputed once a
    // second for the length of a ride, so keeping the samples would grow without
    // bound and turn the mean into an O(n) scan on each tick.
    private var fourthPowerSum: Double = 0.0
    private var fourthPowerCount: Long = 0L
    private var movingSeconds: Long = 0L
    private var workJoules: Double = 0.0

    fun reset() {
        resampler.reset()
        powerWindow.clear()
        fourthPowerSum = 0.0
        fourthPowerCount = 0L
        movingSeconds = 0L
        workJoules = 0.0
    }

    fun process(
        timestampMs: Long,
        powerWatts: Int?,
        powerSource: PowerSource?,
        heartRateBpm: Int?,
        altitudeM: Double?,
        isMoving: Boolean,
        thresholds: AthleteThresholds,
    ): TrainingMetrics {
        resampler
            .accept(timestampMs, powerWatts, heartRateBpm, altitudeM, isMoving)
            .forEach { second -> accumulate(second, powerSource) }

        val normalizedPower = normalizedPower(powerSource)
        val intensityFactor =
            normalizedPower
                ?.let { np -> thresholds.ftpWatts?.takeIf { it > 0 }?.let { np.toDouble() / it } }
        val trainingStressScore =
            if (normalizedPower != null && intensityFactor != null && thresholds.ftpWatts != null) {
                movingSeconds * normalizedPower * intensityFactor / (thresholds.ftpWatts * 3600.0) * 100.0
            } else {
                null
            }

        return TrainingMetrics(
            normalizedPowerWatts = normalizedPower,
            intensityFactor = intensityFactor,
            trainingStressScore = trainingStressScore,
            workKj = workJoules / 1000.0,
        )
    }

    private fun accumulate(
        second: ResampledSecond,
        powerSource: PowerSource?,
    ) {
        if (!second.isMoving) return
        movingSeconds++

        val watts = second.powerWatts ?: return
        workJoules += watts.coerceAtLeast(0).toDouble()

        // NP is defined on measured power only. Building it on PowerEstimator's
        // +-30-60 % model would compound that error to the fourth power.
        if (powerSource != PowerSource.MEASURED) return
        powerWindow.addLast(watts.coerceAtLeast(0))
        while (powerWindow.size > normalizedPowerWindowSeconds) {
            powerWindow.removeFirst()
        }
        if (powerWindow.size == normalizedPowerWindowSeconds) {
            fourthPowerSum += powerWindow.average().pow(4)
            fourthPowerCount++
        }
    }

    /**
     * Coggan's Normalized Power: the fourth root of the mean of the fourth
     * powers of a 30-second rolling average. The exponent is what makes a
     * surging ride cost more than a steady one at the same mean watts.
     */
    private fun normalizedPower(powerSource: PowerSource?): Int? {
        if (powerSource != PowerSource.MEASURED) return null
        if (fourthPowerCount == 0L) return null
        return (fourthPowerSum / fourthPowerCount).pow(0.25).toInt()
    }
}
