package com.speedevand.inkride.history.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.tracking.LapRecord
import com.speedevand.inkride.core.testing.fakes.FakeRideHistoryRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideLapRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideTrackPointRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RideHistoryViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val settingsRepo = FakeUserSettingsRepository()
    private val trackPointRepo = FakeRideTrackPointRepository()
    private val lapRepo = FakeRideLapRepository()
    private val rideRepo = FakeRideHistoryRepository(lapRepository = lapRepo, trackPointRepository = trackPointRepo)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = RideHistoryViewModel(rideRepo, settingsRepo, trackPointRepo, lapRepo)

    private suspend fun currentRides(): List<RideRecord> = rideRepo.observeAll().first()

    @Test
    fun `initial state shows loading then becomes false after flow emits`() =
        runTest {
            val viewModel = viewModel()
            // After combine emits (immediate with UnconfinedTestDispatcher), loading becomes false
            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
            assertThat(viewModel.state.value.rides).isEqualTo(emptyList())
        }

    @Test
    fun `combine flows produces list of UI models`() =
        runTest {
            val ride =
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
            rideRepo.emitRides(listOf(ride))

            val viewModel = viewModel()

            assertThat(viewModel.state.value.rides.size).isEqualTo(1)
            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
        }

    @Test
    fun `empty rides produces empty list`() =
        runTest {
            rideRepo.emitRides(emptyList())
            val viewModel = viewModel()

            assertThat(viewModel.state.value.rides).isEqualTo(emptyList())
            assertThat(viewModel.state.value.isLoading).isEqualTo(false)
        }

    @Test
    fun `ride click sends NavigateToDetail event`() =
        runTest {
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnRideClick(42L))
                val event = awaitItem()
                assertThat(event).isEqualTo(RideHistoryEvent.NavigateToDetail(42L))
            }
        }

    @Test
    fun `delete ride sends undo snackbar`() =
        runTest {
            val ride =
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
            rideRepo.emitRides(listOf(ride))

            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
                val event = awaitItem()
                assertThat(event).isEqualTo(RideHistoryEvent.ShowUndoSnackbar)
            }
        }

    @Test
    fun `undo restores recently deleted ride`() =
        runTest {
            val ride =
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
            rideRepo.emitRides(listOf(ride))

            val viewModel = viewModel()

            // Delete the ride
            viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
            assertThat(currentRides().size).isEqualTo(0)

            // Undo
            viewModel.onAction(RideHistoryAction.OnUndoDelete)
            assertThat(currentRides().size).isEqualTo(1)
        }

    @Test
    fun `undo restores track points and laps that were deleted`() =
        runTest {
            val ride =
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
            rideRepo.emitRides(listOf(ride))
            val points = listOf(RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0))
            val laps =
                listOf(LapRecord(lapNumber = 1, distanceKm = 5.0, movingTimeSeconds = 300L, averageSpeedKmh = 20.0, elevationGainM = 10.0))
            trackPointRepo.setPoints(1L, points)
            lapRepo.setLaps(1L, laps)

            val viewModel = viewModel()

            viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
            // rideRepo is wired to lapRepo/trackPointRepo, so deleting the ride above already
            // cascaded and cleared these — proving restore happens from the ViewModel's cached
            // copy, not a re-read of the (now-empty) repositories.
            assertThat(trackPointRepo.getPoints(1L)).isEqualTo(Result.Success(emptyList()))
            assertThat(lapRepo.getLaps(1L)).isEqualTo(Result.Success(emptyList()))

            viewModel.onAction(RideHistoryAction.OnUndoDelete)

            val restoredRideId = currentRides().first().id
            assertThat(trackPointRepo.getPoints(restoredRideId)).isEqualTo(Result.Success(points))
            assertThat(lapRepo.getLaps(restoredRideId)).isEqualTo(Result.Success(laps))
        }

    @Test
    fun `delete all clears all rides`() =
        runTest {
            val rides =
                (0 until 3).map { i ->
                    RideRecord(
                        id = i.toLong(),
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
                }
            rideRepo.emitRides(rides)

            val viewModel = viewModel()
            viewModel.onAction(RideHistoryAction.OnDeleteAll)
            assertThat(currentRides().size).isEqualTo(0)
        }

    @Test
    fun `delete ride with getPoints failure sends error event`() =
        runTest {
            val ride =
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
            rideRepo.emitRides(listOf(ride))
            trackPointRepo.getResult = Result.Error(DataError.Local.UNKNOWN)

            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
                val errorEvent = awaitItem()
                assertThat(errorEvent).isInstanceOf<RideHistoryEvent.ShowError>()
                val undoEvent = awaitItem()
                assertThat(undoEvent).isEqualTo(RideHistoryEvent.ShowUndoSnackbar)
                // Verify ride was still deleted despite error
                assertThat(currentRides().size).isEqualTo(0)
            }
        }

    @Test
    fun `delete ride with getLaps failure sends error event`() =
        runTest {
            val ride =
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
            rideRepo.emitRides(listOf(ride))
            lapRepo.getResult = Result.Error(DataError.Local.UNKNOWN)

            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
                val errorEvent = awaitItem()
                assertThat(errorEvent).isInstanceOf<RideHistoryEvent.ShowError>()
                val undoEvent = awaitItem()
                assertThat(undoEvent).isEqualTo(RideHistoryEvent.ShowUndoSnackbar)
                // Verify ride was still deleted despite error
                assertThat(currentRides().size).isEqualTo(0)
            }
        }

    @Test
    fun `undo delete with savePoints failure sends error event`() =
        runTest {
            val ride =
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
            rideRepo.emitRides(listOf(ride))
            val points = listOf(RideTrackPoint(timestampMs = 0L, latitude = 52.0, longitude = 21.0))
            trackPointRepo.setPoints(1L, points)

            val viewModel = viewModel()

            // Delete the ride (consume events from delete)
            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
                awaitItem() // ShowUndoSnackbar
            }

            // Set up save error for undo
            trackPointRepo.saveResult = Result.Error(DataError.Local.UNKNOWN)

            // Undo and check for error
            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnUndoDelete)
                val errorEvent = awaitItem()
                assertThat(errorEvent).isInstanceOf<RideHistoryEvent.ShowError>()
            }

            // Verify ride was still restored despite savePoints failure
            assertThat(currentRides().size).isEqualTo(1)
        }

    @Test
    fun `undo delete with saveLaps failure sends error event`() =
        runTest {
            val ride =
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
            rideRepo.emitRides(listOf(ride))
            val laps =
                listOf(LapRecord(lapNumber = 1, distanceKm = 5.0, movingTimeSeconds = 300L, averageSpeedKmh = 20.0, elevationGainM = 10.0))
            lapRepo.setLaps(1L, laps)

            val viewModel = viewModel()

            // Delete the ride (consume events from delete)
            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnDeleteRide(1L))
                awaitItem() // ShowUndoSnackbar
            }

            // Set up save error for undo
            lapRepo.saveResult = Result.Error(DataError.Local.UNKNOWN)

            // Undo and check for error
            viewModel.events.test {
                viewModel.onAction(RideHistoryAction.OnUndoDelete)
                val errorEvent = awaitItem()
                assertThat(errorEvent).isInstanceOf<RideHistoryEvent.ShowError>()
            }

            // Verify ride was still restored despite saveLaps failure
            assertThat(currentRides().size).isEqualTo(1)
        }
}
