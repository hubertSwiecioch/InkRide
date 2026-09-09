package com.speedevand.inkride.dashboard.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.DataError
import com.speedevand.inkride.core.domain.EmptyResult
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.ble.BleSample
import com.speedevand.inkride.core.domain.ble.BleSensorDataSource
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideTrackPoint
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.LapRecord
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.LocationFix
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService
import com.speedevand.inkride.core.domain.tracking.PlannedRoute
import com.speedevand.inkride.core.domain.tracking.RideMetricsCalculator
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideSensorSample
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.core.domain.tracking.RoutingError
import com.speedevand.inkride.core.domain.tracking.RoutingService
import com.speedevand.inkride.core.domain.tracking.SensorError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DestinationSearchViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val placeSearchService = FakePlaceSearchService()
    private val routingService = FakeRoutingService()
    private val currentLocationProvider = FakeCurrentLocationProvider()
    private val rideTracker = testRideTracker()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = DestinationSearchViewModel(placeSearchService, routingService, currentLocationProvider, rideTracker)

    @Test
    fun `a query shorter than 3 characters does not trigger a search`() =
        runTest(testDispatcher) {
            val vm = viewModel()

            vm.onAction(DestinationSearchAction.OnQueryChanged("Wa"))
            advanceTimeBy(1_000L)

            assertThat(placeSearchService.queriesReceived).hasSize(0)
        }

    @Test
    fun `a query at least 3 characters long triggers a search after the debounce window`() =
        runTest(testDispatcher) {
            placeSearchService.nextResult = Result.Success(listOf(PlaceResult("Warsaw, Poland", 52.2297, 21.0122)))
            val vm = viewModel()

            vm.onAction(DestinationSearchAction.OnQueryChanged("War"))
            advanceTimeBy(700L)

            assertThat(placeSearchService.queriesReceived).isEqualTo(listOf("War"))
            assertThat(vm.state.value.results).hasSize(1)
        }

    @Test
    fun `only the latest query survives rapid typing`() =
        runTest(testDispatcher) {
            placeSearchService.nextResult = Result.Success(emptyList())
            val vm = viewModel()

            vm.onAction(DestinationSearchAction.OnQueryChanged("War"))
            advanceTimeBy(100L)
            vm.onAction(DestinationSearchAction.OnQueryChanged("Wars"))
            advanceTimeBy(700L)

            assertThat(placeSearchService.queriesReceived).isEqualTo(listOf("Wars"))
        }

    @Test
    fun `a network failure while searching surfaces a ShowError event`() =
        runTest(testDispatcher) {
            placeSearchService.nextResult = Result.Error(PlaceSearchError.NETWORK_FAILED)
            val vm = viewModel()

            vm.events.test {
                vm.onAction(DestinationSearchAction.OnQueryChanged("War"))
                advanceTimeBy(700L)
                assertThat(awaitItem()).isEqualTo(DestinationSearchEvent.ShowError(PlaceSearchError.NETWORK_FAILED.toUiText()))
            }
        }

    @Test
    fun `selecting a result routes from the current location and loads it into RideTracker`() =
        runTest(testDispatcher) {
            currentLocationProvider.nextResult = Result.Success(LocationFix(52.0, 21.0))
            routingService.nextResult = Result.Success(PlannedRoute(name = null, points = emptyList(), waypoints = emptyList()))
            val vm = viewModel()
            val selected = PlaceResult("Warsaw, Poland", 52.2297, 21.0122)

            vm.events.test {
                vm.onAction(DestinationSearchAction.OnResultSelected(selected))
                assertThat(awaitItem()).isEqualTo(DestinationSearchEvent.NavigateBackToDashboard)
            }
            assertThat(
                rideTracker.state.value.activeRoute
                    ?.name,
            ).isEqualTo("Warsaw, Poland")
            assertThat(routingService.lastRequest).isEqualTo(RouteRequest(52.0, 21.0, 52.2297, 21.0122))
        }

    @Test
    fun `a location failure while routing surfaces a ShowError event and never calls the routing service`() =
        runTest(testDispatcher) {
            currentLocationProvider.nextResult = Result.Error(LocationError.TIMED_OUT)
            val vm = viewModel()

            vm.events.test {
                vm.onAction(DestinationSearchAction.OnResultSelected(PlaceResult("Warsaw, Poland", 52.2297, 21.0122)))
                assertThat(awaitItem()).isEqualTo(DestinationSearchEvent.ShowError(LocationError.TIMED_OUT.toUiText()))
            }
            assertThat(routingService.lastRequest).isNull()
        }
}

