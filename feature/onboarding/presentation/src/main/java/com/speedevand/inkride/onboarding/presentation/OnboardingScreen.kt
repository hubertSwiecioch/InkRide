package com.speedevand.inkride.onboarding.presentation

import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.design_system.InkRideTheme
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.core.presentation.ObserveAsEvents
import com.speedevand.inkride.onboarding.presentation.components.OnboardingPageIndicator
import com.speedevand.inkride.onboarding.presentation.components.PermissionPrimingPage
import com.speedevand.inkride.onboarding.presentation.components.ValuePropPage
import org.koin.androidx.compose.koinViewModel

@Composable
fun OnboardingRoot(
    onOnboardingComplete: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            OnboardingEvent.NavigateToDashboard -> onOnboardingComplete()
        }
    }

    OnboardingScreen(
        state = state,
        onAction = viewModel::onAction,
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onAction: (OnboardingAction) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { state.steps.size })

    LaunchedEffect(state.pageIndex) {
        if (pagerState.currentPage != state.pageIndex) {
            pagerState.animateScrollToPage(page = state.pageIndex, animationSpec = snap())
        }
    }

    val currentStep = state.steps[state.pageIndex]

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(DesignConstants.PADDING_LARGE),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            HorizontalPager(
                state = pagerState,
                // Page changes are button-driven only, keeping step order
                // and permission gating deterministic (no accidental swipe
                // past an unhandled permission step).
                userScrollEnabled = false,
                modifier = Modifier.weight(1f).testTag(OnboardingTestTags.PAGER),
            ) { pageIndex ->
                val step = state.steps[pageIndex]
                if (step.isPermissionStep) {
                    PermissionPrimingPage(
                        step = step,
                        onContinue = { onAction(OnboardingAction.OnNextClicked) },
                    )
                } else {
                    ValuePropPage(step = step)
                }
            }

            OnboardingPageIndicator(
                pageCount = state.steps.size,
                currentPage = state.pageIndex,
                modifier = Modifier.padding(vertical = DesignConstants.PADDING_MEDIUM),
            )

            if (!currentStep.isPermissionStep) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OutlinedButtonMMD(
                        onClick = { onAction(OnboardingAction.OnSkipValuePropClicked) },
                        modifier = Modifier.testTag(OnboardingTestTags.SKIP_BUTTON),
                    ) {
                        TextMMD(text = stringResource(R.string.onboarding_skip))
                    }
                    ButtonMMD(
                        onClick = { onAction(OnboardingAction.OnNextClicked) },
                        modifier = Modifier.testTag(OnboardingTestTags.NEXT_BUTTON),
                    ) {
                        TextMMD(text = stringResource(R.string.onboarding_next))
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun OnboardingScreenPreview() {
    InkRideTheme {
        OnboardingScreen(state = OnboardingState(), onAction = {})
    }
}
