package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import com.speedevand.inkride.core.domain.ble.PairedSensors
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.history.RideSampleRepository
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.onFailure
import com.speedevand.inkride.core.domain.onSuccess
import com.speedevand.inkride.core.domain.settings.AutoLapMode
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.domain.tracking.training.AthleteThresholds
import com.speedevand.inkride.core.domain.tracking.training.DecouplingCalculator
import com.speedevand.inkride.core.domain.tracking.training.ThresholdDetector
import com.speedevand.inkride.core.domain.tracking.training.TrainingLoadCalculator
import com.speedevand.inkride.core.domain.tracking.training.TrainingMetrics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

enum class TrackingStatus {
    IDLE,
    TRACKING,
    PAUSED,
    AUTO_PAUSED,
}

data class TrackingState(
    val status: TrackingStatus = TrackingStatus.IDLE,
    val metrics: RideMetrics = RideMetrics(),
    val laps: List<LapRecord> = emptyList(),
    val activeGoal: RideGoal? = null,
    // Loaded GPX route the rider is following, and their live progress along it.
    // Both null when no route is loaded.
    val activeRoute: PlannedRoute? = null,
    val routeProgress: RouteProgress? = null,
    // Mirrors the latest BleSample.connected while a ride is active. False both
    // when nothing is paired and when a paired sensor has dropped — Task 7's UI
    // combines this with the paired-address settings to tell the two apart.
    val bleSensorConnected: Boolean = false,
    // The rider's latest known position while status != IDLE, carried forward
    // on a fix-less sample (same treatment as [routeProgress]) and cleared on
    // [stop]. Lets a caller that needs "where is the rider right now" (e.g.
    // destination search's current-location lookup) reuse this live,
    // Kalman-filtered position instead of requesting a second, redundant GPS
    // fix while a ride is already active.
    val currentPosition: LocationFix? = null,
    // Training load for the ride so far. Fields inside are null when their
    // inputs are absent; see TrainingMetrics.
    val trainingMetrics: TrainingMetrics = TrainingMetrics(),
)

/**
 * Process-scoped owner of an in-progress ride.
 *
 * Registered as a Koin singleton so the sample-collection loop runs on its own
 * [scope], independent of any ViewModel lifecycle. Paired with the foreground
 * `TrackingService` — which keeps the process alive — this lets a ride keep
 * recording while the app is backgrounded or the (E-Ink) screen is off.
 *
 * The UI observes [state] for live status/metrics and [errors] for one-shot
 * sensor failures, and issues commands via [start]/[pause]/[resume]/[stop],
 * [recordLap], and [setGoal]/[clearGoal].
 */
