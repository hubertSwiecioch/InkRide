package com.speedevand.inkride.ble.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class BleGattTest {
    @Test
    fun `parseHeartRate reads an 8-bit value`() {
        val data = byteArrayOf(0x00, 75)
        assertThat(parseHeartRate(data)).isEqualTo(75)
    }

    @Test
    fun `parseHeartRate reads a 16-bit value`() {
        // Flags bit0 set → 16-bit little-endian value 0x012C = 300.
        val data = byteArrayOf(0x01, 0x2C, 0x01)
        assertThat(parseHeartRate(data)).isEqualTo(300)
    }

    @Test
    fun `parseHeartRate returns null on an empty packet`() {
        assertThat(parseHeartRate(byteArrayOf())).isNull()
    }

    @Test
    fun `CscCadenceTracker yields null on the first crank sample`() {
        val tracker = CscCadenceTracker()
        // flags 0x02 (crank present), revs = 10, eventTime = 0
        val result = tracker.update(byteArrayOf(0x02, 0x0A, 0x00, 0x00, 0x00))
        assertThat(result?.cadenceRpm).isNull()
    }

    @Test
    fun `CscCadenceTracker computes 60 rpm for one rev per second`() {
        val tracker = CscCadenceTracker()
        tracker.update(byteArrayOf(0x02, 0x0A, 0x00, 0x00, 0x00))
        // +1 revolution, +1024 ticks (= 1 second at 1/1024 s resolution).
        val result = tracker.update(byteArrayOf(0x02, 0x0B, 0x00, 0x00, 0x04))
        assertThat(result?.cadenceRpm).isEqualTo(60)
    }

    @Test
    fun `CscCadenceTracker handles crank-event-time wraparound`() {
        val tracker = CscCadenceTracker()
        // Start near the uint16 ceiling: time = 65535.
        tracker.update(byteArrayOf(0x02, 0x0A, 0x00, 0xFF.toByte(), 0xFF.toByte()))
        // Wrap to 1023 → delta = 1024 ticks, +1 rev → 60 rpm.
        val result = tracker.update(byteArrayOf(0x02, 0x0B, 0x00, 0xFF.toByte(), 0x03))
        assertThat(result?.cadenceRpm).isEqualTo(60)
    }

    @Test
    fun `parses instantaneous power from a minimal packet`() {
        // flags = 0x0000 (no optional fields), power = 250 W little-endian.
        val packet = byteArrayOf(0x00, 0x00, 0xFA.toByte(), 0x00)

        val result = parseCyclingPower(packet)

        assertThat(result).isNotNull()
        assertThat(result!!.powerWatts).isEqualTo(250)
        assertThat(result.pedalBalanceLeftPercent).isNull()
        assertThat(result.crankRevolutions).isNull()
    }

    @Test
    fun `parses pedal balance when the flag is set`() {
        // flags bit 0 set, power = 200 W, balance = 0x64 (100 half-percent = 50%).
        val packet = byteArrayOf(0x01, 0x00, 0xC8.toByte(), 0x00, 0x64)

        val result = parseCyclingPower(packet)

        assertThat(result!!.pedalBalanceLeftPercent).isEqualTo(50)
    }

    @Test
    fun `parses crank revolution data when the flag is set`() {
        // flags bit 5 set (0x0020), power = 200 W, revs = 1000, event time = 2048.
        val packet = byteArrayOf(0x20, 0x00, 0xC8.toByte(), 0x00, 0xE8.toByte(), 0x03, 0x00, 0x08)

        val result = parseCyclingPower(packet)

        assertThat(result!!.crankRevolutions).isEqualTo(1000)
        assertThat(result.crankEventTime).isEqualTo(2048)
    }

    @Test
    fun `skips the optional fields between balance and crank data`() {
        // flags 0x0035: pedal balance (bit 0), accumulated torque (bit 2),
        // wheel revolution data (bit 4) and crank data (bit 5) all present.
        // Without skipping torque and wheel data, the crank offset lands on the
        // wrong bytes and the revolutions come back as garbage.
        val packet =
            byteArrayOf(
                0x35,
                0x00, // flags
                0xC8.toByte(),
                0x00, // power = 200 W
                0x64, // pedal balance = 50%
                0x11,
                0x22, // accumulated torque (skipped)
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06, // wheel revs + event time (skipped)
                0xE8.toByte(),
                0x03,
                0x00,
                0x08, // crank revs = 1000, event time = 2048
            )

        val result = parseCyclingPower(packet)

        assertThat(result!!.powerWatts).isEqualTo(200)
        assertThat(result.pedalBalanceLeftPercent).isEqualTo(50)
        assertThat(result.crankRevolutions).isEqualTo(1000)
        assertThat(result.crankEventTime).isEqualTo(2048)
    }

    @Test
    fun `negative power from a coasting sensor is preserved as signed`() {
        // power = -5 W (0xFFFB), which some meters report while coasting.
        val packet = byteArrayOf(0x00, 0x00, 0xFB.toByte(), 0xFF.toByte())

        assertThat(parseCyclingPower(packet)!!.powerWatts).isEqualTo(-5)
    }

    @Test
    fun `returns null for packets too short to carry power`() {
        assertThat(parseCyclingPower(byteArrayOf())).isNull()
        assertThat(parseCyclingPower(byteArrayOf(0x00, 0x00, 0x10))).isNull()
    }

    @Test
    fun `returns power but no crank data when the packet is truncated mid-field`() {
        // Crank flag set but only two of the four crank bytes present.
        val packet = byteArrayOf(0x20, 0x00, 0xC8.toByte(), 0x00, 0xE8.toByte(), 0x03)

        val result = parseCyclingPower(packet)

        assertThat(result!!.powerWatts).isEqualTo(200)
        assertThat(result.crankRevolutions).isNull()
    }
}
