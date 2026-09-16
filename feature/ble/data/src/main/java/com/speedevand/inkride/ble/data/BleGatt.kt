package com.speedevand.inkride.ble.data

import java.util.UUID

/** Standard Bluetooth SIG UUIDs for the GATT profiles InkRide consumes. */
internal object BleGatt {
    private fun sig(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    // Services
    val HEART_RATE_SERVICE: UUID = sig("180d")
    val CSC_SERVICE: UUID = sig("1816")
    val CYCLING_POWER_SERVICE: UUID = sig("1818")

    // Characteristics
    val HEART_RATE_MEASUREMENT: UUID = sig("2a37")
    val CSC_MEASUREMENT: UUID = sig("2a5b")
    val CYCLING_POWER_MEASUREMENT: UUID = sig("2a63")

    // Client Characteristic Configuration Descriptor — written to enable notifications.
    val CCCD: UUID = sig("2902")
}

/**
 * Parses a Heart Rate Measurement (0x2A37) value. The first flag bit selects an
 * 8- or 16-bit BPM field. Returns null on a malformed packet.
 */
internal fun parseHeartRate(data: ByteArray): Int? {
    if (data.isEmpty()) return null
    val is16Bit = (data[0].toInt() and 0x01) != 0
    return if (is16Bit) {
        if (data.size < 3) {
            null
        } else {
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        }
    } else {
        if (data.size < 2) null else data[1].toInt() and 0xFF
    }
}

/**
 * A cumulative crank reading: revolutions since the sensor powered on, paired
 * with the time of that revolution. The two are only meaningful together, so
 * they travel as one value rather than two nullable fields a caller has to
 * check in step.
 */
internal data class CrankReading(
    val revolutions: Int,
    val eventTime: Int,
)

/** Decoded Cycling Power Measurement (0x2A63) fields InkRide consumes. */
internal data class CyclingPowerResult(
    val powerWatts: Int,
    val pedalBalanceLeftPercent: Int? = null,
    val crank: CrankReading? = null,
)

/**
 * Parses a Cycling Power Measurement (0x2A63) value.
 *
 * Layout: `uint16 flags`, then a mandatory `sint16` instantaneous power in
 * watts, then optional fields in flag order. Two are read here: pedal power
 * balance (bit 0, uint8 in half-percent units) and crank revolution data
 * (bit 5, uint16 cumulative revolutions + uint16 event time in 1/1024 s) —
 * the latter is why a power meter can supply cadence with no separate CSC
 * sensor. Fields between them that InkRide ignores must still be skipped, or
 * the crank offset lands on the wrong bytes.
 *
 * Power may legitimately be negative (some meters report a small negative
 * value while coasting), so it is read signed. Returns null on a packet too
 * short to carry power; a packet truncated inside an optional field yields
 * power with that field null, never a guessed value.
 */
internal fun parseCyclingPower(data: ByteArray): CyclingPowerResult? {
    if (data.size < CYCLING_POWER_HEADER_BYTES) return null
    val flags = readUint16(data, 0)
    val powerWatts = readSint16(data, 2)

    var offset = CYCLING_POWER_HEADER_BYTES
    val pedalBalanceLeftPercent =
        if (flags hasFlag FLAG_PEDAL_BALANCE) {
            if (data.size < offset + 1) return CyclingPowerResult(powerWatts)
            val halfPercent = data[offset].toInt() and 0xFF
            offset += 1
            halfPercent / 2
        } else {
            null
        }

    // Skip the optional fields between pedal balance and crank data, in flag
    // order, so the crank offset stays correct on meters that report them.
    if (flags hasFlag FLAG_ACCUMULATED_TORQUE) offset += 2 // uint16
    if (flags hasFlag FLAG_WHEEL_REVOLUTION_DATA) offset += 6 // uint32 + uint16

    if (!(flags hasFlag FLAG_CRANK_REVOLUTION_DATA) || data.size < offset + 4) {
        return CyclingPowerResult(powerWatts, pedalBalanceLeftPercent)
    }
    return CyclingPowerResult(
        powerWatts = powerWatts,
        pedalBalanceLeftPercent = pedalBalanceLeftPercent,
        crank =
            CrankReading(
                revolutions = readUint16(data, offset),
                eventTime = readUint16(data, offset + 2),
            ),
    )
}

// Cycling Power Measurement flag bits (Bluetooth SIG). Only the ones that
// change where a later field starts are named — getting one wrong silently
// shifts every subsequent offset rather than failing loudly.
private const val FLAG_PEDAL_BALANCE = 0x0001
private const val FLAG_ACCUMULATED_TORQUE = 0x0004
private const val FLAG_WHEEL_REVOLUTION_DATA = 0x0010
private const val FLAG_CRANK_REVOLUTION_DATA = 0x0020

/** uint16 flags + mandatory sint16 instantaneous power. */
private const val CYCLING_POWER_HEADER_BYTES = 4

private infix fun Int.hasFlag(flag: Int): Boolean = (this and flag) != 0

/**
 * Turns successive cumulative crank readings into an instantaneous cadence.
 * Shared by the CSC (0x2A5B) and Cycling Power (0x2A63) parsers, which carry
 * the identical field pair: cumulative revolutions and an event time in
 * 1/1024 s, both wrapping at 65536. Returns null until a baseline exists and
 * whenever no time has elapsed between readings.
 *
 * Both counters wrap every [COUNTER_WRAP_MS], and most crank sensors stop
 * notifying entirely once the crank stops rather than reporting 0 rpm. A
 * baseline older than one wrap is therefore not merely stale but ambiguous —
 * the modular delta against it is indistinguishable from a much shorter one,
 * so a rider pulling away from a long red light would be credited a cadence
 * several times what they are actually turning. Past that age the reading is
 * treated as a fresh baseline instead, which costs one sample of cadence and
 * is the only honest option: nothing in the packet can disambiguate the gap.
 */
internal class CrankRevolutionTracker {
    private var lastRevolutions: Int? = null
    private var lastEventTime: Int? = null
    private var lastReadingAtMs: Long? = null

