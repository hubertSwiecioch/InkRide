package com.speedevand.inkride.dashboard.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.speedevand.inkride.R
import com.speedevand.inkride.dashboard.data.AndroidRideSensorDataSource
import com.speedevand.inkride.dashboard.domain.RideMetricsCalculator
import com.speedevand.inkride.dashboard.model.RideMetrics
import com.speedevand.inkride.ui.theme.InkRideTheme
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import java.util.Locale

@Composable
fun CoreDashboardRoot() {
    val context = LocalContext.current
    val vmFactory = viewModelFactory {
        initializer {
            CoreDashboardViewModel(
                rideSensorDataSource = AndroidRideSensorDataSource(context.applicationContext),
                rideMetricsCalculator = RideMetricsCalculator()
            )
        }
    }
    val viewModel: CoreDashboardViewModel = viewModel(factory = vmFactory)
    val state by viewModel.state.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        if (!context.hasLocationPermission()) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    CoreDashboardScreen(
        state = state,
        onAction = viewModel::onAction
    )
}

@Composable
fun CoreDashboardScreen(
    state: CoreDashboardState,
    onAction: (CoreDashboardAction) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val metrics = state.rideMetrics

            TextMMD(
                text = if (state.isTracking) stringResource(R.string.dashboard_tracking) else stringResource(R.string.dashboard_idle),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            TextMMD(
                text = stringResource(R.string.dashboard_speed_current, metrics.currentSpeedKmh.format(1)),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )

            MetricRow(
                left = stringResource(R.string.dashboard_speed_avg, metrics.averageSpeedKmh.format(1)),
                right = stringResource(R.string.dashboard_speed_max, metrics.maxSpeedKmh.format(1))
            )

            MetricRow(
                left = stringResource(R.string.dashboard_distance, metrics.distanceKm.format(2)),
                right = stringResource(R.string.dashboard_time_moving, metrics.movingTimeSeconds.toClock())
            )

            MetricRow(
                left = stringResource(R.string.dashboard_time_elapsed, metrics.elapsedTimeSeconds.toClock()),
                right = stringResource(R.string.dashboard_auto_pause_inline, if (metrics.isAutoPaused) stringResource(R.string.dashboard_auto_pause_short_on) else stringResource(R.string.dashboard_auto_pause_short_off))
            )

            MetricRow(
                left = stringResource(R.string.dashboard_altitude, metrics.altitudeM?.format(0) ?: "--"),
                right = stringResource(R.string.dashboard_elevation_gain, metrics.elevationGainM.format(0))
            )

            MetricRow(
                left = stringResource(R.string.dashboard_grade, metrics.gradePercent.format(1)),
                right = stringResource(R.string.dashboard_calories, metrics.caloriesKcal.format(0))
            )

            TextMMD(
                text = if (metrics.isAutoPaused) {
                    stringResource(R.string.dashboard_auto_pause_on)
                } else {
                    stringResource(R.string.dashboard_auto_pause_off)
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )

            TextMMD(
                text = stringResource(R.string.dashboard_accuracy, metrics.gpsAccuracyM?.toDouble()?.format(1) ?: "--"),
                style = MaterialTheme.typography.bodyMedium
            )

            ProfileControlRow(
                label = stringResource(R.string.dashboard_weight),
                value = stringResource(R.string.dashboard_weight_value, state.userProfile.weightKg.format(0)),
                onDecrease = {
                    onAction(
                        CoreDashboardAction.OnUserProfileChanged(
                            state.userProfile.copy(weightKg = (state.userProfile.weightKg - 1.0).coerceAtLeast(35.0))
                        )
                    )
                },
                onIncrease = {
                    onAction(
                        CoreDashboardAction.OnUserProfileChanged(
                            state.userProfile.copy(weightKg = (state.userProfile.weightKg + 1.0).coerceAtMost(180.0))
                        )
                    )
                }
            )

            ProfileControlRow(
                label = stringResource(R.string.dashboard_age),
                value = stringResource(R.string.dashboard_age_value, state.userProfile.age),
                onDecrease = {
                    onAction(
                        CoreDashboardAction.OnUserProfileChanged(
                            state.userProfile.copy(age = (state.userProfile.age - 1).coerceAtLeast(12))
                        )
                    )
                },
                onIncrease = {
                    onAction(
                        CoreDashboardAction.OnUserProfileChanged(
                            state.userProfile.copy(age = (state.userProfile.age + 1).coerceAtMost(100))
                        )
                    )
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ButtonMMD(
                    modifier = Modifier.weight(1f),
                    onClick = { onAction(CoreDashboardAction.OnStartStopClick) }
                ) {
                    TextMMD(
                        text = if (state.isTracking) {
                            stringResource(R.string.dashboard_stop)
                        } else {
                            stringResource(R.string.dashboard_start)
                        }
                    )
                }

                ButtonMMD(
                    modifier = Modifier.weight(1f),
                    onClick = { onAction(CoreDashboardAction.OnResetClick) }
                ) {
                    TextMMD(text = stringResource(R.string.dashboard_reset))
                }
            }
        }
    }
}

@Composable
private fun MetricRow(
    left: String,
    right: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextMMD(text = left, style = MaterialTheme.typography.bodyLarge)
        TextMMD(text = right, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ProfileControlRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.bodyLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ButtonMMD(onClick = onDecrease) {
                TextMMD(text = "-")
            }
            TextMMD(text = value, style = MaterialTheme.typography.bodyLarge)
            ButtonMMD(onClick = onIncrease) {
                TextMMD(text = "+")
            }
        }
    }
}

private fun Context.hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val coarse = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    return fine || coarse
}

private fun Double.format(decimals: Int): String {
    return String.format(Locale.US, "%1$.${decimals}f", this)
}

private fun Long.toClock(): String {
    val hours = this / 3600
    val minutes = (this % 3600) / 60
    val seconds = this % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

@Preview(showBackground = true)
@Composable
private fun CoreDashboardScreenPreview() {
    InkRideTheme {
        CoreDashboardScreen(
            state = CoreDashboardState(
                isTracking = true,
                rideMetrics = RideMetrics(
                    currentSpeedKmh = 28.3,
                    averageSpeedKmh = 24.1,
                    maxSpeedKmh = 41.4,
                    distanceKm = 37.5,
                    movingTimeSeconds = 4_582,
                    elapsedTimeSeconds = 4_950,
                    altitudeM = 302.0,
                    elevationGainM = 412.0,
                    gradePercent = 5.2,
                    caloriesKcal = 968.0,
                    isAutoPaused = false,
                    gpsAccuracyM = 3.5f
                )
            ),
            onAction = {}
        )
    }
}


