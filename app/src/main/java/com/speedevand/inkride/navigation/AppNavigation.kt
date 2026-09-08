package com.speedevand.inkride.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mudita.mmd.components.nav_bar.NavigationBarItemMMD
import com.mudita.mmd.components.nav_bar.NavigationBarMMD
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.R
import com.speedevand.inkride.ble.presentation.bleGraph
import com.speedevand.inkride.core.domain.navigation.DashboardGraph
import com.speedevand.inkride.core.domain.navigation.DashboardRoute
import com.speedevand.inkride.core.domain.navigation.RideHistoryRoute
import com.speedevand.inkride.core.domain.navigation.SettingsRoute
import com.speedevand.inkride.dashboard.presentation.dashboardGraph
import com.speedevand.inkride.history.presentation.historyGraph
import com.speedevand.inkride.onboarding.presentation.onboardingGraph
import com.speedevand.inkride.settings.presentation.settingsGraph
import com.speedevand.inkride.tracking.service.TrackingService

@Composable
fun AppNavigation(startDestination: Any) {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStack?.destination
    // Freeze the incoming start destination on first composition. NavHost
    // rebuilds its internal graph (and resets the back stack) whenever this
    // value changes object-identity across recompositions -- MainActivity
    // recomputes it on every UserSettings emission, including the one
    // onboarding's own completion save triggers, which would otherwise race
    // with the explicit navigate() call already wired into onboarding's
    // completion handler.
    val frozenStartDestination = remember { startDestination }

    val items =
        listOf(
            BottomNavItem(
                labelRes = R.string.nav_ride,
                icon = Icons.Default.PlayArrow,
                route = DashboardRoute,
            ),
            BottomNavItem(
                labelRes = R.string.nav_history,
                icon = Icons.Default.History,
                route = RideHistoryRoute,
            ),
            BottomNavItem(
                labelRes = R.string.nav_settings,
                icon = Icons.Default.Settings,
                route = SettingsRoute,
            ),
        )

    val showBottomBar = items.any { currentDestination?.hasRoute(it.route::class) == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBarMMD {
                    items.forEach { item ->
                        val selected = currentDestination?.hasRoute(item.route::class) == true
                        NavigationBarItemMMD(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(DashboardGraph) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = stringResource(item.labelRes),
                                )
                            },
                            label = { TextMMD(text = stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = frozenStartDestination,
            // consumeWindowInsets marks this region as already handled so the nested
            // per-screen Scaffolds (each with their own contentWindowInsets) don't
            // apply the same status/navigation bar inset a second time.
            modifier =
                Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            dashboardGraph(navController, TrackingService::class.java)
            onboardingGraph(navController)
            historyGraph(navController)
            settingsGraph(navController)
            bleGraph(navController)
        }
    }
}

private data class BottomNavItem(
    val labelRes: Int,
    val icon: ImageVector,
    val route: Any,
)
