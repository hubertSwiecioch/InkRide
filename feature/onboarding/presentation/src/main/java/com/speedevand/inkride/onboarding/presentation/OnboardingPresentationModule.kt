package com.speedevand.inkride.onboarding.presentation

import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val onboardingPresentationModule =
    module {
        viewModelOf(::OnboardingViewModel)
    }
