plugins {
    id("inkride.android.library")
    id("inkride.compose")
}

android {
    namespace = "com.speedevand.inkride.core.testing"
}

// This module is only ever consumed from test source sets
// (androidTestImplementation / testImplementation), so nothing here reaches a
// production APK. Dependencies are `api` rather than `implementation` so a
// consumer gets the Compose/JUnit types that appear in these helpers'
// signatures without re-declaring them.
dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.coroutines.core)
    api(libs.junit)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.androidx.test.core)
    api(libs.koin.android)
    api(libs.koin.test)
}
