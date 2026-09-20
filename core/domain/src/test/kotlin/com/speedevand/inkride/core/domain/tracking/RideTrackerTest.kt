package com.speedevand.inkride.core.domain.tracking

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
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
import com.speedevand.inkride.core.domain.settings.AlertConfig
import com.speedevand.inkride.core.domain.settings.AutoLapConfig
import com.speedevand.inkride.core.domain.settings.AutoLapMode
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideTrackerTest {
    private val settings = UserSettings(weightKg = 75, age = 32)

    @Test
    fun `start sets status to tracking and starts the sensor`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker = newTracker(testScheduler, sensor)

            tracker.start()

            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.TRACKING)
            assertThat(sensor.started).isTrue()
        }

    @Test
    fun `start failure surfaces an error and stays idle`() =
        runTest {
            val sensor =
                FakeSensorDataSource().apply {
                    startResult = Result.Error(SensorError.Permission.LOCATION_DENIED)
                }
            val tracker = newTracker(testScheduler, sensor)
            val errors = mutableListOf<SensorError>()
            // Eager (unconfined) subscriber so it's attached before start() emits.
            val collectorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            collectorScope.launch { tracker.errors.collect { errors.add(it) } }

            tracker.start()

            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.IDLE)
            assertThat(errors).hasSize(1)
            assertThat(errors.first()).isEqualTo(SensorError.Permission.LOCATION_DENIED)
            collectorScope.cancel()
        }

    @Test
    fun `processed samples update live metrics`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker = newTracker(testScheduler, sensor)

            tracker.start()
            sensor.samples.emit(sampleAt(0L, speedFromGpsMps = 10.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1000L, speedFromGpsMps = 10.0, accuracy = 5.0f))

            // 10 m/s = 36 km/h
            assertThat(tracker.state.value.metrics.currentSpeedKmh).isEqualTo(36.0)
        }

    @Test
    fun `pause then resume toggles status without ending the session`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker = newTracker(testScheduler, sensor)

            tracker.start()
            tracker.pause()
            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.PAUSED)

            tracker.resume()
            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.TRACKING)
        }

    @Test
    fun `auto-pause engages after sustained low speed and resumes on movement`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, autoPauseDelayMs = 2_000L)

            tracker.start()
            // Moving: well above the resume threshold.
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.TRACKING)

            // Stopped: low speed must persist beyond the delay before auto-pause.
            sensor.samples.emit(sampleAt(2000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f))
            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.TRACKING)
            sensor.samples.emit(sampleAt(4500L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f))
            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.AUTO_PAUSED)

            // Moving again: auto-resume.
            sensor.samples.emit(sampleAt(5500L, latitude = 0.0002, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.TRACKING)
        }

    @Test
    fun `auto-pause does not engage while the calculator still reports movement`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, autoPauseDelayMs = 2_000L)

            tracker.start()
            // A crawl: ~8 m of displacement per 1 s fix at a tight 4 m accuracy
            // confirms movement, but the Doppler speed stays near zero the whole
            // time so currentSpeedKmh never clears autoPauseSpeedKmh (1.5). Before
            // the isMoving contract this silently auto-paused mid-climb once the
            // sample timestamps crossed autoPauseDelayMs.
            sensor.samples.emit(sampleAt(0L, latitude = 52.0, longitude = 0.0, speedFromGpsMps = 0.2, accuracy = 4.0f))
            repeat(4) { step ->
                sensor.samples.emit(
                    sampleAt(
                        1_000L * (step + 1),
                        latitude = 52.0 + 0.000072 * (step + 1),
                        longitude = 0.0,
                        speedFromGpsMps = 0.2,
                        accuracy = 4.0f,
                    ),
                )
            }

            assertThat(tracker.state.value.metrics.currentSpeedKmh).isLessThan(1.5)
            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.TRACKING)
        }

    @Test
    fun `manual pause is not auto-resumed by movement`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker = newTracker(testScheduler, sensor)

            tracker.start()
            tracker.pause()
            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.PAUSED)

            // Even moving fast, a manual pause must stick.
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.PAUSED)
        }

    @Test
    fun `stop saves a ride that covered enough distance and resets`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val history = FakeHistoryRepository()
            val tracker = newTracker(testScheduler, sensor, history)

            tracker.start()
            // ~11 m over 1 s at a reliable accuracy → distance > 10 m save floor.
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            assertThat(tracker.state.value.metrics.distanceKm).isGreaterThan(0.01)

            tracker.stop()

            assertThat(history.saved).hasSize(1)
            assertThat(sensor.stopped).isTrue()
            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.IDLE)
            assertThat(tracker.state.value.metrics.distanceKm).isEqualTo(0.0)
        }

    @Test
    fun `stop does not save a ride below the distance floor`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val history = FakeHistoryRepository()
            val tracker = newTracker(testScheduler, sensor, history)

            tracker.start()
            // Stationary samples → no distance accumulated.
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f))

            tracker.stop()

            assertThat(history.saved).isEmpty()
            assertThat(tracker.state.value.status).isEqualTo(TrackingStatus.IDLE)
        }

    @Test
    fun `recordLap captures the segment since the previous lap`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker = newTracker(testScheduler, sensor)

            tracker.start()
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            val distanceAfterFirst = tracker.state.value.metrics.distanceKm

            tracker.recordLap()
            assertThat(tracker.state.value.laps).hasSize(1)
            assertThat(
                tracker.state.value.laps
                    .first()
                    .lapNumber,
            ).isEqualTo(1)
            assertThat(
                tracker.state.value.laps
                    .first()
                    .distanceKm,
            ).isEqualTo(distanceAfterFirst)

            sensor.samples.emit(sampleAt(2000L, latitude = 0.0002, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            tracker.recordLap()
            assertThat(tracker.state.value.laps).hasSize(2)
            // Second lap is the delta, so its distance is below the running total.
            assertThat(
                tracker.state.value.laps[1]
                    .distanceKm,
            ).isGreaterThan(0.0)
        }

    @Test
    fun `setGoal and clearGoal update the active goal`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker = newTracker(testScheduler, sensor)

            tracker.start()
            tracker.setGoal(RideGoal.Distance(targetKm = 20.0))
            assertThat(tracker.state.value.activeGoal).isEqualTo(RideGoal.Distance(20.0))

            tracker.clearGoal()
            assertThat(tracker.state.value.activeGoal).isNull()
        }

    @Test
    fun `stop clears laps and goal`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker = newTracker(testScheduler, sensor)

            tracker.start()
            tracker.setGoal(RideGoal.Duration(targetSeconds = 600L))
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            tracker.recordLap()

            tracker.stop()

            assertThat(tracker.state.value.laps).isEmpty()
            assertThat(tracker.state.value.activeGoal).isNull()
        }

    @Test
    fun `a BLE sample updates live metrics without a GPS fix`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            // No GPS sample emitted at all — only a heart-rate notification.
            ble.samples.emit(BleSample(timestampMs = 0L, heartRateBpm = 142, cadenceRpm = 88))

            assertThat(tracker.state.value.metrics.heartRateBpm).isEqualTo(142)
            assertThat(tracker.state.value.metrics.cadenceRpm).isEqualTo(88)
        }

    @Test
    fun `a BLE disconnect clears the connected flag`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(BleSample(timestampMs = 0L, heartRateBpm = 142, cadenceRpm = 88, connected = true))
            assertThat(tracker.state.value.bleSensorConnected).isTrue()

            ble.samples.emit(BleSample(timestampMs = 1000L, connected = false))
            assertThat(tracker.state.value.bleSensorConnected).isFalse()
        }

    @Test
    fun `HR set by a BLE sample survives a subsequent GPS-only metrics update`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(BleSample(timestampMs = 0L, heartRateBpm = 142, cadenceRpm = 88, connected = true))
            assertThat(tracker.state.value.metrics.heartRateBpm).isEqualTo(142)

            // A GPS fix must not clobber the HR/cadence the BLE collector just
            // committed to state — it should read the live value at commit
            // time inside the same atomic update, not a snapshot taken
            // before it.
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))

            assertThat(tracker.state.value.metrics.heartRateBpm).isEqualTo(142)
            assertThat(tracker.state.value.metrics.cadenceRpm).isEqualTo(88)
        }

    @Test
    fun `over-speed alert fires once when speed crosses the threshold`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker =
                newTracker(
                    testScheduler,
                    sensor,
                    settings = settings.copy(alerts = AlertConfig(maxSpeedKmh = 30.0)),
                )
            val alerts = mutableListOf<RideAlert>()
            val collectorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            collectorScope.launch { tracker.alerts.collect { alerts.add(it) } }

            tracker.start()
            // 10 m/s = 36 km/h, above the 30 km/h limit.
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            // Still over the limit — must not re-fire (edge-triggered).
            sensor.samples.emit(sampleAt(2000L, latitude = 0.0002, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))

            assertThat(alerts).hasSize(1)
            assertThat(alerts.first()).isInstanceOf(RideAlert.OverSpeed::class)
            collectorScope.cancel()
        }

    @Test
    fun `cadence drops to zero after 3 seconds without a fresh BLE update`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(
                BleSample(timestampMs = 0L, heartRateBpm = 142, cadenceRpm = 88, cadenceUpdatedAtMs = 0L, connected = true),
            )
            assertThat(tracker.state.value.metrics.cadenceRpm).isEqualTo(88)

            // A GPS fix 3.5s later with no intervening cadence update: cadence
            // should read 0 (the sensor stopped notifying), while HR is unaffected.
            sensor.samples.emit(sampleAt(3_500L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))

            assertThat(tracker.state.value.metrics.cadenceRpm).isEqualTo(0)
            assertThat(tracker.state.value.metrics.heartRateBpm).isEqualTo(142)
        }

    @Test
    fun `a fresh cadence update before the timeout keeps the real value`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(BleSample(timestampMs = 0L, cadenceRpm = 88, cadenceUpdatedAtMs = 0L, connected = true))
            ble.samples.emit(BleSample(timestampMs = 2_000L, cadenceRpm = 90, cadenceUpdatedAtMs = 2_000L, connected = true))
            // 4500 - 2000 = 2500ms since the last cadence update, under the 3000ms timeout.
            sensor.samples.emit(sampleAt(4_500L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))

            assertThat(tracker.state.value.metrics.cadenceRpm).isEqualTo(90)
        }

    @Test
    fun `an implausible heart-rate spike is rejected and the last good value is kept`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(BleSample(timestampMs = 0L, heartRateBpm = 145, connected = true))
            ble.samples.emit(BleSample(timestampMs = 1_000L, heartRateBpm = 255, connected = true))

            assertThat(tracker.state.value.metrics.heartRateBpm).isEqualTo(145)
        }

    @Test
    fun `measured watts from a paired meter reach the published metrics`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(
                BleSample(timestampMs = 0L, powerWatts = 243, powerUpdatedAtMs = 0L, connected = true),
            )
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1_000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))

            assertThat(tracker.state.value.metrics.powerWatts).isEqualTo(243)
            assertThat(tracker.state.value.metrics.powerSource).isEqualTo(PowerSource.MEASURED)
        }

    @Test
    fun `power reverts to the estimate once the meter has been quiet too long`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val ble = FakeBleSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, ble = ble)

            tracker.start()
            ble.samples.emit(
                BleSample(timestampMs = 0L, powerWatts = 243, powerUpdatedAtMs = 0L, connected = true),
            )
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1_000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))
            assertThat(tracker.state.value.metrics.powerSource).isEqualTo(PowerSource.MEASURED)

            // No fresh packet for well past the 3s window: the meter has gone
            // quiet, so its last reading must stop standing in for a live one.
            sensor.samples.emit(sampleAt(10_000L, latitude = 0.0002, longitude = 0.0, speedFromGpsMps = 8.0, accuracy = 5.0f))

            assertThat(tracker.state.value.metrics.powerSource).isEqualTo(PowerSource.ESTIMATED)
        }

    @Test
    fun `a ride row is created at start so an interrupted ride survives`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val history = FakeHistoryRepository()
            val tracker = newTracker(testScheduler, sensor, history)

            tracker.start()

            // The row must exist before any sample arrives — that is the whole point.
            assertThat(history.allRides).hasSize(1)
            assertThat(history.allRides.single().isComplete).isFalse()
            // ...and stay out of history until the ride is actually finished.
            assertThat(history.saved).isEmpty()
        }

    @Test
    fun `the in-progress row is finished in place rather than inserted again at stop`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val history = FakeHistoryRepository()
            val tracker = newTracker(testScheduler, sensor, history)

            tracker.start()
            val rideId = history.allRides.single().id
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))

            tracker.stop()

            // One row throughout: the ride keeps the id its samples were written
            // against, rather than a second row appearing at stop.
            assertThat(history.allRides).hasSize(1)
            assertThat(history.allRides.single().id).isEqualTo(rideId)
            assertThat(history.allRides.single().isComplete).isTrue()
            assertThat(history.saved.single().distanceKm).isGreaterThan(0.01)
        }

    @Test
    fun `a ride below the distance floor leaves no row behind`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val history = FakeHistoryRepository()
            val tracker = newTracker(testScheduler, sensor, history)

            tracker.start()
            sensor.samples.emit(sampleAt(0L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(1000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 0.0, accuracy = 5.0f))

            tracker.stop()

            // Not merely absent from history: the placeholder row is deleted, so
            // it cannot come back as something to "recover" on the next launch.
            assertThat(history.allRides).isEmpty()
        }

    @Test
    fun `recovery finishes an interrupted ride that covered enough distance`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val history = FakeHistoryRepository()
            val samples = FakeSampleRepository()
            val tracker = newTracker(testScheduler, sensor, history, samples)

            val rideId = (history.startRide(startedAt = 1_000L) as Result.Success).data
            samples.saveSamples(
                rideId,
                listOf(
                    RideSample(timestampMs = 1_000L, latitude = 52.0, longitude = 0.0, speedKmh = 20.0),
                    RideSample(timestampMs = 61_000L, latitude = 52.003, longitude = 0.0, speedKmh = 20.0),
                ),
            )

            tracker.recoverUnfinishedRides()

            val recovered = history.allRides.single()
            assertThat(recovered.isComplete).isTrue()
            // ~333 m between those two latitudes, rebuilt from the samples alone.
            assertThat(recovered.distanceKm).isGreaterThan(0.3)
            assertThat(recovered.maxSpeedKmh).isEqualTo(20.0)
            // Both samples cleared the auto-pause threshold, so both count as moving.
            assertThat(recovered.movingTimeSeconds).isEqualTo(2L)
            assertThat(recovered.elapsedTimeSeconds).isEqualTo(60L)
        }

    @Test
    fun `recovery deletes an interrupted ride that never went anywhere`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val history = FakeHistoryRepository()
            val tracker = newTracker(testScheduler, sensor, history)

            history.startRide(startedAt = 1_000L)

            tracker.recoverUnfinishedRides()

            assertThat(history.allRides).isEmpty()
        }

    @Test
    fun `recovery leaves finished rides alone`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val history = FakeHistoryRepository()
            val tracker = newTracker(testScheduler, sensor, history)

            val finished =
                RideRecord(
                    id = 0L,
                    startTimestamp = 1_000L,
                    endTimestamp = 2_000L,
                    distanceKm = 12.5,
                    movingTimeSeconds = 1_800L,
                    elapsedTimeSeconds = 2_000L,
                    averageSpeedKmh = 25.0,
                    maxSpeedKmh = 42.0,
                    elevationGainM = 120.0,
                    caloriesKcal = 400.0,
                )
            history.save(finished)

            tracker.recoverUnfinishedRides()

            // A completed ride is not an interrupted one: recovery must not
            // rewrite its aggregates from a sample stream it no longer has.
            assertThat(history.saved.single().distanceKm).isEqualTo(12.5)
            assertThat(history.saved.single().maxSpeedKmh).isEqualTo(42.0)
        }

    @Test
    fun `samples recorded during a ride are flushed against the ride's own row`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val history = FakeHistoryRepository()
            val samples = FakeSampleRepository()
            val tracker = newTracker(testScheduler, sensor, history, samples)

            tracker.start()
            val rideId = history.allRides.single().id
            // Three seconds of riding: one buffered sample per second.
            sensor.samples.emit(sampleAt(1_000L, latitude = 0.0, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(2_000L, latitude = 0.0001, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))
            sensor.samples.emit(sampleAt(3_000L, latitude = 0.0002, longitude = 0.0, speedFromGpsMps = 10.0, accuracy = 5.0f))

            tracker.stop()

            // The tail is flushed at stop, keyed by the row created at start —
            // no batch is stranded waiting for the flush interval to elapse.
            assertThat(samples.saved[rideId]).isNotNull()
            assertThat(samples.saved.getValue(rideId).map { it.timestampMs })
                .containsExactly(1_000L, 2_000L, 3_000L)
        }

    @Test
    fun `training load rides along in the tracking state`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker =
                newTracker(testScheduler, sensor, settings = settings.copy(ftpWatts = 200))

            tracker.start()
            repeat(120) { second ->
                sensor.samples.emit(
                    sampleAt(
                        second * 1000L,
                        latitude = 0.000072 * second,
                        longitude = 0.0,
                        speedFromGpsMps = 8.0,
                        accuracy = 4.0f,
                    ),
                )
            }

            assertThat(tracker.state.value.trainingMetrics.workKj).isGreaterThan(0.0)
        }

    @Test
    fun `finishing a ride writes the thresholds that were in force`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val history = FakeHistoryRepository()
            val tracker =
                newTracker(
                    testScheduler,
                    sensor,
                    history,
                    settings = settings.copy(ftpWatts = 210, lthrBpm = 168),
                )

            tracker.start()
            repeat(120) { second ->
                sensor.samples.emit(
                    sampleAt(
                        second * 1000L,
                        latitude = 0.000072 * second,
                        longitude = 0.0,
                        speedFromGpsMps = 8.0,
                        accuracy = 4.0f,
                    ),
                )
            }
            tracker.stop()

            val saved = history.saved.single()
            // Pinned at ride time so a later FTP change cannot rewrite this ride's load.
            assertThat(saved.ftpAtRideWatts).isEqualTo(210)
            assertThat(saved.lthrAtRideBpm).isEqualTo(168)
        }

    @Test
    fun `training load resets between rides`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker = newTracker(testScheduler, sensor, settings = settings.copy(ftpWatts = 200))

            tracker.start()
            repeat(60) { second ->
                sensor.samples.emit(
                    sampleAt(second * 1000L, latitude = 0.000072 * second, speedFromGpsMps = 8.0, accuracy = 4.0f),
                )
            }
            val firstRideWork = tracker.state.value.trainingMetrics.workKj
            assertThat(firstRideWork).isGreaterThan(0.0)
            tracker.stop()

            tracker.start()

            // A second ride must not inherit the first one's accumulated load.
            assertThat(tracker.state.value.trainingMetrics.workKj).isEqualTo(0.0)
        }

    @Test
    fun `a lap closes every whole kilometre when auto-lap is set to distance`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker =
                newTracker(
                    testScheduler,
                    sensor,
                    settings = settings.copy(autoLap = AutoLapConfig(AutoLapMode.DISTANCE, distanceKm = 1.0)),
                )

            tracker.start()
            // ~2.4 km at 8 m/s over 300 s.
            repeat(300) { second ->
                sensor.samples.emit(
                    sampleAt(
                        second * 1000L,
                        latitude = 0.000072 * second,
                        longitude = 0.0,
                        speedFromGpsMps = 8.0,
                        accuracy = 4.0f,
                    ),
                )
            }

            assertThat(tracker.state.value.laps).hasSize(2)
        }

    @Test
    fun `no laps are recorded when auto-lap is off`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker = newTracker(testScheduler, sensor)

            tracker.start()
            repeat(300) { second ->
                sensor.samples.emit(
                    sampleAt(
                        second * 1000L,
                        latitude = 0.000072 * second,
                        longitude = 0.0,
                        speedFromGpsMps = 8.0,
                        accuracy = 4.0f,
                    ),
                )
            }

            assertThat(tracker.state.value.laps).isEmpty()
        }

    @Test
    fun `a lap closes on each interval when auto-lap is set to time`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker =
                newTracker(
                    testScheduler,
                    sensor,
                    settings = settings.copy(autoLap = AutoLapConfig(AutoLapMode.TIME, intervalMinutes = 1)),
                )

            tracker.start()
            // 150 s of riding at one lap a minute.
            repeat(150) { second ->
                sensor.samples.emit(
                    sampleAt(
                        second * 1000L,
                        latitude = 0.000072 * second,
                        longitude = 0.0,
                        speedFromGpsMps = 8.0,
                        accuracy = 4.0f,
                    ),
                )
            }

            assertThat(tracker.state.value.laps).hasSize(2)
        }

    @Test
    fun `auto-lap boundaries do not carry over into the next ride`() =
        runTest {
            val sensor = FakeSensorDataSource()
            val tracker =
                newTracker(
                    testScheduler,
                    sensor,
                    settings = settings.copy(autoLap = AutoLapConfig(AutoLapMode.DISTANCE, distanceKm = 1.0)),
                )

            tracker.start()
            repeat(300) { second ->
                sensor.samples.emit(
                    sampleAt(second * 1000L, latitude = 0.000072 * second, speedFromGpsMps = 8.0, accuracy = 4.0f),
                )
            }
            tracker.stop()

            tracker.start()

            // A stale boundary from the previous ride would either fire a lap
            // immediately or swallow the first kilometre of this one.
            assertThat(tracker.state.value.laps).isEmpty()
        }

    private fun newTracker(
        scheduler: TestCoroutineScheduler,
        sensor: FakeSensorDataSource,
        history: FakeHistoryRepository = FakeHistoryRepository(),
        samples: FakeSampleRepository = FakeSampleRepository(),
        autoPauseDelayMs: Long = 3_000L,
        ble: FakeBleSensorDataSource = FakeBleSensorDataSource(),
        settings: UserSettings = this.settings,
    ) = RideTracker(
        sensorDataSource = sensor,
        // warmupReliableFixes = 1 disables the GPS cold-start warm-up gate so
        // these tracker-orchestration tests see metrics from the first reliable
        // fix; warm-up itself is covered in RideMetricsCalculatorTest.
        metricsCalculator = RideMetricsCalculator(warmupReliableFixes = 1),
        historyRepository = history,
        trackPointRepository = FakeTrackPointRepository(),
        sampleRepository = samples,
        lapRepository = FakeLapRepository(),
        bleSensorDataSource = ble,
        userSettingsRepository = FakeUserSettingsRepository(settings),
        autoPauseDelayMs = autoPauseDelayMs,
        scope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
    )

    private fun sampleAt(
        timestampMs: Long,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        speedFromGpsMps: Double? = null,
        accuracy: Float? = null,
    ) = RideSensorSample(
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        speedFromGpsMps = speedFromGpsMps,
        accuracyM = accuracy,
    )
}

