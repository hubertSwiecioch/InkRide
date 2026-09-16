package com.speedevand.inkride.core.domain.tracking.training

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.tracking.PowerSource
import org.junit.jupiter.api.Test

class DecouplingCalculatorTest {
    private val calculator = DecouplingCalculator()

    @Test
    fun `a steady ride decouples by roughly nothing`() {
        val samples =
            (0 until 3_600).map { second ->
                RideSample(
                    timestampMs = second * 1000L,
                    powerWatts = 200,
                    powerSource = PowerSource.MEASURED,
                    heartRateBpm = 150,
                )
            }

        assertThat(calculator.calculate(samples)!!).isCloseTo(0.0, 0.5)
    }

    @Test
    fun `drifting heart rate at constant power shows positive decoupling`() {
        val samples =
            (0 until 3_600).map { second ->
                // Same watts, heart rate climbing 150 -> 165 across the ride.
                RideSample(
                    timestampMs = second * 1000L,
                    powerWatts = 200,
                    powerSource = PowerSource.MEASURED,
                    heartRateBpm = 150 + second / 240,
                )
            }

        assertThat(calculator.calculate(samples)!!).isGreaterThan(3.0)
    }

    @Test
    fun `estimated power yields no decoupling`() {
        val samples =
            (0 until 3_600).map { second ->
                RideSample(
                    timestampMs = second * 1000L,
                    powerWatts = 200,
                    powerSource = PowerSource.ESTIMATED,
                    heartRateBpm = 150,
                )
            }

        assertThat(calculator.calculate(samples)).isNull()
    }

    @Test
    fun `fading power at constant heart rate also counts as decoupling`() {
        val samples =
            (0 until 3_600).map { second ->
                // The other way a rider drifts: same heart rate, less power.
                RideSample(
                    timestampMs = second * 1000L,
                    powerWatts = 200 - second / 240,
                    powerSource = PowerSource.MEASURED,
                    heartRateBpm = 150,
                )
            }

        assertThat(calculator.calculate(samples)!!).isGreaterThan(3.0)
    }

    @Test
    fun `a rider getting stronger decouples negatively rather than being clamped`() {
        val samples =
            (0 until 3_600).map { second ->
                // Heart rate settling while power holds: negative drift is real
                // and reporting it as zero would hide a genuinely good ride.
                RideSample(
                    timestampMs = second * 1000L,
                    powerWatts = 200,
                    powerSource = PowerSource.MEASURED,
                    heartRateBpm = 165 - second / 240,
                )
            }

        assertThat(calculator.calculate(samples)!!).isLessThan(-3.0)
    }

    @Test
    fun `a ride too short to have two comparable halves yields nothing`() {
        val samples =
            (0 until 120).map { second ->
                RideSample(
                    timestampMs = second * 1000L,
                    powerWatts = 200,
                    powerSource = PowerSource.MEASURED,
                    heartRateBpm = 150,
                )
            }

        assertThat(calculator.calculate(samples)).isNull()
    }

    @Test
    fun `a ride with power but no heart rate yields nothing`() {
        val samples =
            (0 until 3_600).map { second ->
                RideSample(timestampMs = second * 1000L, powerWatts = 200, powerSource = PowerSource.MEASURED)
            }

        assertThat(calculator.calculate(samples)).isNull()
    }
}
