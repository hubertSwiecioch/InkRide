package com.speedevand.inkride.dashboard.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.dashboard.data.RideSensorDataSource
import com.speedevand.inkride.dashboard.domain.RideMetricsCalculator
import com.speedevand.inkride.dashboard.model.RideMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CoreDashboardViewModel(
    private val rideSensorDataSource: RideSensorDataSource,
    private val rideMetricsCalculator: RideMetricsCalculator = RideMetricsCalculator()
) : ViewModel() {
    private val _state = MutableStateFlow(CoreDashboardState())
    val state = _state.asStateFlow()

    init {
        observeSensorSamples()
    }

    fun onAction(action: CoreDashboardAction) {
        when (action) {
            CoreDashboardAction.OnResetClick -> resetSession()
            CoreDashboardAction.OnStartStopClick -> toggleTracking()
            is CoreDashboardAction.OnUserProfileChanged -> {
                _state.update { it.copy(userProfile = action.profile) }
            }
        }
    }

    private fun observeSensorSamples() {
        viewModelScope.launch {
            rideSensorDataSource.observeSamples().collect { sample ->
                val currentState = _state.value
                if (!currentState.isTracking) return@collect

                val metrics = rideMetricsCalculator.process(
                    sample = sample,
                    userProfile = currentState.userProfile
                )
                _state.update { it.copy(rideMetrics = metrics) }
            }
        }
    }

    private fun toggleTracking() {
        val nextTracking = !_state.value.isTracking
        _state.update { it.copy(isTracking = nextTracking) }

        if (nextTracking) {
            rideSensorDataSource.start()
        } else {
            rideSensorDataSource.stop()
        }
    }

    private fun resetSession() {
        rideMetricsCalculator.reset()
        _state.update {
            it.copy(
                rideMetrics = RideMetrics(),
                isTracking = false
            )
        }
        rideSensorDataSource.stop()
    }

    override fun onCleared() {
        rideSensorDataSource.stop()
        super.onCleared()
    }
}


