package com.speedevand.inkride.dashboard.presentation.model

import androidx.compose.runtime.Immutable
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.tracking.training.TrainingMetrics
import java.util.Locale

/**
 * Training load as pre-formatted strings, for the same reason [RideMetricsUi]
 * is: recomposition then happens when the rendered text changes, not when a
 * double wobbles in its sixth decimal. On E-Ink that is the difference between
 * a redraw the rider notices and one they do not.
 *
 * Every absent metric renders as "--". A zero would claim the rider produced
 * nothing, which is a different statement from "no meter, no FTP, cannot know".
 */
@Immutable
data class TrainingMetricsUi(
    val normalizedPower: String = ABSENT,
    val intensityFactor: String = ABSENT,
    val trainingStressScore: String = ABSENT,
    val hrTss: String = ABSENT,
    val trimp: String = ABSENT,
    val vam: String = ABSENT,
    val work: String = "0",
    val heartRateZone: String = ABSENT,
    val powerZone: String = ABSENT,
) {
    companion object {
        const val ABSENT = "--"

        fun from(
            metrics: TrainingMetrics,
            @Suppress("UNUSED_PARAMETER") units: MeasurementUnits,
        ): TrainingMetricsUi =
            TrainingMetricsUi(
                normalizedPower = metrics.normalizedPowerWatts?.toString() ?: ABSENT,
                intensityFactor =
                    metrics.intensityFactor?.let { String.format(Locale.ROOT, "%.2f", it) } ?: ABSENT,
                trainingStressScore = metrics.trainingStressScore?.roundedToUnit() ?: ABSENT,
                hrTss = metrics.hrTss?.roundedToUnit() ?: ABSENT,
                trimp = metrics.trimp?.roundedToUnit() ?: ABSENT,
                vam = metrics.vamMetersPerHour?.roundedToUnit() ?: ABSENT,
                work = metrics.workKj.roundedToUnit(),
                heartRateZone = metrics.currentHrZone?.let { "Z$it" } ?: ABSENT,
                powerZone = metrics.currentPowerZone?.let { "Z$it" } ?: ABSENT,
            )

        private fun Double.roundedToUnit(): String = String.format(Locale.ROOT, "%.0f", this)
    }
}
