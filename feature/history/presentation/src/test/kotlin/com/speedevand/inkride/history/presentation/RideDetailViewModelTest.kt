package com.speedevand.inkride.history.presentation

import android.net.Uri
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideSample
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.testing.fakes.FakeRideHistoryRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideLapRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideSampleRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideTrackPointRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import com.speedevand.inkride.core.testing.support.TestRides
import com.speedevand.inkride.core.testing.support.TestSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideDetailViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val rideRepo = FakeRideHistoryRepository()
    private val lapRepo = FakeRideLapRepository()
    private val trackPointRepo = FakeRideTrackPointRepository()
    private val sampleRepo = FakeRideSampleRepository()
    private val settingsRepo = FakeUserSettingsRepository()
    private val gpxExporter = FakeGpxExporter()

    private val sampleRide =
        RideRecord(
            id = 1L,
            startTimestamp = 0L,
            endTimestamp = 1000L,
            distanceKm = 10.0,
            movingTimeSeconds = 600L,
            elapsedTimeSeconds = 1200L,
            averageSpeedKmh = 20.0,
            maxSpeedKmh = 30.0,
            elevationGainM = 50.0,
            caloriesKcal = 200.0,
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `load by ID success populates state`() =
        runTest {
            rideRepo.emitRides(listOf(sampleRide))
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.ride).isNotNull()
            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
        }

    @Test
    fun `load by ID failure shows error`() =
        runTest {
            rideRepo.getByIdResult = Result.Error(DataError.Local.NOT_FOUND)
            val viewModel = RideDetailViewModel(99L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
            assertThat(viewModel.state.value.ride).isEqualTo(null)
        }

    @Test
    fun `back click navigates back`() =
        runTest {
            rideRepo.emitRides(listOf(sampleRide))
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            viewModel.events.test {
                viewModel.onAction(RideDetailAction.OnBackClick)
                val event = awaitItem()
                assertThat(event).isEqualTo(RideDetailEvent.NavigateBack)
            }
        }

    @Test
    fun `delete click deletes and navigates back`() =
        runTest {
            rideRepo.emitRides(listOf(sampleRide))
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            viewModel.events.test {
                viewModel.onAction(RideDetailAction.OnDeleteClick)
                val event = awaitItem()
                assertThat(event).isEqualTo(RideDetailEvent.NavigateBack)
            }
        }

    @Test
    fun `export with no track shows error`() =
        runTest {
            rideRepo.emitRides(listOf(sampleRide))
            gpxExporter.result = Result.Error(GpxExportError.NO_TRACK)
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            viewModel.events.test {
                viewModel.onAction(RideDetailAction.OnExportGpxClick)
                val event = awaitItem()
                assertThat(event).isInstanceOf<RideDetailEvent.ShowError>()
            }
        }

    @Test
    fun `track points populate route state`() =
        runTest {
            rideRepo.emitRides(listOf(sampleRide))
            trackPointRepo.setPoints(
                1L,
                listOf(
                    RideTrackPoint(timestampMs = 0L, latitude = 52.1, longitude = 21.0),
                    RideTrackPoint(timestampMs = 1000L, latitude = 52.2, longitude = 21.1),
                ),
            )
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.trackPoints).isEqualTo(
                listOf(TrackPointUi(52.1, 21.0), TrackPointUi(52.2, 21.1)),
            )
        }

    @Test
    fun `track points with altitude populate the elevation chart`() =
        runTest {
            rideRepo.emitRides(listOf(sampleRide))
            trackPointRepo.setPoints(
                1L,
                listOf(
                    RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0, altitudeM = 100.0),
                    RideTrackPoint(timestampMs = 1000L, latitude = 52.01, longitude = 21.0, altitudeM = 150.0),
                ),
            )
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.elevationChart).isNotNull()
        }

    @Test
    fun `track points without altitude are still loaded for the route map, but leave the elevation chart null`() =
        runTest {
            rideRepo.emitRides(listOf(sampleRide))
            trackPointRepo.setPoints(
                1L,
                listOf(
                    RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0),
                    RideTrackPoint(timestampMs = 1000L, latitude = 52.01, longitude = 21.0),
                ),
            )
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.trackPoints).isEqualTo(
                listOf(TrackPointUi(52.0, 21.0), TrackPointUi(52.01, 21.0)),
            )
            assertThat(viewModel.state.value.elevationChart).isNull()
        }

    class FakeGpxExporter : GpxExporter {
        var result: Result<Uri, GpxExportError> = Result.Error(GpxExportError.NO_TRACK)

        override suspend fun export(rideId: Long): Result<Uri, GpxExportError> = result
    }

    @Test
    fun `a ride recorded before training metrics existed shows them as unavailable`() =
        runTest {
            rideRepo.getByIdResult =
                Result.Success(
                    TestRides.record(id = 1L).copy(trainingStressScore = null, normalizedPowerWatts = null),
                )

            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            // Nothing to backfill: the stream was never recorded. Absent, not zero.
            assertThat(viewModel.state.value.training.trainingStressScore).isEqualTo("--")
            assertThat(viewModel.state.value.training.hasAnyTrainingData).isEqualTo(false)
        }

    @Test
    fun `zone durations are read back from the sample stream`() =
        runTest {
            rideRepo.getByIdResult = Result.Success(TestRides.record(id = 1L).copy(lthrAtRideBpm = 160))
            sampleRepo.setSamples(
                rideId = 1L,
                samples = (0 until 600).map { RideSample(timestampMs = it * 1000L, heartRateBpm = 120) },
            )

            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            // HRmax(Tanaka, 30) = 187, so 120 bpm is 64 % -> zone 2.
            assertThat(viewModel.state.value.training.secondsInHrZone[2]).isEqualTo(600L)
            assertThat(viewModel.state.value.training.hasAnyTrainingData).isEqualTo(true)
        }

    @Test
    fun `zones are measured against the ride's own thresholds, not today's`() =
        runTest {
            // The ride was ridden at FTP 200; the rider has since raised it to 400.
            rideRepo.getByIdResult = Result.Success(TestRides.record(id = 1L).copy(ftpAtRideWatts = 200))
            settingsRepo.emitSettings(TestSettings.default().copy(ftpWatts = 400))
            sampleRepo.setSamples(
                rideId = 1L,
                samples = (0 until 300).map { RideSample(timestampMs = it * 1000L, powerWatts = 200) },
            )

            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            // 200 W against the ride's own 200 W FTP is zone 4. Against today's
            // 400 it would be zone 1, which would rewrite history.
            assertThat(viewModel.state.value.training.secondsInPowerZone[4]).isEqualTo(300L)
        }

    @Test
    fun `accepting a proposed threshold applies it and clears the candidate`() =
        runTest {
            rideRepo.getByIdResult = Result.Success(TestRides.record(id = 1L))
            settingsRepo.emitSettings(TestSettings.default().copy(ftpWatts = 200, pendingFtpWatts = 260))
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)
            assertThat(
                viewModel.state.value.thresholdProposal
                    ?.ftpWatts,
            ).isEqualTo(260)

            viewModel.onAction(RideDetailAction.OnAcceptThresholdProposal)

            assertThat(settingsRepo.lastSaved?.ftpWatts).isEqualTo(260)
            assertThat(settingsRepo.lastSaved?.pendingFtpWatts).isEqualTo(null)
        }

    @Test
    fun `rejecting a proposed threshold leaves the standing one untouched`() =
        runTest {
            rideRepo.getByIdResult = Result.Success(TestRides.record(id = 1L))
            settingsRepo.emitSettings(TestSettings.default().copy(ftpWatts = 200, pendingFtpWatts = 260))
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, sampleRepo, settingsRepo, gpxExporter)

            viewModel.onAction(RideDetailAction.OnRejectThresholdProposal)

            assertThat(settingsRepo.lastSaved?.ftpWatts).isEqualTo(200)
            assertThat(settingsRepo.lastSaved?.pendingFtpWatts).isEqualTo(null)
        }
}