private class FakeSensorDataSource : RideSensorDataSource {
    val samples = MutableSharedFlow<RideSensorSample>(extraBufferCapacity = 64)
    var startResult: EmptyResult<SensorError> = Result.Success(Unit)
    var started = false
    var stopped = false

    override fun observeSamples(): Flow<RideSensorSample> = samples

    override fun start(): EmptyResult<SensorError> {
        started = true
        return startResult
    }

    override fun stop() {
        stopped = true
    }
}

/**
 * Models the real row lifecycle rather than a write log: a ride row is inserted
 * incomplete at start, updated to complete at stop, and deleted outright when it
 * never covered enough ground. [saved] is therefore what history would actually
 * show, and [allRides] is the raw table including a ride still in progress.
 */
private class FakeHistoryRepository : RideHistoryRepository {
    val allRides = mutableListOf<RideRecord>()

    /** Only finished rides — what `observeAll`'s `WHERE isComplete = 1` would return. */
    val saved: List<RideRecord> get() = allRides.filter { it.isComplete }

    private var nextId = 1L

    override fun observeAll(): Flow<List<RideRecord>> = MutableStateFlow(saved)

    override suspend fun getById(id: Long): Result<RideRecord, DataError.Local> =
        allRides.firstOrNull { it.id == id }?.let { Result.Success(it) }
            ?: Result.Error(DataError.Local.NOT_FOUND)

