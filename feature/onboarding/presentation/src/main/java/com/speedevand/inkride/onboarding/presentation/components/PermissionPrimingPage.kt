package com.speedevand.inkride.onboarding.presentation.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.onboarding.presentation.OnboardingStep
import com.speedevand.inkride.onboarding.presentation.OnboardingTestTags
import com.speedevand.inkride.onboarding.presentation.R

/**
 * Rationale + adaptive request button for one permission-priming step. Fully
 * self-contained (launcher, live grant status, permanently-denied
 * detection) since none of this is shared state the ViewModel needs --
 * [onContinue] only signals "the user is done with this step", identical to
 * DashboardScreen's own self-contained permission-launcher pattern.
 */
@Composable
fun PermissionPrimingPage(
    step: OnboardingStep,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // Disambiguates "never asked" from "permanently denied" -- both make
    // shouldShowRequestPermissionRationale return false, so only having
    // asked once already tells them apart.
    var hasRequestedOnce by rememberSaveable(step) { mutableStateOf(false) }

    // Bumped on ON_RESUME so returning from the system Settings screen
    // re-reads live permission state instead of showing stale button text.
    var refreshTick by rememberSaveable(step) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshTick++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allGranted =
        remember(step, refreshTick) {
            step.permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    val permanentlyDenied =
        hasRequestedOnce && !allGranted && activity != null &&
            step.permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            hasRequestedOnce = true
            if (result.values.all { it }) {
                onContinue()
            }
        }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = step.icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        TextMMD(
            text = stringResource(step.titleRes),
            modifier =
                Modifier
                    .padding(top = DesignConstants.PADDING_LARGE)
                    .testTag(OnboardingTestTags.PAGE_TITLE),
        )
        TextMMD(
            text = stringResource(step.bodyRes),
            modifier = Modifier.padding(top = DesignConstants.PADDING_MEDIUM),
        )

        Column(
            modifier = Modifier.padding(top = DesignConstants.PADDING_LARGE),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ButtonMMD(
                onClick = {
                    when {
                        allGranted -> onContinue()
                        permanentlyDenied -> context.openAppSettings()
                        else -> launcher.launch(step.permissions.toTypedArray())
                    }
                },
                modifier = Modifier.testTag(OnboardingTestTags.PERMISSION_PRIMARY_BUTTON),
            ) {
                TextMMD(
                    text =
                        stringResource(
                            if (permanentlyDenied) {
                                R.string.onboarding_permission_open_settings
                            } else {
                                R.string.onboarding_permission_continue
                            },
                        ),
                )
            }
            OutlinedButtonMMD(
                onClick = onContinue,
                modifier =
                    Modifier
                        .padding(top = DesignConstants.PADDING_SMALL)
                        .testTag(OnboardingTestTags.PERMISSION_SKIP_BUTTON),
            ) {
                TextMMD(text = stringResource(R.string.onboarding_permission_skip))
            }
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null)),
    )
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
