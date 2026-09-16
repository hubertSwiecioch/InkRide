package com.speedevand.inkride.core.domain.tracking.training

import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.tracking.PowerSource

/**
 * Aerobic decoupling (Pw:Hr): how much the power-to-heart-rate ratio fades from
 * the first half of a ride to the second. A low number means the rider held
 * their aerobic efficiency; a high one means they drifted.
 *
 * Computed after the ride, never live: the halfway point is only known once the
 * ride ends. Requires measured power and heart rate together — from an
 * estimated power this would be the quotient of two uncertainties.
 */
class DecouplingCalculator(
    private val minSamplesPerHalf: Int = 300,
) {
    fun calculate(samples: List<RideSample>): Double? {
        val usable =
            samples.filter {
                it.powerSource == PowerSource.MEASURED && it.powerWatts != null && it.heartRateBpm != null
            }
        if (usable.size < minSamplesPerHalf * 2) return null

        val midpoint = usable.size / 2
        val firstRatio = ratio(usable.subList(0, midpoint)) ?: return null
        val secondRatio = ratio(usable.subList(midpoint, usable.size)) ?: return null
        if (firstRatio == 0.0) return null

        return (firstRatio - secondRatio) / firstRatio * 100.0
    }

    private fun ratio(half: List<RideSample>): Double? {
        if (half.isEmpty()) return null
        val averagePower = half.mapNotNull { it.powerWatts }.average()
        val averageHeartRate = half.mapNotNull { it.heartRateBpm }.average()
        if (averageHeartRate <= 0.0) return null
        return averagePower / averageHeartRate
    }
}
