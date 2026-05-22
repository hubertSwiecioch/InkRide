package com.speedevand.inkride.dashboard.presentation

import com.speedevand.inkride.dashboard.model.RideMetrics
import com.speedevand.inkride.dashboard.model.UserProfile

sealed interface CoreDashboardAction {
    data object OnStartStopClick : CoreDashboardAction
    data object OnResetClick : CoreDashboardAction
    data class OnUserProfileChanged(val profile: UserProfile) : CoreDashboardAction
}

sealed interface CoreDashboardEvent

data class CoreDashboardState(
    val rideMetrics: RideMetrics = RideMetrics(),
    val isTracking: Boolean = false,
    val userProfile: UserProfile = UserProfile(weightKg = 75.0, age = 30)
)

