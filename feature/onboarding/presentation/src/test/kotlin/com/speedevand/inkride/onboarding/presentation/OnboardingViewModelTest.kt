package com.speedevand.inkride.onboarding.presentation

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.settings.UserSettings
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
class OnboardingViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository = FakeUserSettingsRepository()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = OnboardingViewModel(SavedStateHandle(), repository)

    @Test
    fun `initial state starts on the first value-prop page`() {
        val state = viewModel().state.value
        assertThat(state.pageIndex).isEqualTo(0)
        assertThat(state.steps.first()).isEqualTo(OnboardingStep.VALUE_PROP_EINK)
    }

    @Test
    fun `next click advances one page at a time`() {
        val vm = viewModel()
        vm.onAction(OnboardingAction.OnNextClicked)
        assertThat(vm.state.value.pageIndex).isEqualTo(1)
    }

    @Test
    fun `skip value prop jumps straight to the first permission step`() {
        val vm = viewModel()
        vm.onAction(OnboardingAction.OnSkipValuePropClicked)
        val state = vm.state.value
        assertThat(state.steps[state.pageIndex]).isEqualTo(OnboardingStep.LOCATION_PERMISSION)
    }

    @Test
    fun `advancing past the last step marks onboarding complete and navigates to dashboard`() =
        runTest {
            val vm = viewModel()
            val lastIndex = vm.state.value.steps.lastIndex

            vm.events.test {
                repeat(lastIndex + 1) { vm.onAction(OnboardingAction.OnNextClicked) }
                val event = awaitItem()
                assertThat(event).isEqualTo(OnboardingEvent.NavigateToDashboard)
            }
            assertThat(repository.lastSaved?.hasCompletedOnboarding).isEqualTo(true)
        }

    @Test
    fun `completing onboarding preserves previously saved settings`() =
        runTest {
            repository.emitSettings(UserSettings(weightKg = 82, age = 40))
            val vm = viewModel()
            val lastIndex = vm.state.value.steps.lastIndex

            vm.events.test {
                repeat(lastIndex + 1) { vm.onAction(OnboardingAction.OnNextClicked) }
                awaitItem()
            }

            assertThat(repository.lastSaved?.weightKg).isEqualTo(82)
            assertThat(repository.lastSaved?.hasCompletedOnboarding).isEqualTo(true)
        }
}