private class FakePlaceSearchService : PlaceSearchService {
    val queriesReceived = mutableListOf<String>()
    var nextResult: Result<List<PlaceResult>, PlaceSearchError> = Result.Success(emptyList())

    override suspend fun search(query: String): Result<List<PlaceResult>, PlaceSearchError> {
        queriesReceived.add(query)
        return nextResult
    }
}

private data class RouteRequest(
    val originLatitude: Double,
    val originLongitude: Double,
    val destinationLatitude: Double,
    val destinationLongitude: Double,
)

private class FakeRoutingService : RoutingService {
    var nextResult: Result<PlannedRoute, RoutingError> = Result.Error(RoutingError.NO_ROUTE_FOUND)
    var lastRequest: RouteRequest? = null

    override suspend fun route(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Result<PlannedRoute, RoutingError> {
        lastRequest = RouteRequest(originLatitude, originLongitude, destinationLatitude, destinationLongitude)
        return nextResult
    }
}

private class FakeCurrentLocationProvider : CurrentLocationProvider {
    var nextResult: Result<LocationFix, LocationError> = Result.Error(LocationError.PROVIDER_UNAVAILABLE)

    override suspend fun getCurrentLocation(): Result<LocationFix, LocationError> = nextResult
}

private fun testRideTracker(): RideTracker =
    RideTracker(
        sensorDataSource =
            object : RideSensorDataSource {
                override fun observeSamples(): Flow<RideSensorSample> = emptyFlow()

                override fun start(): EmptyResult<SensorError> = Result.Success(Unit)

                override fun stop() = Unit
            },
        metricsCalculator = RideMetricsCalculator(),
        historyRepository =
            object : RideHistoryRepository {
                override fun observeAll(): Flow<List<RideRecord>> = emptyFlow()

                override suspend fun getById(id: Long) = Result.Error(DataError.Local.NOT_FOUND)

                override suspend fun save(ride: RideRecord) = Result.Success(1L)

                override suspend fun deleteById(id: Long): EmptyResult<DataError.Local> = Result.Success(Unit)

                override suspend fun deleteAll(): EmptyResult<DataError.Local> = Result.Success(Unit)
            },
        trackPointRepository =
            object : RideTrackPointRepository {
                override suspend fun savePoints(
                    rideId: Long,
                    points: List<RideTrackPoint>,
                ): EmptyResult<DataError.Local> = Result.Success(Unit)

                override suspend fun getPoints(rideId: Long) = Result.Success(emptyList<RideTrackPoint>())
            },
        lapRepository =
            object : RideLapRepository {
                override suspend fun saveLaps(
                    rideId: Long,
                    laps: List<LapRecord>,
                ): EmptyResult<DataError.Local> = Result.Success(Unit)

                override suspend fun getLaps(rideId: Long) = Result.Success(emptyList<LapRecord>())
            },
        bleSensorDataSource =
            object : BleSensorDataSource {
                override fun observeSamples(): Flow<BleSample> = emptyFlow()

                override fun connect(
                    hrmAddress: String?,
                    cadenceAddress: String?,
                ) = Unit

                override fun disconnect() = Unit
            },
        userSettingsRepository =
            object : UserSettingsRepository {
                private val settings = MutableStateFlow(UserSettings(weightKg = 75, age = 30))

                override fun observeSettings(): Flow<UserSettings> = settings

                override suspend fun save(settings: UserSettings): EmptyResult<DataError.Local> {
                    this.settings.value = settings
                    return Result.Success(Unit)
                }
            },
    )
