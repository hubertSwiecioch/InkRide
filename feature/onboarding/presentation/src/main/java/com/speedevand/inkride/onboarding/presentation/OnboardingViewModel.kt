package com.speedevand.inkride.onboarding.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val userSettingsRepository: UserSettingsRepository,
) : ViewModel() {
    private val steps = buildOnboardingSteps()

    private val _state =
        MutableStateFlow(
            OnboardingState(steps = steps, pageIndex = savedStateHandle[PAGE_INDEX_KEY] ?: 0),
        )
    val state = _state.asStateFlow()

    private val _events = Channel<OnboardingEvent>()
    val events = _events.receiveAsFlow()

    fun onAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.OnNextClicked -> advance(_state.value.pageIndex + 1)
            OnboardingAction.OnSkipValuePropClicked -> advance(steps.indexOfFirst { it.isPermissionStep })
        }
    }

    private fun advance(nextIndex: Int) {
        if (nextIndex >= steps.size) {
            completeOnboarding()
        } else {
            savedStateHandle[PAGE_INDEX_KEY] = nextIndex
            _state.update { it.copy(pageIndex = nextIndex) }
        }
    }

    // Failure here just means onboarding may show again next launch -- not
    // data loss -- so the write is fire-and-forget rather than surfaced as
    // an error to the user.
    private fun completeOnboarding() {
        viewModelScope.launch {
            val current = userSettingsRepository.observeSettings().first()
            userSettingsRepository.save(current.copy(hasCompletedOnboarding = true))
            _events.send(OnboardingEvent.NavigateToDashboard)
        }
    }

    private companion object {
        const val PAGE_INDEX_KEY = "onboarding_page_index"
    }
}