    override suspend fun save(ride: RideRecord): Result<Long, DataError.Local> {
        val id = if (ride.id == 0L) nextId++ else ride.id
        allRides.removeAll { it.id == id }
        allRides.add(ride.copy(id = id))
        return Result.Success(id)
    }

    override suspend fun startRide(startedAt: Long): Result<Long, DataError.Local> =
        save(
            RideRecord(
                id = 0L,
                startTimestamp = startedAt,
                endTimestamp = startedAt,
                distanceKm = 0.0,
                movingTimeSeconds = 0L,
                elapsedTimeSeconds = 0L,
                averageSpeedKmh = 0.0,
                maxSpeedKmh = 0.0,
                elevationGainM = 0.0,
                caloriesKcal = 0.0,
                isComplete = false,
            ),
        )

    override suspend fun finishRide(ride: RideRecord): EmptyResult<DataError.Local> {
        val index = allRides.indexOfFirst { it.id == ride.id }
        if (index < 0) return Result.Error(DataError.Local.NOT_FOUND)
        allRides[index] = ride.copy(isComplete = true)
        return Result.Success(Unit)
    }

    override suspend fun getUnfinishedRides(): Result<List<RideRecord>, DataError.Local> =
        Result.Success(allRides.filterNot { it.isComplete }.sortedBy { it.startTimestamp })

