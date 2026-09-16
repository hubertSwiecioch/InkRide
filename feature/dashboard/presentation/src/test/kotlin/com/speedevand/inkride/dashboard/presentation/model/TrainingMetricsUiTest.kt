package com.speedevand.inkride.dashboard.presentation.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.tracking.training.TrainingMetrics
import org.junit.jupiter.api.Test

class TrainingMetricsUiTest {
    @Test
    fun `absent training metrics render as double dashes rather than zeros`() {
        val ui = TrainingMetricsUi.from(TrainingMetrics(), MeasurementUnits.METRIC)

        // A zero would claim the rider produced nothing; "--" says the app
        // cannot know, which is the truth without a meter or an FTP.
        assertThat(ui.normalizedPower).isEqualTo("--")
        assertThat(ui.intensityFactor).isEqualTo("--")
        assertThat(ui.trainingStressScore).isEqualTo("--")
    }

    @Test
    fun `present training metrics are rounded for a slow display`() {
        val ui =
            TrainingMetricsUi.from(
                TrainingMetrics(
                    normalizedPowerWatts = 243,
                    intensityFactor = 0.7843,
                    trainingStressScore = 64.4,
                    vamMetersPerHour = 842.7,
                    workKj = 612.3,
                ),
                MeasurementUnits.METRIC,
            )

        assertThat(ui.normalizedPower).isEqualTo("243")
        assertThat(ui.intensityFactor).isEqualTo("0.78")
        assertThat(ui.trainingStressScore).isEqualTo("64")
        assertThat(ui.vam).isEqualTo("843")
        assertThat(ui.work).isEqualTo("612")
    }

    @Test
    fun `the heart-rate zone shows which zone, not a raw index`() {
        val ui = TrainingMetricsUi.from(TrainingMetrics(currentHrZone = 3), MeasurementUnits.METRIC)

        assertThat(ui.heartRateZone).isEqualTo("Z3")
    }

    @Test
    fun `an absent zone renders as double dashes too`() {
        val ui = TrainingMetricsUi.from(TrainingMetrics(), MeasurementUnits.METRIC)

        assertThat(ui.heartRateZone).isEqualTo("--")
        assertThat(ui.powerZone).isEqualTo("--")
    }

    @Test
    fun `work is always shown because watt-seconds accumulate without a meter`() {
        // workKj is not nullable: even an estimated ride does real work, so this
        // is the one training figure that never reads "--".
        val ui = TrainingMetricsUi.from(TrainingMetrics(workKj = 0.0), MeasurementUnits.METRIC)

        assertThat(ui.work).isEqualTo("0")
    }

    @Test
    fun `VAM is rounded to whole metres per hour on a descent as well`() {
        val ui = TrainingMetricsUi.from(TrainingMetrics(vamMetersPerHour = -1234.6), MeasurementUnits.METRIC)

        assertThat(ui.vam).isEqualTo("-1235")
    }
}
