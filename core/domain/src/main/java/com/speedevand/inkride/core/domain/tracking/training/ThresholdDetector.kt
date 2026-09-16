package com.speedevand.inkride.core.domain.tracking.training

import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.tracking.PowerSource

/** A threshold the detector believes has been beaten. Null means no proposal. */
data class ThresholdProposal(
    val ftpWatts: Int? = null,
    val lthrBpm: Int? = null,
)

/**
 * Looks for a new FTP or LTHR in a finished ride.
 *
 * FTP is the best 20-minute rolling average power x 0.95, the standard field
 * estimate. Only `PowerSource.MEASURED` samples count: letting a modelled
 * estimate move the threshold would let wind rewrite the rider's physiology.
 *
 * A proposal is only raised when it beats the standing threshold by
 * [minImprovementRatio], which keeps day-to-day noise from nudging the number,
 * and it is never applied automatically — the rider accepts or rejects it.
 */
class ThresholdDetector(
    private val windowSeconds: Int = 20 * 60,
    private val ftpFactor: Double = 0.95,
    private val minImprovementRatio: Double = 1.02,
) {
    fun detect(
        samples: List<RideSample>,
        current: AthleteThresholds,
    ): ThresholdProposal {
        val bestPower =
            bestRollingAverage(
                samples.map { sample ->
                    sample.powerWatts?.takeIf { sample.powerSource == PowerSource.MEASURED }?.toDouble()
                },
            )
        val ftpCandidate = bestPower?.let { (it * ftpFactor).toInt() }
        val lthrCandidate = bestRollingAverage(samples.map { it.heartRateBpm?.toDouble() })?.toInt()

        return ThresholdProposal(
            ftpWatts = ftpCandidate?.takeIf { beats(it, current.ftpWatts) },
            lthrBpm = lthrCandidate?.takeIf { beats(it, current.lthrBpm) },
        )
    }

    private fun beats(
        candidate: Int,
        currentValue: Int?,
    ): Boolean = currentValue == null || candidate >= currentValue * minImprovementRatio

    /**
     * Best [windowSeconds]-long average over a 1 Hz series, treating null as a
     * break: a window containing a gap is not a sustained effort.
     */
    private fun bestRollingAverage(series: List<Double?>): Double? {
        if (series.size < windowSeconds) return null
        var best = Double.NEGATIVE_INFINITY
        var windowSum = 0.0
        var validCount = 0

        for (index in series.indices) {
            series[index]?.let {
                windowSum += it
                validCount++
            }
            if (index >= windowSeconds) {
                series[index - windowSeconds]?.let {
                    windowSum -= it
                    validCount--
                }
            }
            if (index >= windowSeconds - 1 && validCount == windowSeconds) {
                best = maxOf(best, windowSum / windowSeconds)
            }
        }
        return best.takeIf { it > Double.NEGATIVE_INFINITY }
    }
}
