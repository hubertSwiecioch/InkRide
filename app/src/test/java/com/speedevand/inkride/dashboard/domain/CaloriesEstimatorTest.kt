package com.speedevand.inkride.dashboard.domain

import com.speedevand.inkride.dashboard.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaloriesEstimatorTest {
    private val estimator = CaloriesEstimator()

    @Test
    fun `returns zero for stopped ride`() {
        val kcal = estimator.estimateKcal(
            speedKmh = 0.0,
            intervalMs = 60_000L,
            userProfile = UserProfile(weightKg = 70.0, age = 30)
        )

        assertEquals(0.0, kcal, 0.0)
    }

    @Test
    fun `higher speed burns more calories for same rider and interval`() {
        val profile = UserProfile(weightKg = 70.0, age = 30)

        val easy = estimator.estimateKcal(speedKmh = 15.0, intervalMs = 60_000L, userProfile = profile)
        val intense = estimator.estimateKcal(speedKmh = 28.0, intervalMs = 60_000L, userProfile = profile)

        assertTrue(intense > easy)
    }
}

