package com.speedevand.inkride.dashboard.domain

import com.speedevand.inkride.dashboard.model.UserProfile

class CaloriesEstimator {
    fun estimateKcal(
        speedKmh: Double,
        intervalMs: Long,
        userProfile: UserProfile
    ): Double {
        if (intervalMs <= 0L || speedKmh <= 0.0) return 0.0

        val met = speedToMet(speedKmh)
        val ageFactor = ageFactor(userProfile.age)
        val minutes = intervalMs / 60_000.0

        // ACSM approximation: kcal/min = MET * 3.5 * weight(kg) / 200.
        return met * 3.5 * userProfile.weightKg / 200.0 * minutes * ageFactor
    }

    private fun speedToMet(speedKmh: Double): Double = when {
        speedKmh < 16.0 -> 4.0
        speedKmh < 19.0 -> 6.8
        speedKmh < 22.0 -> 8.0
        speedKmh < 25.0 -> 10.0
        else -> 12.0
    }

    private fun ageFactor(age: Int): Double = when {
        age < 30 -> 1.0
        age < 45 -> 0.97
        age < 60 -> 0.94
        else -> 0.9
    }
}