    override suspend fun deleteById(id: Long): EmptyResult<DataError.Local> {
        allRides.removeAll { it.id == id }
        return Result.Success(Unit)
    }

    override suspend fun deleteAll(): EmptyResult<DataError.Local> {
        allRides.clear()
        return Result.Success(Unit)
    }
}

private class FakeSampleRepository : RideSampleRepository {
    val saved = mutableMapOf<Long, MutableList<RideSample>>()

    override suspend fun saveSamples(
        rideId: Long,
        samples: List<RideSample>,
    ): EmptyResult<DataError.Local> {
        saved.getOrPut(rideId) { mutableListOf() } += samples
        return Result.Success(Unit)
    }

    override suspend fun getSamples(rideId: Long): Result<List<RideSample>, DataError.Local> =
        Result.Success(saved[rideId].orEmpty().sortedBy { it.timestampMs })
}

private class FakeTrackPointRepository : RideTrackPointRepository {
    val saved = mutableMapOf<Long, List<RideTrackPoint>>()

    override suspend fun savePoints(
        rideId: Long,
        points: List<RideTrackPoint>,
    ): EmptyResult<DataError.Local> {
        saved[rideId] = points
        return Result.Success(Unit)
    }

    override suspend fun getPoints(rideId: Long): Result<List<RideTrackPoint>, DataError.Local> =
        Result.Success(saved[rideId] ?: emptyList())
}

private class FakeLapRepository : RideLapRepository {
    val saved = mutableMapOf<Long, List<LapRecord>>()

    override suspend fun saveLaps(
        rideId: Long,
        laps: List<LapRecord>,
    ): EmptyResult<DataError.Local> {
        saved[rideId] = laps
        return Result.Success(Unit)
    }

    override suspend fun getLaps(rideId: Long): Result<List<LapRecord>, DataError.Local> = Result.Success(saved[rideId] ?: emptyList())
}

private class FakeBleSensorDataSource : BleSensorDataSource {
    val samples = MutableSharedFlow<BleSample>(extraBufferCapacity = 16)

    override fun observeSamples(): Flow<BleSample> = samples

    override fun connect(sensors: PairedSensors) = Unit

    override fun disconnect() = Unit
}

private class FakeUserSettingsRepository(
    private val settings: UserSettings,
) : UserSettingsRepository {
    override fun observeSettings(): Flow<UserSettings> = MutableStateFlow(settings)

    override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> = Result.Success(Unit)
}