class RideTracker(
    private val sensorDataSource: RideSensorDataSource,
    private val metricsCalculator: RideMetricsCalculator,
    private val historyRepository: RideHistoryRepository,
    private val trackPointRepository: RideTrackPointRepository,
    private val sampleRepository: RideSampleRepository,
    private val lapRepository: RideLapRepository,
    private val bleSensorDataSource: BleSensorDataSource,
    private val userSettingsRepository: UserSettingsRepository,
    private val routeFollower: RouteFollower = RouteFollower(),
    private val heartRateFilter: HeartRateFilter = HeartRateFilter(),
    private val trainingLoadCalculator: TrainingLoadCalculator = TrainingLoadCalculator(),
    private val thresholdDetector: ThresholdDetector = ThresholdDetector(),
    private val decouplingCalculator: DecouplingCalculator = DecouplingCalculator(),
    private val minSaveDistanceKm: Double = 0.01,
    // Smallest segment (km) that closes into an automatic final lap at stop.
    private val minLapDistanceKm: Double = 0.01,
    // Auto-pause: drop into AUTO_PAUSED after the rider stays below
    // [autoPauseSpeedKmh] for [autoPauseDelayMs], and auto-resume once they
    // exceed [autoResumeSpeedKmh]. The resume threshold sits above the pause
    // threshold (hysteresis) so GPS speed wobble near a stop can't rapidly flip
    // the state back and forth.
    private val autoPauseSpeedKmh: Double = 1.5,
    private val autoResumeSpeedKmh: Double = 2.5,
    private val autoPauseDelayMs: Long = 3_000L,
    // How often the buffered 1 Hz stream is written out. Injectable so an
    // instrumented test can watch a flush land without riding for a real minute.
    private val sampleFlushIntervalMs: Long = 60_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    // One-shot sensor errors (permission/hardware) surfaced to the UI. Buffered
    // so an emission isn't lost if no collector is attached at that instant.
    private val _errors = MutableSharedFlow<SensorError>(extraBufferCapacity = 1)
    val errors: SharedFlow<SensorError> = _errors.asSharedFlow()

    // Edge-triggered speed/HR alerts. The foreground service collects these to
    // vibrate the device. Buffered so a buzz isn't lost during a brief gap in
    // collection (e.g. service restart).
    private val _alerts = MutableSharedFlow<RideAlert>(extraBufferCapacity = 4)
    val alerts: SharedFlow<RideAlert> = _alerts.asSharedFlow()

    // Latched alert conditions so each threshold crossing buzzes once, not every
    // sample. Reset whenever the ride leaves TRACKING (and on (re)start). Touched
    // from the GPS and BLE collectors, so guarded by [alertLock].
    private var wasOverSpeed = false
    private var wasHrHigh = false
    private var wasHrLow = false
    private var wasOffRoute = false
    private val alertLock = Any()

    private var collectJob: Job? = null
    private var sessionStartMs: Long = 0L

    // Timestamp (sample clock) at which the rider's speed first dropped below the
    // auto-pause threshold; null whenever they're moving. Drives the delay before
    // auto-pause engages. Read/written by the collection coroutine and nulled by
    // manual start/pause/resume/stop on the caller thread, so it's @Volatile for
    // cross-thread visibility.
    @Volatile
    private var lowSpeedSinceMs: Long? = null

    // Timestamp (BLE sample clock) of the most recent cadence value actually
    // reported by a CSC sensor. Distinguishes "cadence is still arriving"
    // from "cadence is stale because the crank stopped and the sensor went
    // quiet" — most CSC sensors stop notifying entirely rather than sending
    // an explicit 0 rpm once the crank stops turning.
    @Volatile
    private var lastCadenceUpdateAtMs: Long? = null

    // How long a BLE reading stays usable after the packet that carried it.
    // One window for every sensor kind: they all go quiet the same way (the
    // crank stops, the strap slips) and two constants drifting apart would mean
    // cadence and power disagreeing about whether the rider is still pedalling.
    private val bleReadingTimeoutMs: Long = 3_000L

    // Latest watts from a paired power meter, and the wall-clock time of the
    // packet that carried them. Null whenever no meter is reporting, which is
    // what routes the calculator back to PowerEstimator.
    @Volatile
    private var latestMeasuredPowerWatts: Int? = null

    @Volatile
    private var lastPowerUpdateAtMs: Long? = null

    // In-memory GPS track for the current ride, flushed to the database (keyed by
    // the new ride's id) at stop. Guarded by [trackPointsLock] because it's
    // appended from the sample-collection coroutine but snapshotted/cleared from
    // whichever thread calls stop().
    private val trackPoints = mutableListOf<RideTrackPoint>()
    private val trackPointsLock = Any()

    // Row id of the in-progress ride, allocated at start so samples can be
    // flushed during the ride instead of being held hostage until stop().
    // Written by the coroutine that performs the insert, read by the sample
    // collector, so @Volatile for cross-thread visibility.
    @Volatile
    private var activeRideId: Long? = null

    // The same id as a value stop() can await: the insert is asynchronous, so a
    // ride stopped moments after starting would otherwise find activeRideId
    // still null and have nothing to finish. Completes with null when the row
    // could not be created at all.
    @Volatile
    private var activeRideIdDeferred: CompletableDeferred<Long?>? = null

    // The 1 Hz stream, buffered between flushes. Appended by the sample
    // collector and drained by the periodic flush or by stop(), so guarded.
    private val pendingSamples = mutableListOf<RideSample>()
    private val pendingSamplesLock = Any()
    private var lastSampleStoredAtMs: Long = 0L

    // Zero rather than the session start on purpose: the first sample of a ride
    // is always further from 0 than any flush interval, so it is written out
    // immediately instead of waiting a minute. A ride killed in its opening
    // seconds then still has something on disk to recover.
    private var lastSampleFlushAtMs: Long = 0L
    private val sampleIntervalMs: Long = 1_000L

    // Ride-total values captured at the previous lap boundary; the next lap is
    // recorded as the delta from these. Reset to 0 at the start of each ride.
    private var lapBaselineDistanceKm: Double = 0.0
    private var lapBaselineMovingTimeSeconds: Long = 0L
    private var lapBaselineElevationGainM: Double = 0.0

    // Ride-total distance / moving time at which the next automatic lap is due.
    private var nextAutoLapDistanceKm: Double? = null
    private var nextAutoLapMovingSeconds: Long? = null

    // Latest user settings, kept current by the collection loop so metric
    // calculation always uses up-to-date weight/bike/age values.
    @Volatile
    private var latestSettings: UserSettings = UserSettings(weightKg = 75, age = 30)

    /** Starts a new ride from idle, or resumes a paused one. No-op while tracking. */
    fun start() {
        when (_state.value.status) {
            TrackingStatus.TRACKING -> {
                Unit
            }

            TrackingStatus.PAUSED, TrackingStatus.AUTO_PAUSED -> {
                lowSpeedSinceMs = null
                resetAlertState()
                _state.update { it.copy(status = TrackingStatus.TRACKING) }
            }

            TrackingStatus.IDLE -> {
                startNewSession()
            }
        }
    }

    /** Manually pauses an active or auto-paused ride. Manual pause sticks: it
     *  won't auto-resume when the rider starts moving again. */
    fun pause() {
        val status = _state.value.status
        if (status == TrackingStatus.TRACKING || status == TrackingStatus.AUTO_PAUSED) {
            lowSpeedSinceMs = null
            _state.update { it.copy(status = TrackingStatus.PAUSED) }
        }
    }

    fun resume() {
        if (_state.value.status == TrackingStatus.PAUSED) {
            lowSpeedSinceMs = null
            resetAlertState()
            _state.update { it.copy(status = TrackingStatus.TRACKING) }
        }
    }

    /**
     * Closes the current lap as the delta from the previous lap boundary and
     * appends it to the lap list. No-op while idle.
     */
    fun recordLap() {
        if (_state.value.status == TrackingStatus.IDLE) return
        val current = _state.value
        val lap = buildLap(current.metrics, current.laps.size + 1)
        lapBaselineDistanceKm = current.metrics.distanceKm
        lapBaselineMovingTimeSeconds = current.metrics.movingTimeSeconds
        lapBaselineElevationGainM = current.metrics.elevationGainM
        _state.update { it.copy(laps = it.laps + lap) }
    }

    /** Sets the per-ride goal. Persists only for the current ride. */
    fun setGoal(goal: RideGoal) {
        _state.update { it.copy(activeGoal = goal) }
    }

    /** Clears any active goal. */
    fun clearGoal() {
        _state.update { it.copy(activeGoal = null) }
    }

    /**
     * Loads a route for the rider to follow. Like a goal it survives [start] from
     * idle and is cleared on [stop]; off-route/next-turn progress is recomputed on
     * each GPS fix. Resets the off-route latch so a fresh route re-arms the alert.
     */
    fun loadRoute(route: PlannedRoute) {
        synchronized(alertLock) { wasOffRoute = false }
        _state.update { it.copy(activeRoute = route, routeProgress = null) }
    }

    /** Unloads the active route and clears its progress. */
    fun clearRoute() {
        synchronized(alertLock) { wasOffRoute = false }
        _state.update { it.copy(activeRoute = null, routeProgress = null) }
    }

    /** Stops tracking, persists the ride if it covered enough distance, and resets. */
    fun stop() {
        if (_state.value.status == TrackingStatus.IDLE) return
        val current = _state.value
        // Close the in-progress segment into a final lap so the breakdown covers
        // the whole ride — but only when the rider actually used laps.
        val laps =
            if (
                current.laps.isNotEmpty() &&
                current.metrics.distanceKm - lapBaselineDistanceKm >= minLapDistanceKm
            ) {
                current.laps + buildLap(current.metrics, current.laps.size + 1)
            } else {
                current.laps
            }
        finishRide(current.metrics, sessionStartMs, laps)
        collectJob?.cancel()
        collectJob = null
        sensorDataSource.stop()
        bleSensorDataSource.disconnect()
        metricsCalculator.reset()
        trainingLoadCalculator.reset()
        lowSpeedSinceMs = null
        lastCadenceUpdateAtMs = null
        latestMeasuredPowerWatts = null
        lastPowerUpdateAtMs = null
        heartRateFilter.reset()
        resetAlertState()
        resetLapBaseline()
        resetSampleBuffer()
        _state.value = TrackingState()
    }

    private fun startNewSession() {
        sensorDataSource
            .start()
            .onFailure { _errors.tryEmit(it) }
            .onSuccess {
                // Preserve a goal/route the rider set before starting; laps always reset.
                val goal = _state.value.activeGoal
                val route = _state.value.activeRoute
                sessionStartMs = System.currentTimeMillis()
                metricsCalculator.reset()
                trainingLoadCalculator.reset()
                lowSpeedSinceMs = null
                lastCadenceUpdateAtMs = null
                latestMeasuredPowerWatts = null
                lastPowerUpdateAtMs = null
                heartRateFilter.reset()
                resetAlertState()
                resetLapBaseline()
                synchronized(trackPointsLock) { trackPoints.clear() }
                resetSampleBuffer()
                // Allocate the ride row up front: samples need somewhere to go
                // immediately, and a process killed mid-ride must leave a
                // recoverable row behind rather than nothing at all.
                val rideIdDeferred = CompletableDeferred<Long?>()
                activeRideIdDeferred = rideIdDeferred
                val startedAt = sessionStartMs
                scope.launch {
                    val id =
                        when (val result = historyRepository.startRide(startedAt)) {
                            is Result.Success -> result.data
                            is Result.Error -> null
                        }
                    activeRideId = id
                    rideIdDeferred.complete(id)
                }
                _state.value =
                    TrackingState(
                        status = TrackingStatus.TRACKING,
                        activeGoal = goal,
                        activeRoute = route,
                    )
                launchCollection()
            }
    }

    private fun resetLapBaseline() {
        lapBaselineDistanceKm = 0.0
        lapBaselineMovingTimeSeconds = 0L
        lapBaselineElevationGainM = 0.0
        nextAutoLapDistanceKm = null
        nextAutoLapMovingSeconds = null
    }

    private fun buildLap(
        metrics: RideMetrics,
        lapNumber: Int,
    ): LapRecord {
        val distanceKm = (metrics.distanceKm - lapBaselineDistanceKm).coerceAtLeast(0.0)
        val movingSeconds = (metrics.movingTimeSeconds - lapBaselineMovingTimeSeconds).coerceAtLeast(0L)
        val elevationGainM = (metrics.elevationGainM - lapBaselineElevationGainM).coerceAtLeast(0.0)
        val avgSpeedKmh = if (movingSeconds > 0L) distanceKm / (movingSeconds / 3600.0) else 0.0
        return LapRecord(
            lapNumber = lapNumber,
            distanceKm = distanceKm,
            movingTimeSeconds = movingSeconds,
            averageSpeedKmh = avgSpeedKmh,
            elevationGainM = elevationGainM,
        )
    }

    /**
     * Buffers a GPS fix into the in-memory track. Only points captured while
     * actively TRACKING are recorded, so pauses (manual or auto) leave clean
     * gaps in the exported GPX rather than clusters of stationary points.
     */
    private fun recordTrackPoint(
        status: TrackingStatus,
        sample: RideSensorSample,
        metrics: RideMetrics,
    ) {
        if (status != TrackingStatus.TRACKING) return
        val lat = sample.latitude ?: return
        val lng = sample.longitude ?: return
        synchronized(trackPointsLock) {
            trackPoints.add(
                RideTrackPoint(
                    timestampMs = sample.timestampMs,
                    latitude = lat,
                    longitude = lng,
                    altitudeM = metrics.altitudeM,
                    accuracyM = sample.accuracyM,
                ),
            )
        }
    }

    /**
     * Buffers one sample per [sampleIntervalMs] while TRACKING and flushes the
     * buffer every [sampleFlushIntervalMs], so a killed process loses at most a
     * minute of a ride rather than all of it.
     *
     * Samples are buffered even before the ride row's id has come back from the
     * insert — only the flush waits on it — so the opening seconds of a ride are
     * not dropped just because the database was slower than the first fix.
     */
    private fun recordRideSample(
        status: TrackingStatus,
        sample: RideSensorSample,
        metrics: RideMetrics,
    ) {
        if (status != TrackingStatus.TRACKING) return
        if (sample.timestampMs - lastSampleStoredAtMs < sampleIntervalMs) return
        lastSampleStoredAtMs = sample.timestampMs
        val rideId = activeRideId

        val batch =
            synchronized(pendingSamplesLock) {
                pendingSamples +=
                    RideSample(
                        timestampMs = sample.timestampMs,
                        latitude = sample.latitude,
                        longitude = sample.longitude,
                        altitudeM = metrics.altitudeM,
                        speedKmh = metrics.currentSpeedKmh,
                        gradePercent = metrics.gradePercent,
                        powerWatts = metrics.powerWatts,
                        powerSource = metrics.powerSource,
                        heartRateBpm = metrics.heartRateBpm,
                        cadenceRpm = metrics.cadenceRpm,
                    )
                if (rideId == null || sample.timestampMs - lastSampleFlushAtMs < sampleFlushIntervalMs) {
                    null
                } else {
                    lastSampleFlushAtMs = sample.timestampMs
                    ArrayList(pendingSamples).also { pendingSamples.clear() }
                }
            }

        if (batch != null && rideId != null) {
            scope.launch { sampleRepository.saveSamples(rideId, batch) }
        }
    }

    private fun resetSampleBuffer() {
        synchronized(pendingSamplesLock) { pendingSamples.clear() }
        lastSampleStoredAtMs = 0L
        lastSampleFlushAtMs = 0L
    }

    private fun launchCollection() {
        collectJob?.cancel()
        collectJob =
            scope.launch {
                val settingsJob =
                    launch {
                        userSettingsRepository.observeSettings().collect { latestSettings = it }
                    }
                // Drive (re)connection only off the paired-address pair, not the whole
                // settings object — so unrelated edits (units, visible metrics) don't
                // touch the GATT layer.
                val bleConnectJob =
                    launch {
                        userSettingsRepository
                            .observeSettings()
                            .map {
                                PairedSensors(
                                    hrmAddress = it.pairedHrmAddress,
                                    cadenceAddress = it.pairedCadenceAddress,
                                    powerAddress = it.pairedPowerAddress,
                                )
                            }.distinctUntilChanged()
                            .collect { bleSensorDataSource.connect(it) }
                    }
                // BLE notifications arrive independently of GPS fixes; fold each new
                // sample straight into the published metrics so HR/cadence stay live
                // even during a GPS dropout or while stationary.
                val bleJob =
                    launch {
                        bleSensorDataSource.observeSamples().collect { ble ->
                            // Mirror the data source's view wholesale: the sample
                            // always carries its retained timestamps, and on a drop
                            // it carries nulls that should clear ours too.
                            lastCadenceUpdateAtMs = ble.cadenceUpdatedAtMs
                            lastPowerUpdateAtMs = ble.powerUpdatedAtMs
                            latestMeasuredPowerWatts = ble.powerWatts
                            val updated =
                                _state.updateAndGet { current ->
                                    if (current.status == TrackingStatus.IDLE) {
                                        current
                                    } else {
                                        current.copy(
                                            metrics =
                                                current.metrics.copy(
                                                    heartRateBpm = heartRateFilter.filter(ble.heartRateBpm, ble.timestampMs),
                                                    cadenceRpm = cadenceOrZeroIfStale(ble.cadenceRpm, ble.timestampMs),
                                                ),
                                            bleSensorConnected = ble.connected,
                                        )
                                    }
                                }
                            // HR alerts can fire from a BLE notification alone, with no
                            // intervening GPS fix.
                            evaluateAlerts(updated.status, updated.metrics)
                        }
                    }
                try {
                    sensorDataSource.observeSamples().collect { sample ->
                        val statusBefore = _state.value.status
                        if (statusBefore == TrackingStatus.IDLE) return@collect
                        val isPaused =
                            statusBefore == TrackingStatus.PAUSED ||
                                statusBefore == TrackingStatus.AUTO_PAUSED
                        val baseMetrics =
                            metricsCalculator.process(
                                sample = sample,
                                userSettings = latestSettings,
                                isPaused = isPaused,
                                measuredPowerWatts = measuredPowerOrNullIfStale(sample.timestampMs),
                            )
                        val thresholds = AthleteThresholds.from(latestSettings)
                        val training =
                            trainingLoadCalculator.process(
                                timestampMs = sample.timestampMs,
                                powerWatts = baseMetrics.powerWatts,
                                powerSource = baseMetrics.powerSource,
                                heartRateBpm = _state.value.metrics.heartRateBpm,
                                altitudeM = baseMetrics.altitudeM,
                                isMoving = baseMetrics.isMoving && !isPaused,
                                thresholds = thresholds,
                            )
                        val autoStatus =
                            evaluateAutoPause(
                                statusBefore,
                                baseMetrics.isMoving,
                                baseMetrics.currentSpeedKmh,
                                sample.timestampMs,
                            )
                        // Recompute route-follow progress from this fix; carry the
                        // previous value forward on a fix-less sample so the readout
                        // doesn't flicker between GPS updates.
                        val progress = evaluateRoute(sample)
                        // A manual start/pause/resume/stop may have landed on the caller
                        // thread since statusBefore was read. Only apply the auto-pause
                        // decision when the status is unchanged, and never write over a
                        // stop() that reset us to IDLE — so the rider's explicit command
                        // is never clobbered by this (asynchronous) sample.
                        val newState =
                            _state.updateAndGet { current ->
                                if (current.status == TrackingStatus.IDLE) return@updateAndGet current
                                val resolved = if (current.status == statusBefore) autoStatus else current.status
                                // Merge HR/cadence from whatever the BLE collector has
                                // most recently committed to `current` inside this same
                                // atomic update, rather than a snapshot read earlier —
                                // otherwise a concurrent BLE commit landing in between
                                // would be clobbered by a stale value here.
                                val metrics =
                                    baseMetrics.copy(
                                        heartRateBpm = current.metrics.heartRateBpm,
                                        cadenceRpm = cadenceOrZeroIfStale(current.metrics.cadenceRpm, sample.timestampMs),
                                    )
                                val position =
                                    if (sample.latitude != null && sample.longitude != null) {
                                        LocationFix(sample.latitude, sample.longitude)
                                    } else {
                                        current.currentPosition
                                    }
                                current.copy(
                                    status = resolved,
                                    metrics = metrics,
                                    routeProgress = progress,
                                    currentPosition = position,
                                    trainingMetrics = training,
                                )
                            }
                        recordTrackPoint(newState.status, sample, newState.metrics)
                        recordRideSample(newState.status, sample, newState.metrics)
                        evaluateAlerts(newState.status, newState.metrics)
                        evaluateOffRoute(newState.status, newState.routeProgress)
                        evaluateAutoLap(newState.status, newState.metrics)
                    }
                } finally {
                    settingsJob.cancel()
                    bleConnectJob.cancel()
                    bleJob.cancel()
                }
            }
    }

    /**
     * Decides the next status based on the live speed, applying the auto-pause
     * delay and the pause/resume hysteresis. Only ever transitions between
     * TRACKING and AUTO_PAUSED — a manual PAUSED (or IDLE) is left untouched so
     * the rider's explicit choice always wins.
     */
    private fun evaluateAutoPause(
        current: TrackingStatus,
        isMoving: Boolean,
        speedKmh: Double,
        nowMs: Long,
    ): TrackingStatus =
        when (current) {
            TrackingStatus.TRACKING -> {
                if (!isMoving) {
                    val since = lowSpeedSinceMs ?: nowMs.also { lowSpeedSinceMs = it }
                    if (nowMs - since >= autoPauseDelayMs) {
                        lowSpeedSinceMs = null
                        TrackingStatus.AUTO_PAUSED
                    } else {
                        current
                    }
                } else {
                    lowSpeedSinceMs = null
                    current
                }
            }

            // Resume keeps its own, higher threshold: hysteresis lives here so
            // speed wobble at a stop can't flip the state back and forth.
            TrackingStatus.AUTO_PAUSED -> {
                if (isMoving && speedKmh > autoResumeSpeedKmh) TrackingStatus.TRACKING else current
            }

            else -> {
                current
            }
        }

    /**
     * The latest measured watts, or null once the meter has been quiet for
     * longer than [bleReadingTimeoutMs]. Null rather than zero on purpose: zero is a
     * claim the rider is producing no power, while null hands the reading back
     * to [PowerEstimator], which is the honest answer when no meter is
     * reporting.
     */
    private fun measuredPowerOrNullIfStale(nowMs: Long): Int? {
        val watts = latestMeasuredPowerWatts ?: return null
        val lastUpdate = lastPowerUpdateAtMs ?: return watts
        return if (nowMs - lastUpdate > bleReadingTimeoutMs) null else watts
    }

    /**
     * Returns 0 once more than [bleReadingTimeoutMs] has passed since the last
     * actual cadence notification, instead of freezing at the last reported
     * value.
     */
    private fun cadenceOrZeroIfStale(
        rawCadenceRpm: Int?,
        nowMs: Long,
    ): Int? {
        val lastUpdate = lastCadenceUpdateAtMs ?: return rawCadenceRpm
        return if (nowMs - lastUpdate > bleReadingTimeoutMs) 0 else rawCadenceRpm
    }

    private fun resetAlertState() =
        synchronized(alertLock) {
            wasOverSpeed = false
            wasHrHigh = false
            wasHrLow = false
            wasOffRoute = false
        }

    /**
     * Emits an alert the first time a live metric crosses a configured threshold
     * (edge-triggered), so the rider is buzzed once per crossing rather than on
     * every sample. Alerts only fire while actively TRACKING; any other status
     * (paused/idle) clears the latches so the next active crossing re-arms.
     */
    private fun evaluateAlerts(
        status: TrackingStatus,
        metrics: RideMetrics,
    ) {
        val config = latestSettings.alerts
        synchronized(alertLock) {
            if (status != TrackingStatus.TRACKING || !config.hasAny) {
                wasOverSpeed = false
                wasHrHigh = false
                wasHrLow = false
                return
            }

            val maxSpeed = config.maxSpeedKmh
            if (maxSpeed != null) {
                val over = metrics.currentSpeedKmh > maxSpeed
                if (over && !wasOverSpeed) _alerts.tryEmit(RideAlert.OverSpeed(metrics.currentSpeedKmh))
                wasOverSpeed = over
            } else {
                wasOverSpeed = false
            }

            val hr = metrics.heartRateBpm
            val hrMax = config.hrZoneMaxBpm
            if (hr != null && hrMax != null) {
                val high = hr > hrMax
                if (high && !wasHrHigh) _alerts.tryEmit(RideAlert.HeartRateHigh(hr))
                wasHrHigh = high
            } else {
                wasHrHigh = false
            }

            val hrMin = config.hrZoneMinBpm
            if (hr != null && hrMin != null) {
                val low = hr < hrMin
                if (low && !wasHrLow) _alerts.tryEmit(RideAlert.HeartRateLow(hr))
                wasHrLow = low
            } else {
                wasHrLow = false
            }
        }
    }

    /**
     * Recomputes progress along the loaded route for the given fix. Returns the
     * previous progress unchanged when no route is loaded or the sample carries
     * no usable position.
     */
    private fun evaluateRoute(sample: RideSensorSample): RouteProgress? {
        val route = _state.value.activeRoute ?: return null
        val lat = sample.latitude
        val lng = sample.longitude
        if (lat == null || lng == null) return _state.value.routeProgress
        return routeFollower.evaluate(route, lat, lng)
    }

    /**
     * Closes a lap each time the ride crosses the next auto-lap boundary. Uses
     * the existing [recordLap], so an automatic lap is indistinguishable from a
     * manual one in the breakdown — which is what a rider expects.
     */
    private fun evaluateAutoLap(
        status: TrackingStatus,
        metrics: RideMetrics,
    ) {
        if (status != TrackingStatus.TRACKING) return
        when (latestSettings.autoLap.mode) {
            AutoLapMode.OFF -> {
                return
            }

            AutoLapMode.DISTANCE -> {
                val step = latestSettings.autoLap.distanceKm?.takeIf { it > 0.0 } ?: return
                val due = nextAutoLapDistanceKm ?: step.also { nextAutoLapDistanceKm = it }
                if (metrics.distanceKm >= due) {
                    recordLap()
                    nextAutoLapDistanceKm = due + step
                }
            }

            AutoLapMode.TIME -> {
                val step =
                    latestSettings.autoLap.intervalMinutes
                        ?.takeIf { it > 0 }
                        ?.times(60L) ?: return
                val due = nextAutoLapMovingSeconds ?: step.also { nextAutoLapMovingSeconds = it }
                if (metrics.movingTimeSeconds >= due) {
                    recordLap()
                    nextAutoLapMovingSeconds = due + step
                }
            }
        }
    }

    /**
     * Edge-triggered off-route alert: buzzes once when the rider first strays
     * beyond the threshold, re-arming after they return. Only fires while
     * actively TRACKING; any other status clears the latch.
     */
    private fun evaluateOffRoute(
        status: TrackingStatus,
        progress: RouteProgress?,
    ) {
        synchronized(alertLock) {
            if (status != TrackingStatus.TRACKING || progress == null) {
                wasOffRoute = false
                return
            }
            val off = progress.isOffRoute
            if (off && !wasOffRoute) _alerts.tryEmit(RideAlert.OffRoute(progress.distanceToRouteM))
            wasOffRoute = off
        }
    }

    /**
     * Closes the row [startNewSession] allocated: flushes the tail of the sample
     * buffer, writes the final aggregates and marks the ride complete — or
     * deletes the row outright when the rider never covered
     * [minSaveDistanceKm], which cascades the samples and track points away with
     * it.
     */
    private fun finishRide(
        metrics: RideMetrics,
        startedAt: Long,
        laps: List<LapRecord>,
    ) {
        // Snapshot and clear both buffers up front (synchronously, before the
        // collection job is cancelled) so a too-short ride still drops its data
        // and the next ride starts clean.
        val points =
            synchronized(trackPointsLock) {
                ArrayList(trackPoints).also { trackPoints.clear() }
            }
        val tailSamples =
            synchronized(pendingSamplesLock) {
                ArrayList(pendingSamples).also { pendingSamples.clear() }
            }
        val rideIdDeferred = activeRideIdDeferred
        activeRideIdDeferred = null
        activeRideId = null
        lastSampleStoredAtMs = 0L
        lastSampleFlushAtMs = 0L

        val endedAt = System.currentTimeMillis()
        val settings = latestSettings
        val training = _state.value.trainingMetrics
        val isLongEnough = metrics.distanceKm >= minSaveDistanceKm

        scope.launch {
            // The insert is asynchronous, so a ride stopped moments after
            // starting has to wait for its id rather than assume there isn't one.
            val rideId = rideIdDeferred?.await()

            if (rideId == null) {
                // The row was never created (the insert failed). Fall back to a
                // plain insert so the ride is not lost along with its placeholder.
                if (!isLongEnough) return@launch
                historyRepository
                    .save(rideRecord(0L, metrics, training, startedAt, endedAt, settings, isComplete = true))
                    .onSuccess { newId -> saveRideChildren(newId, points, laps, tailSamples) }
                return@launch
            }

            if (!isLongEnough) {
                // Cascading foreign keys take the samples and track points with it.
                historyRepository.deleteById(rideId)
                return@launch
            }

            if (tailSamples.isNotEmpty()) {
                sampleRepository.saveSamples(rideId, tailSamples)
            }
            // Read the stream back once, after the tail flush, and use it for
            // both post-ride analyses. What reached disk is what a later
            // re-analysis would see, so the two cannot disagree.
            val storedSamples =
                when (val result = sampleRepository.getSamples(rideId)) {
                    is Result.Success -> result.data
                    is Result.Error -> emptyList()
                }
            val decoupling = decouplingCalculator.calculate(storedSamples)
            historyRepository
                .finishRide(
                    rideRecord(rideId, metrics, training, startedAt, endedAt, settings, isComplete = true)
                        .copy(decouplingPercent = decoupling),
                ).onSuccess { saveRideChildren(rideId, points, laps, samples = emptyList()) }
            proposeThresholds(storedSamples, settings)
        }
    }

    /**
     * Looks for a new FTP or LTHR in the ride that just finished and records it
     * as a pending candidate. Never applied on its own: a threshold is the
     * rider's to accept, and silently raising it would rewrite what every later
     * ride's TSS means without them ever asking for it.
     *
     * Takes the stream the finish path already read back from storage: what
     * actually reached disk is what post-ride analysis will use.
     */
    private suspend fun proposeThresholds(
        samples: List<RideSample>,
        settings: UserSettings,
    ) {
        if (!settings.autoDetectThresholds) return
        val proposal = thresholdDetector.detect(samples, AthleteThresholds.from(settings))
        if (proposal.ftpWatts == null && proposal.lthrBpm == null) return
        userSettingsRepository.save(
            settings.copy(
                pendingFtpWatts = proposal.ftpWatts ?: settings.pendingFtpWatts,
                pendingLthrBpm = proposal.lthrBpm ?: settings.pendingLthrBpm,
            ),
        )
    }

    private suspend fun saveRideChildren(
        rideId: Long,
        points: List<RideTrackPoint>,
        laps: List<LapRecord>,
        samples: List<RideSample>,
    ) {
        if (samples.isNotEmpty()) {
            sampleRepository.saveSamples(rideId, samples)
        }
        if (points.isNotEmpty()) {
            trackPointRepository.savePoints(rideId, points)
        }
        if (laps.isNotEmpty()) {
            lapRepository.saveLaps(rideId, laps)
        }
    }

    private fun rideRecord(
        id: Long,
        metrics: RideMetrics,
        training: TrainingMetrics,
        startedAt: Long,
        endedAt: Long,
        settings: UserSettings,
        isComplete: Boolean,
    ) = AthleteThresholds.from(settings).let { thresholds ->
        RideRecord(
            id = id,
            startTimestamp = startedAt,
            endTimestamp = endedAt,
            distanceKm = metrics.distanceKm,
            movingTimeSeconds = metrics.movingTimeSeconds,
            elapsedTimeSeconds = metrics.elapsedTimeSeconds,
            averageSpeedKmh = metrics.averageSpeedKmh,
            maxSpeedKmh = metrics.maxSpeedKmh,
            elevationGainM = metrics.elevationGainM,
            caloriesKcal = metrics.caloriesKcal,
            averagePowerWatts = metrics.averagePowerWatts,
            bikeWeightKg = settings.bikeWeightKg,
            bikeType = settings.bikeType,
            isComplete = isComplete,
            normalizedPowerWatts = training.normalizedPowerWatts,
            intensityFactor = training.intensityFactor,
            trainingStressScore = training.trainingStressScore,
            hrTss = training.hrTss,
            trimp = training.trimp,
            workKj = training.workKj,
            maxPowerWatts = metrics.powerWatts.takeIf { it > 0 },
            powerSource = metrics.powerSource,
            // Pinned at ride time: raising FTP later must not rewrite the
            // training load of a ride already in the books.
            ftpAtRideWatts = thresholds.ftpWatts,
            lthrAtRideBpm = thresholds.lthrBpm,
        )
    }

    /**
     * Closes rides a previous process left open. Called once at app start —
     * deliberately not from `init`, because Koin constructs this singleton on the
     * main thread and database I/O there would block launch.
     *
     * A recovered ride is explicitly a best-effort reconstruction from the
     * samples that made it to disk, not a claim of full fidelity: elevation gain
     * and calories keep whatever the row already held, because neither can be
     * rebuilt from positions alone.
     */
    fun recoverUnfinishedRides() {
        scope.launch {
            historyRepository.getUnfinishedRides().onSuccess { rides ->
                rides.forEach { ride ->
                    val samples =
                        when (val result = sampleRepository.getSamples(ride.id)) {
                            is Result.Success -> result.data
                            is Result.Error -> emptyList()
                        }
                    val distanceKm = distanceFromSamplesKm(samples)
                    if (distanceKm >= minSaveDistanceKm) {
                        historyRepository.finishRide(rideFromSamples(ride, samples, distanceKm))
                    } else {
                        // Nothing worth keeping: a start that never went anywhere.
                        historyRepository.deleteById(ride.id)
                    }
                }
            }
        }
    }

    /** Straight-line distance along the recovered sample positions. */
    private fun distanceFromSamplesKm(samples: List<RideSample>): Double {
        var meters = 0.0
        var previousLat: Double? = null
        var previousLng: Double? = null
        for (sample in samples) {
            val lat = sample.latitude ?: continue
            val lng = sample.longitude ?: continue
            val priorLat = previousLat
            val priorLng = previousLng
            if (priorLat != null && priorLng != null) {
                meters += haversineMeters(priorLat, priorLng, lat, lng)
            }
            previousLat = lat
            previousLng = lng
        }
        return meters / 1000.0
    }

    /**
     * Rebuilds the aggregates the metrics calculator would have produced, from
     * the samples that survived. Moving time counts only samples whose recorded
     * speed cleared [autoPauseSpeedKmh], so a ride that spent ten minutes at a
     * red light does not come back claiming them as riding time.
     */
    private fun rideFromSamples(
        ride: RideRecord,
        samples: List<RideSample>,
        distanceKm: Double,
    ): RideRecord {
        val elapsedSeconds =
            if (samples.size < 2) {
                0L
            } else {
                (samples.last().timestampMs - samples.first().timestampMs) / 1000L
            }
        val movingSeconds =
            samples.count { (it.speedKmh ?: 0.0) > autoPauseSpeedKmh }.toLong() *
                (sampleIntervalMs / 1000L)
        val maxSpeedKmh = samples.mapNotNull { it.speedKmh }.maxOrNull() ?: 0.0
        val averageSpeedKmh = if (movingSeconds > 0L) distanceKm / (movingSeconds / 3600.0) else 0.0
        val averagePowerWatts =
            samples
                .mapNotNull { it.powerWatts }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toInt() ?: 0
        return ride.copy(
            endTimestamp = samples.lastOrNull()?.timestampMs ?: ride.endTimestamp,
            distanceKm = distanceKm,
            movingTimeSeconds = movingSeconds,
            elapsedTimeSeconds = elapsedSeconds,
            averageSpeedKmh = averageSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            averagePowerWatts = averagePowerWatts,
            isComplete = true,
        )
    }
}
