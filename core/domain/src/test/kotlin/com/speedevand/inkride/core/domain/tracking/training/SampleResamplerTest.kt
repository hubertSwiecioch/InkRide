package com.speedevand.inkride.core.domain.tracking.training

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class SampleResamplerTest {
    @Test
    fun `emits exactly one second per elapsed second regardless of input rate`() {
        val resampler = SampleResampler()

        // Four inputs inside one second must not produce four seconds.
        assertThat(resampler.accept(0L, 200, 140, 100.0, true)).hasSize(1)
        assertThat(resampler.accept(250L, 210, 141, 100.0, true)).isEmpty()
        assertThat(resampler.accept(500L, 220, 142, 100.0, true)).isEmpty()
        assertThat(resampler.accept(750L, 230, 143, 100.0, true)).isEmpty()

        assertThat(resampler.accept(1_000L, 240, 144, 101.0, true)).hasSize(1)
    }

    @Test
    fun `holds the last reading across a multi-second gap`() {
        val resampler = SampleResampler()
        resampler.accept(0L, 200, 140, 100.0, true)

        // A 4 s gap: the three missing seconds are filled with the held value,
        // so a window never silently shortens because a sensor went quiet.
        val filled = resampler.accept(4_000L, 260, 150, 104.0, true)

        assertThat(filled).hasSize(4)
        assertThat(filled.map { it.powerWatts }).isEqualTo(listOf(200, 200, 200, 260))
        assertThat(filled.last().timestampMs).isEqualTo(4_000L)
    }

    @Test
    fun `a sub-second update refreshes what a later gap fill holds`() {
        val resampler = SampleResampler()
        resampler.accept(0L, 200, 140, 100.0, true)
        // Emits nothing, but must become the value held across the next gap —
        // otherwise a fill replays a reading the sensors already superseded.
        resampler.accept(400L, 300, 160, 100.0, true)

        val filled = resampler.accept(3_000L, 260, 150, 103.0, true)

        assertThat(filled.map { it.powerWatts }).isEqualTo(listOf(300, 300, 260))
    }

    @Test
    fun `a long dropout is not allowed to manufacture held data without limit`() {
        val resampler = SampleResampler(maxHoldSeconds = 5)
        resampler.accept(0L, 200, 140, 100.0, true)

        // 60 s of silence: holding a minute of invented power would inflate
        // every window built on it, so the fill is capped.
        val filled = resampler.accept(60_000L, 260, 150, 100.0, true)

        assertThat(filled).hasSize(6)
        assertThat(filled.map { it.powerWatts }).isEqualTo(listOf(200, 200, 200, 200, 200, 260))
    }

    @Test
    fun `reset clears the held reading`() {
        val resampler = SampleResampler()
        resampler.accept(0L, 200, 140, 100.0, true)
        resampler.reset()

        assertThat(resampler.accept(5_000L, 100, 120, 100.0, true)).hasSize(1)
    }
}
