package com.speedevand.inkride.core.domain.tracking.training

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.tracking.PowerSource
import org.junit.jupiter.api.Test

class TrainingLoadCalculatorTest {
    private val thresholds = AthleteThresholds(ftpWatts = 250, lthrBpm = 165, ageForHrZones = 30)

    @Test
    fun `normalized power equals average power for a perfectly steady effort`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        // 10 minutes at a flat 200 W. With no variability, NP collapses to the mean.
        for (second in 0..600) {
            metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
        }

        assertThat(metrics.normalizedPowerWatts!!).isBetween(198, 202)
    }

    @Test
    fun `normalized power exceeds average power for a variable effort`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        // Alternating 100/300 W minutes: same 200 W mean, higher physiological cost.
        for (second in 0..1_200) {
            val watts = if ((second / 60) % 2 == 0) 100 else 300
            metrics = calculator.process(second * 1000L, watts, PowerSource.MEASURED, null, null, true, thresholds)
        }

        assertThat(metrics.normalizedPowerWatts!!).isGreaterThan(210)
    }

    @Test
    fun `IF and TSS follow from NP and FTP`() {
        val calculator = TrainingLoadCalculator()
        val atFtp = AthleteThresholds(ftpWatts = 200, lthrBpm = 165, ageForHrZones = 30)
        var metrics = TrainingMetrics()

        // Exactly one hour at exactly FTP: IF = 1.0 and TSS = 100 by definition.
        for (second in 0..3_600) {
            metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, atFtp)
        }

        assertThat(metrics.intensityFactor!!).isCloseTo(1.0, 0.02)
        assertThat(metrics.trainingStressScore!!).isCloseTo(100.0, 2.0)
    }

    @Test
    fun `estimated power never produces NP, IF or TSS`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        for (second in 0..600) {
            metrics = calculator.process(second * 1000L, 200, PowerSource.ESTIMATED, null, null, true, thresholds)
        }

        assertThat(metrics.normalizedPowerWatts).isNull()
        assertThat(metrics.intensityFactor).isNull()
        assertThat(metrics.trainingStressScore).isNull()
    }

    @Test
    fun `IF and TSS are absent without an FTP rather than guessed`() {
        val calculator = TrainingLoadCalculator()
        val noFtp = AthleteThresholds(ftpWatts = null, lthrBpm = 165, ageForHrZones = 30)
        var metrics = TrainingMetrics()

        for (second in 0..600) {
            metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, noFtp)
        }

        assertThat(metrics.normalizedPowerWatts).isNotNull()
        assertThat(metrics.intensityFactor).isNull()
        assertThat(metrics.trainingStressScore).isNull()
    }

    @Test
    fun `stopped seconds do not dilute normalized power`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        for (second in 0..600) {
            metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
        }
        // Five minutes at a red light, zero watts, not moving.
        for (second in 601..900) {
            metrics = calculator.process(second * 1000L, 0, PowerSource.MEASURED, null, null, false, thresholds)
        }

        assertThat(metrics.normalizedPowerWatts!!).isBetween(198, 202)
    }

    @Test
    fun `normalized power is absent until a full window has been ridden`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        // 20 s is not enough to fill the 30-second rolling average, and an NP
        // built on a partial window would read high on any hard start.
        for (second in 0..20) {
            metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
        }
        assertThat(metrics.normalizedPowerWatts).isNull()

        for (second in 21..40) {
            metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
        }
        assertThat(metrics.normalizedPowerWatts).isNotNull()
    }

    @Test
    fun `work accumulates in kilojoules from watt-seconds`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        // 100 W for 100 s = 10 000 J = 10 kJ.
        for (second in 1..100) {
            metrics = calculator.process(second * 1000L, 100, PowerSource.MEASURED, null, null, true, thresholds)
        }

        assertThat(metrics.workKj).isCloseTo(10.0, 0.2)
    }

    @Test
    fun `reset clears accumulated load so the next ride starts from zero`() {
        val calculator = TrainingLoadCalculator()
        for (second in 0..600) {
            calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
        }

        calculator.reset()
        val metrics = calculator.process(0L, 200, PowerSource.MEASURED, null, null, true, thresholds)

        assertThat(metrics.normalizedPowerWatts).isNull()
        // Only the single post-reset second counts: 200 W for 1 s = 0.2 kJ.
        assertThat(metrics.workKj).isCloseTo(0.2, 0.01)
    }
}
