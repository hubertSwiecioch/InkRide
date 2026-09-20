package com.speedevand.inkride.history.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.CoreConstants.FORMAT_NO_DECIMALS
import com.speedevand.inkride.core.CoreConstants.FORMAT_ONE_DECIMAL
import com.speedevand.inkride.core.CoreConstants.FORMAT_TWO_DECIMALS
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.history.RideSampleRepository
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.onFailure
import com.speedevand.inkride.core.domain.onSuccess
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.domain.tracking.HeartRateZoneCalculator
import com.speedevand.inkride.core.domain.tracking.buildElevationProfile
import com.speedevand.inkride.core.domain.tracking.training.AthleteThresholds
import com.speedevand.inkride.core.domain.tracking.training.DecouplingCalculator
import com.speedevand.inkride.core.domain.tracking.training.PowerZoneCalculator
import com.speedevand.inkride.core.presentation.UiText
import com.speedevand.inkride.core.presentation.toUiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class RideDetailViewModel(
    private val rideId: Long,
    private val rideHistoryRepository: RideHistoryRepository,
    private val lapRepository: RideLapRepository,
    private val trackPointRepository: RideTrackPointRepository,
    private val sampleRepository: RideSampleRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val gpxExporter: GpxExporter,
) : ViewModel() {
    private val heartRateZoneCalculator = HeartRateZoneCalculator()
    private val powerZoneCalculator = PowerZoneCalculator()
    private val decouplingCalculator = DecouplingCalculator()

    private val _state = MutableStateFlow(RideDetailState())
    val state = _state.asStateFlow()

    private val _events = Channel<RideDetailEvent>()
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            rideHistoryRepository
                .getById(rideId)
                .onSuccess { ride ->
                    var laps = emptyList<com.speedevand.inkride.core.domain.tracking.LapRecord>()
                    lapRepository
                        .getLaps(rideId)
                        .onSuccess { laps = it }
                        .onFailure { error -> _events.send(RideDetailEvent.ShowError(error.toUiText())) }
                    val rawTrackPoints =
                        trackPointRepository
                            .getPoints(rideId)
                            .let { result ->
                                when (result) {
                                    is com.speedevand.inkride.core.domain.Result.Success -> result.data
                                    is com.speedevand.inkride.core.domain.Result.Error -> emptyList()
                                }
                            }
                    val trackPoints = rawTrackPoints.map { point -> TrackPointUi(point.latitude, point.longitude) }
                    val elevationProfile = buildElevationProfile(rawTrackPoints)
                    // Zone durations are recomputed from the persisted stream
                    // rather than stored: the thresholds they are measured
                    // against are the ride's own, and a stored breakdown would
                    // silently keep whichever ones happened to be current.
                    val samples =
                        when (val result = sampleRepository.getSamples(rideId)) {
                            is com.speedevand.inkride.core.domain.Result.Success -> result.data
                            is com.speedevand.inkride.core.domain.Result.Error -> emptyList()
                        }
                    userSettingsRepository.observeSettings().collect { settings ->
                        _state.update {
                            it.copy(
                                ride = ride.toDetailUi(settings.units),
                                laps = laps.map { lap -> lap.toLapUi(settings.units) },
                                trackPoints = trackPoints,
                                elevationChart = elevationProfile?.toChartUi(settings.units),
                                training = buildTrainingUi(ride, samples, settings),
                                thresholdProposal =
                                    if (settings.pendingFtpWatts != null || settings.pendingLthrBpm != null) {
                                        ThresholdProposalUi(settings.pendingFtpWatts, settings.pendingLthrBpm)
                                    } else {
                                        null
                                    },
                                isLoading = false,
                            )
                        }
                    }
                }.onFailure { error ->
                    _state.update { it.copy(isLoading = false) }
                    _events.send(RideDetailEvent.ShowError(error.toUiText()))
                }
        }
    }

    /**
     * Pre-formats what the record holds and recomputes what the stream can
     * still answer. Every field the record has as null reads "--": a ride from
     * before this feature has nothing to backfill, and a zero would claim the
     * rider produced nothing.
     */
    private fun buildTrainingUi(
        ride: RideRecord,
        samples: List<RideSample>,
        settings: UserSettings,
    ): RideDetailTrainingUi {
        val thresholds =
            AthleteThresholds(
                // The ride's own thresholds, not today's: TSS is only meaningful
                // against what was in force when it was ridden.
                ftpWatts = ride.ftpAtRideWatts ?: settings.ftpWatts,
                lthrBpm = ride.lthrAtRideBpm ?: settings.lthrBpm,
                ageForHrZones = settings.age,
            )
        val hrZones = mutableMapOf<Int, Long>()
        val powerZones = mutableMapOf<Int, Long>()
        samples.forEach { sample ->
            sample.heartRateBpm?.let { bpm ->
                val zone = heartRateZoneCalculator.zoneFor(bpm, thresholds.ageForHrZones)
                hrZones[zone] = (hrZones[zone] ?: 0L) + 1L
            }
            val watts = sample.powerWatts
            val ftp = thresholds.ftpWatts
            if (watts != null && ftp != null && ftp > 0) {
                val zone = powerZoneCalculator.zoneFor(watts, ftp)
                powerZones[zone] = (powerZones[zone] ?: 0L) + 1L
            }
        }

        val decoupling = ride.decouplingPercent ?: decouplingCalculator.calculate(samples)
        return RideDetailTrainingUi(
            trainingStressScore = ride.trainingStressScore.formatOrAbsent(FORMAT_NO_DECIMALS),
            intensityFactor = ride.intensityFactor.formatOrAbsent(FORMAT_TWO_DECIMALS),
            normalizedPower = ride.normalizedPowerWatts?.toString() ?: ABSENT,
            hrTss = ride.hrTss.formatOrAbsent(FORMAT_NO_DECIMALS),
            trimp = ride.trimp.formatOrAbsent(FORMAT_NO_DECIMALS),
            work = ride.workKj.formatOrAbsent(FORMAT_NO_DECIMALS),
            decoupling = decoupling.formatOrAbsent(FORMAT_ONE_DECIMAL),
            secondsInHrZone = hrZones,
            secondsInPowerZone = powerZones,
            hasAnyTrainingData =
                ride.trainingStressScore != null || ride.hrTss != null ||
                    ride.normalizedPowerWatts != null || hrZones.isNotEmpty() ||
                    powerZones.isNotEmpty(),
        )
    }

    private fun Double?.formatOrAbsent(format: String): String = this?.let { String.format(Locale.ROOT, format, it) } ?: ABSENT

    fun onAction(action: RideDetailAction) {
        when (action) {
            RideDetailAction.OnAcceptThresholdProposal -> {
                viewModelScope.launch {
                    val settings = userSettingsRepository.observeSettings().first()
                    userSettingsRepository.save(
                        settings.copy(
                            ftpWatts = settings.pendingFtpWatts ?: settings.ftpWatts,
                            lthrBpm = settings.pendingLthrBpm ?: settings.lthrBpm,
                            pendingFtpWatts = null,
                            pendingLthrBpm = null,
                        ),
                    )
                }
            }

            RideDetailAction.OnRejectThresholdProposal -> {
                viewModelScope.launch {
                    val settings = userSettingsRepository.observeSettings().first()
                    // Only the candidate goes; the standing threshold is untouched.
                    userSettingsRepository.save(
                        settings.copy(pendingFtpWatts = null, pendingLthrBpm = null),
                    )
                }
            }

            RideDetailAction.OnDeleteClick -> {
                viewModelScope.launch {
                    rideHistoryRepository.deleteById(rideId)
                    _events.send(RideDetailEvent.NavigateBack)
                }
            }

            RideDetailAction.OnBackClick -> {
                viewModelScope.launch {
                    _events.send(RideDetailEvent.NavigateBack)
                }
            }

            RideDetailAction.OnExportGpxClick -> {
                viewModelScope.launch {
                    gpxExporter
                        .export(rideId)
                        .onSuccess { uri -> _events.send(RideDetailEvent.ShareGpx(uri)) }
                        .onFailure { error ->
                            val message =
                                when (error) {
                                    GpxExportError.NO_TRACK -> {
                                        UiText.StringResource(R.string.ride_detail_export_no_track)
                                    }

                                    GpxExportError.FAILED -> {
                                        UiText.StringResource(R.string.ride_detail_export_failed)
                                    }
                                }
                            _events.send(RideDetailEvent.ShowError(message))
                        }
                }
            }
        }
    }
}
