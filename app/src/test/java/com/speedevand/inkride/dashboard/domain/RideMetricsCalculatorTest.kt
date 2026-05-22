package com.speedevand.inkride.dashboard.domain

import com.speedevand.inkride.dashboard.model.RideSensorSample
import com.speedevand.inkride.dashboard.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideMetricsCalculatorTest {
    private val profile = UserProfile(weightKg = 75.0, age = 32)

    @Test
    fun `auto pause keeps moving timer at zero when speed stays below threshold`() {
        val calculator = RideMetricsCalculator(autoPauseThresholdKmh = 2.0)

        calculator.process(sampleAt(timestampMs = 0L), profile)
        val metrics = calculator.process(sampleAt(timestampMs = 2_000L), profile)

        assertTrue(metrics.isAutoPaused)
        assertEquals(0L, metrics.movingTimeSeconds)
        assertEquals(0.0, metrics.distanceKm, 0.0001)
    }

    @Test
    fun `moving samples accumulate distance speed and calories`() {
        val calculator = RideMetricsCalculator(autoPauseThresholdKmh = 1.5)

        calculator.process(sampleAt(timestampMs = 0L), profile)
        val moving = calculator.process(
            sample = RideSensorSample(
                timestampMs = 5_000L,
                latitude = 52.22990,
                longitude = 21.01200,
                altitudeFromGpsM = 110.0,
                altitudeFromBarometerM = 111.0,
                speedFromGpsMps = 6.0,
                accuracyM = 4f
            ),
            userProfile = profile
        )

        assertFalse(moving.isAutoPaused)
        assertTrue(moving.currentSpeedKmh > 20.0)
        assertTrue(moving.maxSpeedKmh >= moving.currentSpeedKmh)
        assertTrue(moving.distanceKm > 0.0)
        assertTrue(moving.caloriesKcal > 0.0)
    }

    @Test
    fun `positive altitude delta contributes to elevation gain and grade`() {
        val calculator = RideMetricsCalculator()

        calculator.process(sampleAt(timestampMs = 0L, altitudeBarometer = 100.0), profile)
        val metrics = calculator.process(
            sample = RideSensorSample(
                timestampMs = 4_000L,
                latitude = 52.23020,
                longitude = 21.01200,
                altitudeFromGpsM = 104.0,
                altitudeFromBarometerM = 105.5,
                speedFromGpsMps = 4.5,
                accuracyM = 3f
            ),
            userProfile = profile
        )

        assertTrue(metrics.elevationGainM > 0.0)
        assertTrue(metrics.gradePercent > 0.0)
    }

    private fun sampleAt(
        timestampMs: Long,
        altitudeBarometer: Double? = 100.0
    ): RideSensorSample = RideSensorSample(
        timestampMs = timestampMs,
        latitude = 52.22970,
        longitude = 21.01220,
        altitudeFromGpsM = 100.0,
        altitudeFromBarometerM = altitudeBarometer,
        speedFromGpsMps = 0.0,
        accuracyM = 5f
    )
}

