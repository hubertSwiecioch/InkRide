package com.speedevand.inkride.dashboard.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.PlaceResult
import com.speedevand.inkride.core.domain.tracking.PlaceSearchError
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.core.domain.tracking.RoutingService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class DestinationSearchViewModel(
    private val placeSearchService: PlaceSearchService,
    private val routingService: RoutingService,
    private val currentLocationProvider: CurrentLocationProvider,
    private val rideTracker: RideTracker,
) : ViewModel() {
    private val _state = MutableStateFlow(DestinationSearchState())
    val state = _state.asStateFlow()

    private val _events = Channel<DestinationSearchEvent>()
    val events = _events.receiveAsFlow()

    private val queryFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            queryFlow
                .distinctUntilChanged()
                .debounce(SEARCH_DEBOUNCE_MS)
                .filter { it.length >= MIN_QUERY_LENGTH }
                .collectLatest { query -> performSearch(query) }
        }
    }

    fun onAction(action: DestinationSearchAction) {
        when (action) {
            is DestinationSearchAction.OnQueryChanged -> onQueryChanged(action.query)
            is DestinationSearchAction.OnResultSelected -> onResultSelected(action.result)
        }
    }

    private fun onQueryChanged(query: String) {
        _state.update { current ->
            current.copy(query = query, results = if (query.length < MIN_QUERY_LENGTH) emptyList() else current.results)
        }
        queryFlow.tryEmit(query)
    }

    private suspend fun performSearch(query: String) {
        _state.update { it.copy(isSearching = true) }
        when (val result = placeSearchService.search(query)) {
            is Result.Success -> {
                _state.update { it.copy(isSearching = false, results = result.data) }
            }

            is Result.Error -> {
                _state.update { it.copy(isSearching = false, results = emptyList()) }
                if (result.error == PlaceSearchError.NETWORK_FAILED) {
                    _events.send(DestinationSearchEvent.ShowError(result.error.toUiText()))
                }
            }
        }
    }

    private fun onResultSelected(result: PlaceResult) {
        if (_state.value.isRouting) return
        viewModelScope.launch {
            _state.update { it.copy(isRouting = true) }

            val location =
                when (val locationResult = currentLocationProvider.getCurrentLocation()) {
                    is Result.Success -> {
                        locationResult.data
                    }

                    is Result.Error -> {
                        _state.update { it.copy(isRouting = false) }
                        _events.send(DestinationSearchEvent.ShowError(locationResult.error.toUiText()))
                        return@launch
                    }
                }

            val routeResult =
                routingService.route(
                    originLatitude = location.latitude,
                    originLongitude = location.longitude,
                    destinationLatitude = result.latitude,
                    destinationLongitude = result.longitude,
                )

            _state.update { it.copy(isRouting = false) }

            when (routeResult) {
                is Result.Success -> {
                    rideTracker.loadRoute(routeResult.data.copy(name = result.displayName))
                    _events.send(DestinationSearchEvent.NavigateBackToDashboard)
                }

                is Result.Error -> {
                    _events.send(DestinationSearchEvent.ShowError(routeResult.error.toUiText()))
                }
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 600L
        const val MIN_QUERY_LENGTH = 3
    }
}
