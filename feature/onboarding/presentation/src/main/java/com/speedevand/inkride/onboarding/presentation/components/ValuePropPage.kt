package com.speedevand.inkride.onboarding.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.presentation.DesignConstants
import com.speedevand.inkride.onboarding.presentation.OnboardingStep
import com.speedevand.inkride.onboarding.presentation.OnboardingTestTags

@Composable
fun ValuePropPage(step: OnboardingStep) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = step.icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
        )
        TextMMD(
            text = stringResource(step.titleRes),
            modifier =
                Modifier
                    .padding(top = DesignConstants.PADDING_LARGE)
                    .testTag(OnboardingTestTags.PAGE_TITLE),
        )
        TextMMD(
            text = stringResource(step.bodyRes),
            modifier = Modifier.padding(top = DesignConstants.PADDING_MEDIUM),
        )
    }
}
