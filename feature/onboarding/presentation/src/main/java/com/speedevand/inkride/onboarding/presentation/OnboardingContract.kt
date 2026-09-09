package com.speedevand.inkride.onboarding.presentation

data class OnboardingState(
    val steps: List<OnboardingStep> = buildOnboardingSteps(),
    val pageIndex: Int = 0,
)

sealed interface OnboardingAction {
    data object OnNextClicked : OnboardingAction

    data object OnSkipValuePropClicked : OnboardingAction
}

sealed interface OnboardingEvent {
    data object NavigateToDashboard : OnboardingEvent
}
