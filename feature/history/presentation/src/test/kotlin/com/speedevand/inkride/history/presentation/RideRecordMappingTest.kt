package com.speedevand.inkride.history.presentation

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.tracking.ElevationProfile
import com.speedevand.inkride.core.domain.tracking.ElevationProfilePoint
import com.speedevand.inkride.core.domain.tracking.PowerSource
import org.junit.jupiter.api.Test

class RideRecordMappingTest {
    private val sampleRide =
        RideRecord(
            id = 1L,
            startTimestamp = 0L,
            endTimestamp = 3661000L,
            distanceKm = 10.5,
            movingTimeSeconds = 1800L,
            elapsedTimeSeconds = 3661L,
            averageSpeedKmh = 21.0,
            maxSpeedKmh = 35.0,
            elevationGainM = 150.0,
            caloriesKcal = 300.0,
            averagePowerWatts = 120,
        )

    @Test
    fun `toUi with metric units formats correctly`() {
        val ui = sampleRide.toUi(MeasurementUnits.METRIC)
        assertThat(ui.id).isEqualTo(1L)
    }

    @Test
    fun `toUi with imperial units converts speed and distance`() {
        val ui = sampleRide.toUi(MeasurementUnits.IMPERIAL)
        assertThat(ui.distanceKm).isEqualTo("6.52 mi")
    }

    @Test
    fun `toDetailUi with metric units formats all fields`() {
        val ui = sampleRide.toDetailUi(MeasurementUnits.METRIC)
        assertThat(ui.id).isEqualTo(1L)
        assertThat(ui.distanceKm).isEqualTo("10.50 km")
    }

    @Test
    fun `toDetailUi with imperial units converts all unit-dependent fields`() {
        val ui = sampleRide.toDetailUi(MeasurementUnits.IMPERIAL)
        assertThat(ui.distanceKm).isEqualTo("6.52 mi")
        assertThat(ui.elevationGainM).isEqualTo("492 ft")
    }

    @Test
    fun `toClock formats zero correctly`() {
        val zeroRide = sampleRide.copy(movingTimeSeconds = 0, elapsedTimeSeconds = 0)
        val ui = zeroRide.toUi()
        assertThat(ui.movingTime).isEqualTo("00:00:00")
    }

    @Test
    fun `toClock formats with hours`() {
        val ui = sampleRide.toUi()
        // 1800 seconds = 00:30:00
        assertThat(ui.movingTime).isEqualTo("00:30:00")
    }

    @Test
    fun `toChartUi with metric units formats altitude labels`() {
        val profile =
            ElevationProfile(
                points = listOf(ElevationProfilePoint(0.0, 100.0), ElevationProfilePoint(5.0, 150.0)),
                minAltitudeM = 100.0,
                maxAltitudeM = 150.0,
                minAltitudeDistanceKm = 0.0,
                maxAltitudeDistanceKm = 5.0,
            )

        val ui = profile.toChartUi(MeasurementUnits.METRIC)

        assertThat(ui.maxAltitudeLabel).isEqualTo("150 m")
        assertThat(ui.minAltitudeLabel).isEqualTo("100 m")
        assertThat(ui.maxAltitudeDistanceFraction).isEqualTo(1.0f)
        assertThat(ui.minAltitudeDistanceFraction).isEqualTo(0.0f)
    }

    @Test
    fun `toChartUi with imperial units converts altitude labels`() {
        val profile =
            ElevationProfile(
                points = listOf(ElevationProfilePoint(0.0, 100.0), ElevationProfilePoint(5.0, 150.0)),
                minAltitudeM = 100.0,
                maxAltitudeM = 150.0,
                minAltitudeDistanceKm = 0.0,
                maxAltitudeDistanceKm = 5.0,
            )

        val ui = profile.toChartUi(MeasurementUnits.IMPERIAL)

        assertThat(ui.maxAltitudeLabel).isEqualTo("492 ft")
        assertThat(ui.minAltitudeLabel).isEqualTo("328 ft")
    }

    @Test
    fun `an estimated ride's average power is marked as approximate`() {
        val ui = sampleRide.copy(powerSource = PowerSource.ESTIMATED).toDetailUi(MeasurementUnits.METRIC)

        assertThat(ui.averagePowerWatts).isEqualTo("~120 W")
    }

    @Test
    fun `a measured ride's average power carries no marker`() {
        val ui = sampleRide.copy(powerSource = PowerSource.MEASURED).toDetailUi(MeasurementUnits.METRIC)

        // The marker's absence is the signal that the number came from a meter.
        assertThat(ui.averagePowerWatts).isEqualTo("120 W")
    }

    @Test
    fun `a ride with no recorded provenance is marked rather than passed off as measured`() {
        // powerSource is null for every ride older than the column. That means
        // "not recorded", not "estimated" — but leaving it bare would imply a
        // meter, which is the exact confusion the marker exists to prevent.
        // Erring toward understating confidence is the safe direction.
        val ui = sampleRide.copy(powerSource = null).toDetailUi(MeasurementUnits.METRIC)

        assertThat(ui.averagePowerWatts).isEqualTo("~120 W")
    }

    @Test
    fun `zero average power is never marked`() {
        val ui = sampleRide.copy(averagePowerWatts = 0, powerSource = PowerSource.ESTIMATED).toDetailUi(MeasurementUnits.METRIC)

        assertThat(ui.averagePowerWatts).isEqualTo("0 W")
    }
}
