package com.speedevand.inkride.onboarding.presentation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.speedevand.inkride.core.domain.navigation.DashboardGraph
import com.speedevand.inkride.core.domain.navigation.OnboardingRoute

fun NavGraphBuilder.onboardingGraph(navController: NavController) {
    composable<OnboardingRoute> {
        OnboardingRoot(
            onOnboardingComplete = {
                navController.navigate(DashboardGraph) {
                    popUpTo(OnboardingRoute) { inclusive = true }
                }
            },
        )
    }
}
