package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class RideSampleAssemblerTest {
    @Test
    fun `fresh accurate fix populates GPS fields and feeds the Kalman filter`() {
        val assembler = RideSampleAssembler()
        val fix =
            RawGpsFix(
                latitude = 50.0,
                longitude = 19.0,
                accuracyM = 5.0f,
                fixTimeMs = 1_000L,
                speedMps = 5.0f,
                bearingDeg = 90f,
                altitudeM = 120.0,
                satelliteCount = 8,
            )

        val sample =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = 1013.25,
                altitudeFromBarometerM = 100.0,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
            )

        // First fix ever fed to a fresh PositionKalmanFilter passes through unchanged
        // (see PositionKalmanFilterTest's own "first fix passes through unchanged" case).
        assertThat(sample.latitude).isEqualTo(50.0)
        assertThat(sample.longitude).isEqualTo(19.0)
        assertThat(sample.altitudeFromGpsM).isEqualTo(120.0)
        assertThat(sample.speedFromGpsMps).isEqualTo(5.0)
        assertThat(sample.accuracyM).isEqualTo(5.0f)
        assertThat(sample.satelliteCount).isEqualTo(8)
        assertThat(sample.bearingDegrees).isEqualTo(90f)
        assertThat(sample.altitudeFromBarometerM).isEqualTo(100.0)
        assertThat(sample.pressureHpa).isEqualTo(1013.25)
    }

    @Test
    fun `unusable fix nulls GPS fields while barometer and heading still flow, even on the very first call`() {
        val assembler = RideSampleAssembler()

        val sample =
            assembler.assemble(
                rawFix = null,
                pressureHpa = 1013.25,
                altitudeFromBarometerM = 100.0,
                smoothedHeadingDeg = 45f,
                nowMs = 1_000L,
            )

        assertThat(sample.latitude).isNull()
        assertThat(sample.longitude).isNull()
        assertThat(sample.altitudeFromGpsM).isNull()
        assertThat(sample.speedFromGpsMps).isNull()
        assertThat(sample.accuracyM).isNull()
        assertThat(sample.satelliteCount).isNull()
        assertThat(sample.bearingDegrees).isEqualTo(45f)
        assertThat(sample.altitudeFromBarometerM).isEqualTo(100.0)
        assertThat(sample.pressureHpa).isEqualTo(1013.25)
    }

    @Test
    fun `same fix time is not re-fed to the Kalman filter`() {
        val assembler = RideSampleAssembler()
        val firstFix = RawGpsFix(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, fixTimeMs = 1_000L)
        val first =
            assembler.assemble(
                rawFix = firstFix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
            )
        assertThat(first.latitude).isEqualTo(50.0)
        assertThat(first.longitude).isEqualTo(19.0)

        // Same fixTimeMs, deliberately different lat/lon: proves the dedup guard
        // keys purely off fixTimeMs. If the filter were re-fed, it would blend
        // toward these new values instead of returning the cached result.
        val repeatedTimeFix = firstFix.copy(latitude = 99.0, longitude = 88.0)
        val second =
            assembler.assemble(
                rawFix = repeatedTimeFix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
            )

        assertThat(second.latitude).isEqualTo(50.0)
        assertThat(second.longitude).isEqualTo(19.0)
    }

    @Test
    fun `a new fix time feeds the Kalman filter again`() {
        val assembler = RideSampleAssembler()
        val firstFix = RawGpsFix(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, fixTimeMs = 1_000L)
        assembler.assemble(
            rawFix = firstFix,
            pressureHpa = null,
            altitudeFromBarometerM = null,
            smoothedHeadingDeg = null,
            nowMs = 1_000L,
        )

        // Use a 100-second window instead of 1 second to keep the velocity reasonable
        // and avoid gating the measurement. The 0.001 degree offset (~111m) over
        // 100 seconds gives ~1.1 m/s, well within expected cycling speeds.
        val secondFix = firstFix.copy(latitude = 50.001, fixTimeMs = 101_000L)
        val second =
            assembler.assemble(
                rawFix = secondFix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 101_000L,
            )

        // A genuinely new fix is blended by the filter, not passed through: the
        // result moves toward 50.001 but isn't exactly equal to either the old
        // (50.0) or new (50.001) raw value.
        assertThat(second.latitude).isNotNull().isGreaterThan(50.0)
        assertThat(second.latitude).isNotNull().isLessThan(50.001)
    }

    @Test
    fun `fast GPS bearing is used above the minimum speed`() {
        val assembler = RideSampleAssembler(gpsBearingMinSpeedMps = 2.0f)
        val fix =
            RawGpsFix(
                latitude = 50.0,
                longitude = 19.0,
                accuracyM = 5.0f,
                fixTimeMs = 1_000L,
                speedMps = 3.0f,
                bearingDeg = 200f,
            )

        val sample =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = 10f,
                nowMs = 1_000L,
            )

        assertThat(sample.bearingDegrees).isEqualTo(200f)
    }

    @Test
    fun `fast GPS bearing is used exactly at the minimum speed threshold`() {
        val assembler = RideSampleAssembler()
        val fix =
            RawGpsFix(
                latitude = 50.0,
                longitude = 19.0,
                accuracyM = 5.0f,
                fixTimeMs = 1_000L,
                speedMps = 2.0f,
                bearingDeg = 200f,
            )

        val sample =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = 10f,
                nowMs = 1_000L,
            )

        // The >= comparison in the original code means the boundary itself
        // counts as "fast enough" to trust GPS bearing.
        assertThat(sample.bearingDegrees).isEqualTo(200f)
    }

    @Test
    fun `slow speed falls back to smoothed heading`() {
        val assembler = RideSampleAssembler(gpsBearingMinSpeedMps = 2.0f)
        val fix =
            RawGpsFix(
                latitude = 50.0,
                longitude = 19.0,
                accuracyM = 5.0f,
                fixTimeMs = 1_000L,
                speedMps = 1.0f,
                bearingDeg = 200f,
            )

        val sample =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = 10f,
                nowMs = 1_000L,
            )

        assertThat(sample.bearingDegrees).isEqualTo(10f)
    }

    @Test
    fun `unusable GPS falls back to smoothed heading`() {
        val assembler = RideSampleAssembler()

        val sample =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = 77f,
                nowMs = 1_000L,
            )

        assertThat(sample.bearingDegrees).isEqualTo(77f)
    }

    @Test
    fun `bearing is null when both GPS bearing and heading are absent`() {
        val assembler = RideSampleAssembler()

        val sample =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
            )

        assertThat(sample.bearingDegrees).isNull()
    }

    @Test
    fun `non-finite smoothed heading is dropped to null`() {
        val assembler = RideSampleAssembler()

        val sample =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = Float.NaN,
                nowMs = 1_000L,
            )

        assertThat(sample.bearingDegrees).isNull()
    }

    @Test
    fun `non-finite GPS bearing is dropped to null`() {
        val assembler = RideSampleAssembler(gpsBearingMinSpeedMps = 2.0f)
        val fix =
            RawGpsFix(
                latitude = 50.0,
                longitude = 19.0,
                accuracyM = 5.0f,
                fixTimeMs = 1_000L,
                speedMps = 5.0f,
                bearingDeg = Float.POSITIVE_INFINITY,
            )

        val sample =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
            )

        assertThat(sample.bearingDegrees).isNull()
    }

    @Test
    fun `heading bearing outside 0 360 is normalized`() {
        val assembler = RideSampleAssembler()

        val sample =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = -10f,
                nowMs = 1_000L,
            )

        assertThat(sample.bearingDegrees).isEqualTo(350f)
    }

    @Test
    fun `GPS bearing outside 0 360 is normalized`() {
        val assembler = RideSampleAssembler(gpsBearingMinSpeedMps = 2.0f)
        val fix =
            RawGpsFix(
                latitude = 50.0,
                longitude = 19.0,
                accuracyM = 5.0f,
                fixTimeMs = 1_000L,
                speedMps = 5.0f,
                bearingDeg = 370f,
            )

        val sample =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
            )

        assertThat(sample.bearingDegrees).isEqualTo(10f)
    }

    @Test
    fun `sample timestamp is the emit time`() {
        val assembler = RideSampleAssembler()

        val sample =
            assembler.assemble(
                rawFix = null,
                pressureHpa = 1013.25,
                altitudeFromBarometerM = 100.0,
                smoothedHeadingDeg = 42f,
                nowMs = 7_500L,
            )

        assertThat(sample.timestampMs).isEqualTo(7_500L)
    }

    @Test
    fun `barometer altitude is forwarded unchanged, including null`() {
        val assembler = RideSampleAssembler()

        val withAltitude =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = 123.4,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
            )
        assertThat(withAltitude.altitudeFromBarometerM).isEqualTo(123.4)

        val withoutAltitude =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
            )
        assertThat(withoutAltitude.altitudeFromBarometerM).isNull()
    }

    @Test
    fun `bearing falls back to the Kalman velocity heading when GPS course is untrustworthy`() {
        val assembler = RideSampleAssembler()

        // Seed the filter with two northbound fixes so it builds a velocity estimate.
        assembler.assemble(
            rawFix = RawGpsFix(52.0, 0.0, accuracyM = 4f, fixTimeMs = 1_000L, speedMps = 1.0f),
            pressureHpa = null,
            altitudeFromBarometerM = null,
            smoothedHeadingDeg = null,
            nowMs = 1_000L,
        )
        val sample =
            assembler.assemble(
                // speedMps below gpsBearingMinSpeedMps, so GPS course-over-ground is
                // rejected; no compass heading either. The Kalman velocity bearing
                // is the only source left and must be used.
                rawFix = RawGpsFix(52.00018, 0.0, accuracyM = 4f, fixTimeMs = 2_000L, speedMps = 1.0f, bearingDeg = 270f),
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 2_000L,
            )

        assertThat(sample.bearingDegrees).isNotNull()
        // Travelling due north: bearing near 0/360, definitely not the rejected 270.
        assertThat(sample.bearingDegrees!!).isLessThan(45f)
    }

    @Test
    fun `cached Kalman result is suppressed on a null fix but reused when the same fix reappears`() {
        val assembler = RideSampleAssembler()
        val fix = RawGpsFix(latitude = 50.0, longitude = 19.0, accuracyM = 5.0f, fixTimeMs = 1_000L)

        val first =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 1_000L,
            )
        assertThat(first.latitude).isEqualTo(50.0)

        val whileUnusable =
            assembler.assemble(
                rawFix = null,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 2_000L,
            )
        assertThat(whileUnusable.latitude).isNull()
        assertThat(whileUnusable.longitude).isNull()

        val sameFixReappears =
            assembler.assemble(
                rawFix = fix,
                pressureHpa = null,
                altitudeFromBarometerM = null,
                smoothedHeadingDeg = null,
                nowMs = 3_000L,
            )
        assertThat(sameFixReappears.latitude).isEqualTo(50.0)
        assertThat(sameFixReappears.longitude).isEqualTo(19.0)
    }
}