    fun cadenceFrom(
        revolutions: Int,
        eventTime: Int,
        nowMs: Long,
    ): Int? {
        val previousRevolutions = lastRevolutions
        val previousEventTime = lastEventTime
        val previousReadingAtMs = lastReadingAtMs
        lastRevolutions = revolutions
        lastEventTime = eventTime
        lastReadingAtMs = nowMs
        if (previousRevolutions == null || previousEventTime == null) return null
        if (previousReadingAtMs != null && nowMs - previousReadingAtMs >= COUNTER_WRAP_MS) return null

        val deltaRevolutions = (revolutions - previousRevolutions + COUNTER_MODULUS) % COUNTER_MODULUS
        val deltaTime = (eventTime - previousEventTime + COUNTER_MODULUS) % COUNTER_MODULUS
        if (deltaTime <= 0) return null
        return (deltaRevolutions.toDouble() * EVENT_TIME_TICKS_PER_SECOND * 60.0 / deltaTime.toDouble()).toInt()
    }

    fun reset() {
        lastRevolutions = null
        lastEventTime = null
        lastReadingAtMs = null
    }

    private companion object {
        /** Both crank counters are uint16. */
        const val COUNTER_MODULUS = 0x10000

        /** Crank event time is expressed in 1/1024 s units. */
        const val EVENT_TIME_TICKS_PER_SECOND = 1024.0

        /** How long the event-time counter takes to wrap: 65536 / 1024 s. */
        const val COUNTER_WRAP_MS = (COUNTER_MODULUS / EVENT_TIME_TICKS_PER_SECOND * 1000.0).toLong()
    }
}

/**
 * Holds the previous crank revolution count / event time from a CSC sensor so
 * the next notification can be turned into an instantaneous cadence (rpm).
 */
internal class CscCadenceTracker {
    private val crankTracker = CrankRevolutionTracker()

    /**
     * Decodes a CSC Measurement (0x2A5B) value and returns the derived cadence in
     * rpm, or null if the packet carries no crank data or this is the first
     * sample (no baseline to diff against). Crank event time is in 1/1024 s units
     * and wraps at 65536.
     */
    fun update(
        data: ByteArray,
        nowMs: Long,
    ): CscResult? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt()
        val wheelPresent = (flags and 0x01) != 0
        val crankPresent = (flags and 0x02) != 0

        var offset = 1
        var wheelRevolutions: Long? = null
        if (wheelPresent) {
            if (data.size < offset + 6) return null
            wheelRevolutions = readUint32(data, offset)
            offset += 6 // uint32 cumulative wheel revs + uint16 last wheel event time
        }
        if (!crankPresent) return CscResult(cadenceRpm = null, wheelRevolutions = wheelRevolutions)
        if (data.size < offset + 4) return CscResult(cadenceRpm = null, wheelRevolutions = wheelRevolutions)

        val cadence =
            crankTracker.cadenceFrom(
                revolutions = readUint16(data, offset),
                eventTime = readUint16(data, offset + 2),
                nowMs = nowMs,
            )
        return CscResult(cadenceRpm = cadence, wheelRevolutions = wheelRevolutions)
    }
}

// File-level so the CSC (0x2A5B) and Cycling Power (0x2A63) parsers share one
// copy of the little-endian decoding rather than drifting apart.
private fun readUint16(
    data: ByteArray,
    offset: Int,
): Int = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

private fun readSint16(
    data: ByteArray,
    offset: Int,
): Int = readUint16(data, offset).toShort().toInt()

private fun readUint32(
    data: ByteArray,
    offset: Int,
): Long =
    (data[offset].toLong() and 0xFF) or
        ((data[offset + 1].toLong() and 0xFF) shl 8) or
        ((data[offset + 2].toLong() and 0xFF) shl 16) or
        ((data[offset + 3].toLong() and 0xFF) shl 24)

internal data class CscResult(
    val cadenceRpm: Int?,
    val wheelRevolutions: Long?,
)
