package com.speedevand.inkride.ui.theme

import androidx.compose.runtime.Composable
import com.mudita.mmd.ThemeMMD

@Composable
fun InkRideTheme(content: @Composable () -> Unit) {
    ThemeMMD(content = content)
}