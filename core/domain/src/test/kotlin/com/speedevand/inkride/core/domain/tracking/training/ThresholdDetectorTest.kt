package com.speedevand.inkride.core.domain.tracking.training

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.tracking.PowerSource
import org.junit.jupiter.api.Test

class ThresholdDetectorTest {
    private val detector = ThresholdDetector()
    private val current = AthleteThresholds(ftpWatts = 200, lthrBpm = 160, ageForHrZones = 30)

    // watts is last so a trailing lambda reads as the power series, which is
    // what almost every case here varies.
    private fun measuredRide(
        seconds: Int,
        bpm: (Int) -> Int? = { null },
        watts: (Int) -> Int?,
    ): List<RideSample> =
        (0 until seconds).map { second ->
            RideSample(
                timestampMs = second * 1000L,
                powerWatts = watts(second),
                powerSource = PowerSource.MEASURED,
                heartRateBpm = bpm(second),
            )
        }

    @Test
    fun `proposes FTP as 95 percent of the best twenty minute measured power`() {
        // 30 min at 300 W measured: best 20-min average is 300, so FTP = 285.
        val proposal = detector.detect(measuredRide(1_800) { 300 }, current)

        assertThat(proposal.ftpWatts).isEqualTo(285)
    }

    @Test
    fun `ignores estimated power entirely`() {
        val samples =
            (0 until 1_800).map { second ->
                RideSample(timestampMs = second * 1000L, powerWatts = 400, powerSource = PowerSource.ESTIMATED)
            }

        val proposal = detector.detect(samples, current)

        assertThat(proposal.ftpWatts).isNull()
    }

    @Test
    fun `proposes nothing when the candidate does not beat the current threshold by two percent`() {
        // Best 20-min 210 W -> candidate 199.5 -> 199, below the current 200.
        val proposal = detector.detect(measuredRide(1_800) { 210 }, current)

        assertThat(proposal.ftpWatts).isNull()
    }

    @Test
    fun `proposes nothing for a ride shorter than the twenty minute window`() {
        val noThresholds = AthleteThresholds(ftpWatts = null, lthrBpm = null, ageForHrZones = 30)

        val proposal = detector.detect(measuredRide(600) { 400 }, noThresholds)

        assertThat(proposal.ftpWatts).isNull()
    }

    @Test
    fun `finds the best window when the hard effort is buried mid-ride`() {
        // An hour easy with a hard 20 minutes in the middle. Taking the first or
        // last window, or the whole-ride mean, would all miss it.
        val samples = measuredRide(3_600) { second -> if (second in 1_200..2_399) 320 else 120 }

        val proposal = detector.detect(samples, current)

        assertThat(proposal.ftpWatts).isEqualTo(304)
    }

    @Test
    fun `a window broken by a dropout is not a sustained effort`() {
        // 30 min at 300 W, but the meter drops for one second in every window.
        // Averaging over what survived would credit an effort never sustained.
        val samples = measuredRide(1_800) { second -> if (second % 600 == 0) null else 300 }

        val proposal = detector.detect(samples, current)

        assertThat(proposal.ftpWatts).isNull()
    }

    @Test
    fun `any sustained effort proposes an FTP when the rider has none yet`() {
        val noFtp = AthleteThresholds(ftpWatts = null, lthrBpm = 160, ageForHrZones = 30)

        val proposal = detector.detect(measuredRide(1_800) { 150 }, noFtp)

        // Nothing to beat, so the first measured 20-minute effort stands as a
        // starting point for the rider to accept.
        assertThat(proposal.ftpWatts).isEqualTo(142)
    }

    @Test
    fun `proposes an LTHR from the best sustained heart rate`() {
        val samples = measuredRide(1_800, watts = { null }, bpm = { 175 })

        val proposal = detector.detect(samples, current)

        assertThat(proposal.lthrBpm).isEqualTo(175)
    }

    @Test
    fun `a ride with no heart rate proposes no LTHR`() {
        val proposal = detector.detect(measuredRide(1_800) { 300 }, current)

        assertThat(proposal.lthrBpm).isNull()
    }
}
