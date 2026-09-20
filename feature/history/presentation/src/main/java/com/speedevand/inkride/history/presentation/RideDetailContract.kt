package com.speedevand.inkride.history.presentation

import android.net.Uri
import com.speedevand.inkride.core.CoreConstants.DATE_TIME_FORMAT
import com.speedevand.inkride.core.CoreConstants.FORMAT_NO_DECIMALS
import com.speedevand.inkride.core.CoreConstants.FORMAT_ONE_DECIMAL
import com.speedevand.inkride.core.CoreConstants.FORMAT_TWO_DECIMALS
import com.speedevand.inkride.core.CoreConstants.UNIT_FT
import com.speedevand.inkride.core.CoreConstants.UNIT_KCAL
import com.speedevand.inkride.core.CoreConstants.UNIT_KM
import com.speedevand.inkride.core.CoreConstants.UNIT_KMH
import com.speedevand.inkride.core.CoreConstants.UNIT_M
import com.speedevand.inkride.core.CoreConstants.UNIT_MI
import com.speedevand.inkride.core.CoreConstants.UNIT_MPH
import com.speedevand.inkride.core.CoreConstants.UNIT_W
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.tracking.ElevationProfile
import com.speedevand.inkride.core.domain.tracking.LapRecord
import com.speedevand.inkride.core.domain.tracking.PowerSource
import com.speedevand.inkride.core.presentation.UiText
import com.speedevand.inkride.core.toClockString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RideDetailState(
    val ride: RideDetailUi? = null,
    val laps: List<RideLapUi> = emptyList(),
    val trackPoints: List<TrackPointUi> = emptyList(),
    val elevationChart: ElevationChartUi? = null,
    val training: RideDetailTrainingUi = RideDetailTrainingUi(),
    val thresholdProposal: ThresholdProposalUi? = null,
    val isLoading: Boolean = true,
)

/**
 * Training load for a finished ride, pre-formatted. Rides recorded before this
 * feature have none of it — the 1 Hz stream they would be computed from was
 * never written — so [hasAnyTrainingData] lets the screen hide the section
 * entirely rather than show a wall of dashes.
 */
data class RideDetailTrainingUi(
    val trainingStressScore: String = ABSENT,
    val intensityFactor: String = ABSENT,
    val normalizedPower: String = ABSENT,
    val hrTss: String = ABSENT,
    val trimp: String = ABSENT,
    val work: String = ABSENT,
    val decoupling: String = ABSENT,
    val secondsInHrZone: Map<Int, Long> = emptyMap(),
    val secondsInPowerZone: Map<Int, Long> = emptyMap(),
    val hasAnyTrainingData: Boolean = false,
)

/** A threshold the detector proposed after a ride, awaiting the rider's answer. */
data class ThresholdProposalUi(
    val ftpWatts: Int? = null,
    val lthrBpm: Int? = null,
)

const val ABSENT = "--"

/**
 * A single recorded GPS position for plotting the route polyline. Kept free of
 * any OsmDroid type so the ViewModel stays unit-testable; the map composable
 * maps these to `org.osmdroid.util.GeoPoint`.
 */
data class TrackPointUi(
    val lat: Double,
    val lng: Double,
)

data class ElevationPointUi(
    val distanceKm: Double,
    val altitudeM: Double,
)

data class ElevationChartUi(
    val points: List<ElevationPointUi>,
    val maxAltitudeLabel: String,
    val maxAltitudeDistanceFraction: Float,
    val minAltitudeLabel: String,
    val minAltitudeDistanceFraction: Float,
)

fun ElevationProfile.toChartUi(units: MeasurementUnits = MeasurementUnits.METRIC): ElevationChartUi {
    val altitudeFactor = if (units == MeasurementUnits.IMPERIAL) 3.28084 else 1.0
    val altitudeUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_FT else UNIT_M
    val totalDistanceKm = points.last().distanceKm.let { if (it > 0.0) it else 1.0 }

    return ElevationChartUi(
        points = points.map { ElevationPointUi(it.distanceKm, it.altitudeM) },
        maxAltitudeLabel = String.format(Locale.US, "$FORMAT_NO_DECIMALS $altitudeUnit", maxAltitudeM * altitudeFactor),
        maxAltitudeDistanceFraction = (maxAltitudeDistanceKm / totalDistanceKm).toFloat().coerceIn(0f, 1f),
        minAltitudeLabel = String.format(Locale.US, "$FORMAT_NO_DECIMALS $altitudeUnit", minAltitudeM * altitudeFactor),
        minAltitudeDistanceFraction = (minAltitudeDistanceKm / totalDistanceKm).toFloat().coerceIn(0f, 1f),
    )
}

