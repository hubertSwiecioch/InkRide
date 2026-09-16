package com.speedevand.inkride.core.domain.history

import com.speedevand.inkride.core.domain.tracking.PowerSource

/**
 * One second of a ride. Every field is nullable: any sensor may be absent, and
 * the stream deliberately continues through a GPS dropout so non-positional
 * readings are not lost with the fix.
 */
data class RideSample(
    val timestampMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeM: Double? = null,
    val speedKmh: Double? = null,
    val gradePercent: Double? = null,
    val powerWatts: Int? = null,
    val powerSource: PowerSource? = null,
    val heartRateBpm: Int? = null,
    val cadenceRpm: Int? = null,
)
