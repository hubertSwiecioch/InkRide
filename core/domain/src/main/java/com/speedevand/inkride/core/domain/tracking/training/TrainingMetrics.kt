package com.speedevand.inkride.core.domain.tracking.training

/**
 * Training load for the ride so far. Every field is null when its inputs are
 * absent rather than approximated: NP, IF and TSS require power from a meter
 * (never PowerEstimator's model), IF and TSS additionally require an FTP, and
 * hrTSS and TRIMP require a heart-rate strap. The UI renders null as "--".
 */
data class TrainingMetrics(
    val normalizedPowerWatts: Int? = null,
    val intensityFactor: Double? = null,
    val trainingStressScore: Double? = null,
    val hrTss: Double? = null,
    val trimp: Double? = null,
    val vamMetersPerHour: Double? = null,
    val workKj: Double = 0.0,
    val currentHrZone: Int? = null,
    val currentPowerZone: Int? = null,
    val secondsInHrZone: Map<Int, Long> = emptyMap(),
    val secondsInPowerZone: Map<Int, Long> = emptyMap(),
)

/**
 * The rider's thresholds. [ftpWatts] null means IF and TSS cannot be computed
 * and must not be guessed; [lthrBpm] falls back to 0.9 x age-predicted HRmax,
 * which is an established rule of thumb, unlike any FTP default would be.
 */
data class AthleteThresholds(
    val ftpWatts: Int?,
    val lthrBpm: Int?,
    val ageForHrZones: Int,
)
