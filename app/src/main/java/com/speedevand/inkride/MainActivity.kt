package com.speedevand.inkride

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.speedevand.inkride.dashboard.presentation.CoreDashboardRoot
import com.speedevand.inkride.ui.theme.InkRideTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InkRideTheme {
                CoreDashboardRoot()
            }
        }
    }
}
