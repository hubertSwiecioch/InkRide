package com.speedevand.inkride.core.domain.tracking.training

import com.speedevand.inkride.core.domain.tracking.HeartRateZoneCalculator
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
    private val heartRateZoneCalculator: HeartRateZoneCalculator = HeartRateZoneCalculator(),
    private val powerZoneCalculator: PowerZoneCalculator = PowerZoneCalculator(),
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

    private val secondsInHrZone = mutableMapOf<Int, Long>()
    private val secondsInPowerZone = mutableMapOf<Int, Long>()
    private var heartRateSum: Long = 0L
    private var heartRateSeconds: Long = 0L
    private var currentHrZone: Int? = null
    private var currentPowerZone: Int? = null
    private var trimpAccumulator: Double = 0.0

    // (timestampMs, altitudeM) for the trailing VAM window, oldest first.
    private val altitudeWindow = ArrayDeque<Pair<Long, Double>>()
    private val vamWindowMs: Long = 60_000L

    fun reset() {
        resampler.reset()
        powerWindow.clear()
        fourthPowerSum = 0.0
        fourthPowerCount = 0L
        movingSeconds = 0L
        workJoules = 0.0
        secondsInHrZone.clear()
        secondsInPowerZone.clear()
        heartRateSum = 0L
        heartRateSeconds = 0L
        currentHrZone = null
        currentPowerZone = null
        trimpAccumulator = 0.0
        altitudeWindow.clear()
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
            .forEach { second -> accumulate(second, powerSource, thresholds) }

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

        // hrTSS mirrors TSS's shape against the lactate-threshold heart rate, so
        // an hour at LTHR scores 100 exactly as an hour at FTP does. Chosen over
        // Banister's TRIMP, which needs resting heart rate and a sex coefficient
        // this app deliberately does not collect.
        val averageHeartRate =
            if (heartRateSeconds > 0L) heartRateSum.toDouble() / heartRateSeconds else null
        val hrTss =
            averageHeartRate
                ?.let { avg -> thresholds.lthrBpm?.takeIf { it > 0 }?.let { avg / it } }
                ?.let { hrIf -> movingSeconds / 3600.0 * hrIf * hrIf * 100.0 }

        // VAM: vertical metres per hour over the trailing window. A short window
        // keeps it responsive on a climb; a longer one would lag the gradient.
        val vam =
            altitudeWindow
                .takeIf { it.size >= 2 }
                ?.let { window ->
                    val elapsedHours = (window.last().first - window.first().first) / 3_600_000.0
                    if (elapsedHours <= 0.0) null else (window.last().second - window.first().second) / elapsedHours
                }

        return TrainingMetrics(
            normalizedPowerWatts = normalizedPower,
            intensityFactor = intensityFactor,
            trainingStressScore = trainingStressScore,
            hrTss = hrTss,
            // TRIMP only exists if a strap actually reported something.
            trimp = trimpAccumulator.takeIf { heartRateSeconds > 0L },
            vamMetersPerHour = vam,
            workKj = workJoules / 1000.0,
            currentHrZone = currentHrZone,
            currentPowerZone = currentPowerZone,
            secondsInHrZone = secondsInHrZone.toMap(),
            secondsInPowerZone = secondsInPowerZone.toMap(),
        )
    }

    private fun accumulate(
        second: ResampledSecond,
        powerSource: PowerSource?,
        thresholds: AthleteThresholds,
    ) {
        if (!second.isMoving) return
        movingSeconds++

        second.heartRateBpm?.let { bpm ->
            heartRateSum += bpm
            heartRateSeconds++
            val zone = heartRateZoneCalculator.zoneFor(bpm, thresholds.ageForHrZones)
            currentHrZone = zone
            secondsInHrZone[zone] = (secondsInHrZone[zone] ?: 0L) + 1L
            // Edwards TRIMP: each minute counts as many times as its zone number.
            trimpAccumulator += zone / 60.0
        }

        second.altitudeM?.let { altitude ->
            altitudeWindow.addLast(second.timestampMs to altitude)
            while (altitudeWindow.size > 1 && second.timestampMs - altitudeWindow.first().first > vamWindowMs) {
                altitudeWindow.removeFirst()
            }
        }

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

        thresholds.ftpWatts?.takeIf { it > 0 }?.let { ftp ->
            val zone = powerZoneCalculator.zoneFor(watts, ftp)
            currentPowerZone = zone
            secondsInPowerZone[zone] = (secondsInPowerZone[zone] ?: 0L) + 1L
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
