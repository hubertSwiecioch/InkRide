package com.speedevand.inkride.convention

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("inkride.android.library")
                apply("inkride.compose")
            }

            dependencies {
                add("implementation", project(":core:domain"))
                add("implementation", project(":core:presentation"))
                add("implementation", project(":core:design-system"))

                add("implementation", libs.findLibrary("koin-android").get())
                add("implementation", libs.findLibrary("koin-androidx-compose").get())

                // Shared instrumented-test stack. Declared once here rather
                // than in each of the five presentation modules. `:core:testing`
                // is test-only, so none of this reaches a production APK.
                add("androidTestImplementation", project(":core:testing"))
                add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
                add("androidTestImplementation", libs.findLibrary("androidx-junit").get())
                add("androidTestImplementation", libs.findLibrary("androidx-test-core").get())
                add("androidTestImplementation", libs.findLibrary("androidx-test-rules").get())
                add("androidTestImplementation", libs.findLibrary("assertk").get())
                add("androidTestImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
                // A library module has no Activity of its own; this supplies the
                // ComponentActivity that createAndroidComposeRule launches.
                add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
            }
        }
    }
}