data class RideLapUi(
    val lapNumber: String,
    val distance: String,
    val time: String,
    val averageSpeed: String,
)

fun LapRecord.toLapUi(units: MeasurementUnits = MeasurementUnits.METRIC): RideLapUi {
    val distanceFactor = if (units == MeasurementUnits.IMPERIAL) 0.621371 else 1.0
    val distanceUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_MI else UNIT_KM
    val speedUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_MPH else UNIT_KMH
    return RideLapUi(
        lapNumber = lapNumber.toString(),
        distance = String.format(Locale.US, "$FORMAT_TWO_DECIMALS $distanceUnit", distanceKm * distanceFactor),
        time = movingTimeSeconds.toClockString(),
        averageSpeed = String.format(Locale.US, "$FORMAT_ONE_DECIMAL $speedUnit", averageSpeedKmh * distanceFactor),
    )
}

data class RideDetailUi(
    val id: Long,
    val formattedDate: String,
    val formattedEndDate: String,
    val distanceKm: String,
    val movingTime: String,
    val elapsedTime: String,
    val averageSpeedKmh: String,
    val maxSpeedKmh: String,
    val elevationGainM: String,
    val caloriesKcal: String,
    val averagePowerWatts: String,
)

fun RideRecord.toDetailUi(units: MeasurementUnits = MeasurementUnits.METRIC): RideDetailUi {
    val distanceFactor = if (units == MeasurementUnits.IMPERIAL) 0.621371 else 1.0
    val speedFactor = if (units == MeasurementUnits.IMPERIAL) 0.621371 else 1.0
    val altitudeFactor = if (units == MeasurementUnits.IMPERIAL) 3.28084 else 1.0

    val distanceUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_MI else UNIT_KM
    val speedUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_MPH else UNIT_KMH
    val altitudeUnit = if (units == MeasurementUnits.IMPERIAL) UNIT_FT else UNIT_M

    val sdf = SimpleDateFormat(DATE_TIME_FORMAT, Locale.getDefault())
    return RideDetailUi(
        id = id,
        formattedDate = sdf.format(Date(startTimestamp)),
        formattedEndDate = sdf.format(Date(endTimestamp)),
        distanceKm = String.format(Locale.US, "$FORMAT_TWO_DECIMALS $distanceUnit", distanceKm * distanceFactor),
        movingTime = movingTimeSeconds.toClockString(),
        elapsedTime = elapsedTimeSeconds.toClockString(),
        averageSpeedKmh = String.format(Locale.US, "$FORMAT_ONE_DECIMAL $speedUnit", averageSpeedKmh * speedFactor),
        maxSpeedKmh = String.format(Locale.US, "$FORMAT_ONE_DECIMAL $speedUnit", maxSpeedKmh * speedFactor),
        elevationGainM = String.format(Locale.US, "$FORMAT_NO_DECIMALS $altitudeUnit", elevationGainM * altitudeFactor),
        caloriesKcal = String.format(Locale.US, "$FORMAT_NO_DECIMALS $UNIT_KCAL", caloriesKcal),
        averagePowerWatts = formatAveragePower(averagePowerWatts, powerSource),
    )
}

/**
 * Average power, marked approximate unless it is known to have come from a
 * meter. [PowerEstimator] is a ±30-60 % physical model, and rendering its
 * output like a measurement lets a rider read a guess as a fact.
 *
 * A null [source] means "not recorded" — every ride older than the column has
 * one — rather than "estimated". It is marked anyway: leaving it bare would
 * imply a meter, which is the exact confusion this marker exists to prevent,
 * and understating confidence is the safer direction to be wrong in.
 *
 * Zero is never marked: nothing was approximated by a ride that produced no
 * power at all.
 */
private fun formatAveragePower(
    watts: Int,
    source: PowerSource?,
): String {
    val formatted = String.format(Locale.US, "$FORMAT_NO_DECIMALS $UNIT_W", watts.toDouble())
    return if (source != PowerSource.MEASURED && watts > 0) "~$formatted" else formatted
}

sealed interface RideDetailAction {
    data object OnAcceptThresholdProposal : RideDetailAction

    data object OnRejectThresholdProposal : RideDetailAction

    data object OnDeleteClick : RideDetailAction

    data object OnBackClick : RideDetailAction

    data object OnExportGpxClick : RideDetailAction
}

sealed interface RideDetailEvent {
    data object NavigateBack : RideDetailEvent

    data class ShowError(
        val message: UiText,
    ) : RideDetailEvent

    data class ShareGpx(
        val uri: Uri,
    ) : RideDetailEvent
}
