package com.speedevand.inkride.onboarding.presentation

import android.Manifest
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

enum class OnboardingStep(
    val icon: ImageVector,
    val titleRes: Int,
    val bodyRes: Int,
    val permissions: List<String> = emptyList(),
) {
    VALUE_PROP_EINK(Icons.Filled.WbSunny, R.string.onboarding_eink_title, R.string.onboarding_eink_body),
    VALUE_PROP_PRIVACY(Icons.Filled.Lock, R.string.onboarding_privacy_title, R.string.onboarding_privacy_body),
    VALUE_PROP_TRACKING(
        Icons.Filled.DirectionsBike,
        R.string.onboarding_tracking_title,
        R.string.onboarding_tracking_body,
    ),
    LOCATION_PERMISSION(
        Icons.Filled.LocationOn,
        R.string.onboarding_location_title,
        R.string.onboarding_location_body,
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ),
    NOTIFICATION_PERMISSION(
        Icons.Filled.Notifications,
        R.string.onboarding_notification_title,
        R.string.onboarding_notification_body,
        listOf(Manifest.permission.POST_NOTIFICATIONS),
    ),
}

val OnboardingStep.isPermissionStep: Boolean get() = permissions.isNotEmpty()

/**
 * The walkthrough's pages, in order. [NOTIFICATION_PERMISSION] is included
 * only from API 33 (TIRAMISU) onward -- POST_NOTIFICATIONS doesn't exist as
 * a runtime permission before that, matching the same SDK check
 * DashboardScreen already uses for its own permission request.
 */
fun buildOnboardingSteps(sdkInt: Int = Build.VERSION.SDK_INT): List<OnboardingStep> =
    buildList {
        add(OnboardingStep.VALUE_PROP_EINK)
        add(OnboardingStep.VALUE_PROP_PRIVACY)
        add(OnboardingStep.VALUE_PROP_TRACKING)
        add(OnboardingStep.LOCATION_PERMISSION)
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(OnboardingStep.NOTIFICATION_PERMISSION)
        }
    }
