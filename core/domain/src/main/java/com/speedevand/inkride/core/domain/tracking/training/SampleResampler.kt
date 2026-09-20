package com.speedevand.inkride.core.domain.tracking.training

/** One normalised second of a ride. */
data class ResampledSecond(
    val timestampMs: Long,
    val powerWatts: Int?,
    val heartRateBpm: Int?,
    val altitudeM: Double?,
    val isMoving: Boolean,
)

/**
 * Normalises an uneven sensor stream onto a 1 Hz grid with zero-order hold.
 *
 * Every window in `TrainingLoadCalculator` is defined in seconds. Without this,
 * a 30-second NP window would contain a variable number of samples and the
 * result would track sensor rates instead of the rider's effort. Holding the
 * last reading across a gap is deliberate: a quiet sensor means "unchanged",
 * not "shorter ride".
 */
class SampleResampler(
    private val maxHoldSeconds: Int = 30,
) {
    private var lastEmittedSecond: Long? = null
    private var held: ResampledSecond? = null

    fun accept(
        timestampMs: Long,
        powerWatts: Int?,
        heartRateBpm: Int?,
        altitudeM: Double?,
        isMoving: Boolean,
    ): List<ResampledSecond> {
        val second = timestampMs / 1000L
        val previous = lastEmittedSecond
        val current =
            ResampledSecond(
                timestampMs = second * 1000L,
                powerWatts = powerWatts,
                heartRateBpm = heartRateBpm,
                altitudeM = altitudeM,
                isMoving = isMoving,
            )

        if (previous == null) {
            lastEmittedSecond = second
            held = current
            return listOf(current.copy(timestampMs = timestampMs))
        }
        if (second <= previous) {
            // Sub-second update: refresh what a later fill would hold, emit nothing.
            held = current
            return emptyList()
        }

        val gapSeconds = (second - previous).toInt()
        val holdFrom = held
        val filled = mutableListOf<ResampledSecond>()
        // Cap the fill so a long dropout can't manufacture minutes of held data.
        val fillCount = (gapSeconds - 1).coerceAtMost(maxHoldSeconds)
        for (offset in 1..fillCount) {
            filled +=
                (holdFrom ?: current).copy(timestampMs = (previous + offset) * 1000L)
        }
        filled += current

        lastEmittedSecond = second
        held = current
        return filled
    }

    fun reset() {
        lastEmittedSecond = null
        held = null
    }
}
