plugins {
    id("inkride.android.library")
}

android {
    namespace = "com.speedevand.inkride.ble.data"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.assertk)
    testRuntimeOnly(libs.junit.vintage.engine)
}
