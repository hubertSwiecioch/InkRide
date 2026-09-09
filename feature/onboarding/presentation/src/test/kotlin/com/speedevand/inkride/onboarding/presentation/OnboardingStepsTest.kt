package com.speedevand.inkride.onboarding.presentation

import android.os.Build
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import org.junit.jupiter.api.Test

class OnboardingStepsTest {
    @Test
    fun `steps include notification permission on API 33 and above`() {
        val steps = buildOnboardingSteps(sdkInt = Build.VERSION_CODES.TIRAMISU)
        assertThat(steps).contains(OnboardingStep.NOTIFICATION_PERMISSION)
    }

    @Test
    fun `steps exclude notification permission below API 33`() {
        val steps = buildOnboardingSteps(sdkInt = Build.VERSION_CODES.S)
        assertThat(steps).doesNotContain(OnboardingStep.NOTIFICATION_PERMISSION)
    }

    @Test
    fun `steps always start with the three value-prop pages then location permission`() {
        val steps = buildOnboardingSteps(sdkInt = Build.VERSION_CODES.S)
        assertThat(steps).containsExactly(
            OnboardingStep.VALUE_PROP_EINK,
            OnboardingStep.VALUE_PROP_PRIVACY,
            OnboardingStep.VALUE_PROP_TRACKING,
            OnboardingStep.LOCATION_PERMISSION,
        )
    }
}
