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
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.testing.fakes.FakeRideHistoryRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideLapRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideTrackPointRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
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
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.ride).isNotNull()
            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
        }

    @Test
    fun `load by ID failure shows error`() =
        runTest {
            rideRepo.getByIdResult = Result.Error(DataError.Local.NOT_FOUND)
            val viewModel = RideDetailViewModel(99L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
            assertThat(viewModel.state.value.ride).isEqualTo(null)
        }

    @Test
    fun `back click navigates back`() =
        runTest {
            rideRepo.emitRides(listOf(sampleRide))
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

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
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

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
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

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
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

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
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

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
            val viewModel = RideDetailViewModel(1L, rideRepo, lapRepo, trackPointRepo, settingsRepo, gpxExporter)

            assertThat(viewModel.state.value.trackPoints).isEqualTo(
                listOf(TrackPointUi(52.0, 21.0), TrackPointUi(52.01, 21.0)),
            )
            assertThat(viewModel.state.value.elevationChart).isNull()
        }

    class FakeGpxExporter : GpxExporter {
        var result: Result<Uri, GpxExportError> = Result.Error(GpxExportError.NO_TRACK)

        override suspend fun export(rideId: Long): Result<Uri, GpxExportError> = result
    }
}
