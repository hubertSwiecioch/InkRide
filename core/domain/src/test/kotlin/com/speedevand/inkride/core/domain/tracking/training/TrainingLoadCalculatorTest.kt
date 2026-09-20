package com.speedevand.inkride.core.domain.tracking.training

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
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

    @Test
    fun `an hour at LTHR scores 100 hrTSS`() {
        val calculator = TrainingLoadCalculator()
        val hrOnly = AthleteThresholds(ftpWatts = null, lthrBpm = 160, ageForHrZones = 30)
        var metrics = TrainingMetrics()

        for (second in 0..3_600) {
            metrics = calculator.process(second * 1000L, null, null, 160, null, true, hrOnly)
        }

        assertThat(metrics.hrTss!!).isCloseTo(100.0, 2.0)
    }

    @Test
    fun `time accumulates into the heart-rate zone the rider is in`() {
        val calculator = TrainingLoadCalculator()
        val hrOnly = AthleteThresholds(ftpWatts = null, lthrBpm = 160, ageForHrZones = 30)
        var metrics = TrainingMetrics()

        // HRmax(Tanaka, 30) = 187. 120 bpm is 64 % -> zone 2.
        for (second in 0..600) {
            metrics = calculator.process(second * 1000L, null, null, 120, null, true, hrOnly)
        }

        assertThat(metrics.currentHrZone).isEqualTo(2)
        assertThat(metrics.secondsInHrZone[2]!!).isBetween(595L, 605L)
    }

    @Test
    fun `hrTSS and TRIMP are absent without a heart-rate reading`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        for (second in 0..600) {
            metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
        }

        assertThat(metrics.hrTss).isNull()
        assertThat(metrics.trimp).isNull()
    }

    @Test
    fun `TRIMP weights each minute by the zone it was spent in`() {
        val calculator = TrainingLoadCalculator()
        val hrOnly = AthleteThresholds(ftpWatts = null, lthrBpm = 160, ageForHrZones = 30)
        var metrics = TrainingMetrics()

        // Ten minutes at 170 bpm. HRmax(30) = 187, so 91 % -> zone 5, and
        // Edwards TRIMP counts each of those minutes five times.
        for (second in 1..600) {
            metrics = calculator.process(second * 1000L, null, null, 170, null, true, hrOnly)
        }

        assertThat(metrics.trimp!!).isCloseTo(50.0, 0.5)
    }

    @Test
    fun `time accumulates into the power zone the rider is in`() {
        val calculator = TrainingLoadCalculator()
        val atFtp = AthleteThresholds(ftpWatts = 200, lthrBpm = 160, ageForHrZones = 30)
        var metrics = TrainingMetrics()

        // 200 W against a 200 W FTP is 100 % -> zone 4.
        for (second in 1..600) {
            metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, atFtp)
        }

        assertThat(metrics.currentPowerZone).isEqualTo(4)
        assertThat(metrics.secondsInPowerZone[4]!!).isBetween(595L, 605L)
    }

    @Test
    fun `a stopped rider adds no time to any zone`() {
        val calculator = TrainingLoadCalculator()
        val atFtp = AthleteThresholds(ftpWatts = 200, lthrBpm = 160, ageForHrZones = 30)
        var metrics = TrainingMetrics()

        for (second in 1..300) {
            metrics = calculator.process(second * 1000L, 0, PowerSource.MEASURED, 100, null, false, atFtp)
        }

        // Standing at a light is not training time, in either zone map.
        assertThat(metrics.secondsInHrZone).isEmpty()
        assertThat(metrics.secondsInPowerZone).isEmpty()
    }

    @Test
    fun `raising FTP mid-ride moves later seconds into the recomputed zone`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        // 200 W against FTP 200 is zone 4...
        for (second in 1..60) {
            metrics =
                calculator.process(
                    second * 1000L,
                    200,
                    PowerSource.MEASURED,
                    null,
                    null,
                    true,
                    AthleteThresholds(ftpWatts = 200, lthrBpm = 160, ageForHrZones = 30),
                )
        }
        assertThat(metrics.currentPowerZone).isEqualTo(4)

        // ...and against a raised FTP of 300 the same watts are 67 %, so zone 2.
        // The calculator must read the thresholds it is handed each call rather
        // than cache the ones it saw first.
        for (second in 61..120) {
            metrics =
                calculator.process(
                    second * 1000L,
                    200,
                    PowerSource.MEASURED,
                    null,
                    null,
                    true,
                    AthleteThresholds(ftpWatts = 300, lthrBpm = 160, ageForHrZones = 30),
                )
        }

        assertThat(metrics.currentPowerZone).isEqualTo(2)
    }

    @Test
    fun `VAM reports vertical metres per hour over the recent window`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        // Climbing 0.25 m/s = 900 m/h, for two full windows.
        for (second in 0..180) {
            val altitude = 100.0 + second * 0.25
            metrics = calculator.process(second * 1000L, 250, PowerSource.MEASURED, null, altitude, true, thresholds)
        }

        assertThat(metrics.vamMetersPerHour!!).isCloseTo(900.0, 50.0)
    }

    @Test
    fun `work in kilojoules is the integral of watts over moving time`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        // 200 W for 600 s = 120 kJ.
        for (second in 1..600) {
            metrics = calculator.process(second * 1000L, 200, PowerSource.MEASURED, null, null, true, thresholds)
        }

        assertThat(metrics.workKj).isCloseTo(120.0, 1.0)
    }

    @Test
    fun `VAM goes negative on a descent rather than reporting the magnitude`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        // Losing 0.5 m/s = -1800 m/h. Reporting 1800 would make a descent look
        // like the hardest climb of the ride.
        for (second in 0..180) {
            val altitude = 500.0 - second * 0.5
            metrics = calculator.process(second * 1000L, 100, PowerSource.MEASURED, null, altitude, true, thresholds)
        }

        assertThat(metrics.vamMetersPerHour!!).isCloseTo(-1800.0, 100.0)
    }

    @Test
    fun `VAM only reflects the trailing window, not the whole climb`() {
        val calculator = TrainingLoadCalculator()
        var metrics = TrainingMetrics()

        // Five minutes climbing hard, then a minute of flat. A whole-ride
        // average would still show the climb; the rider needs the flat.
        for (second in 0..300) {
            metrics =
                calculator.process(second * 1000L, 250, PowerSource.MEASURED, null, 100.0 + second * 0.5, true, thresholds)
        }
        for (second in 301..420) {
            metrics = calculator.process(second * 1000L, 250, PowerSource.MEASURED, null, 250.0, true, thresholds)
        }

        assertThat(metrics.vamMetersPerHour!!).isCloseTo(0.0, 30.0)
    }

    @Test
    fun `VAM is absent until the barometer has given it something to compare`() {
        val calculator = TrainingLoadCalculator()

        val metrics = calculator.process(0L, 250, PowerSource.MEASURED, null, 100.0, true, thresholds)

        assertThat(metrics.vamMetersPerHour).isNull()
    }
}
