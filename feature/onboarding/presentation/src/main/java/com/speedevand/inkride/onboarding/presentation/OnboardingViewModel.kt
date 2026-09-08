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

    // Guards against advance() being re-entered (e.g. a fast double-tap on
    // "Skip for now") after completion has already started but before the UI
    // has navigated away -- without this, each re-entry would launch another
    // completeOnboarding() coroutine, another save(), and another
    // NavigateToDashboard send. Never reset back to false: once onboarding
    // completes the screen navigates away and this ViewModel is torn down.
    private var isCompleting = false

    fun onAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.OnNextClicked -> advance(_state.value.pageIndex + 1)
            OnboardingAction.OnSkipValuePropClicked -> advance(steps.indexOfFirst { it.isPermissionStep })
        }
    }

    private fun advance(nextIndex: Int) {
        if (nextIndex >= steps.size) {
            if (!isCompleting) {
                completeOnboarding()
            }
        } else {
            savedStateHandle[PAGE_INDEX_KEY] = nextIndex
            _state.update { it.copy(pageIndex = nextIndex) }
        }
    }

    // Failure here just means onboarding may show again next launch -- not
    // data loss -- so the write is fire-and-forget rather than surfaced as
    // an error to the user.
    private fun completeOnboarding() {
        isCompleting = true
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
