package com.speedevand.inkride.core.testing.rules

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module

/**
 * Starts a Koin container for one test class and tears it down afterwards, so
 * a library module's `androidTest` can resolve `koinViewModel()` without an
 * Application class of its own.
 *
 * Pass the module under test's real `*PresentationModule` plus a module binding
 * fakes over its repositories — a later definition wins, so the fakes override
 * the production bindings.
 *
 * Declare this with `@get:Rule(order = 0)`, before the Compose rule: the
 * container must exist before the activity's composition resolves a ViewModel.
 */
class KoinTestRule(
    private val modules: List<Module>,
) : ExternalResource() {
    override fun before() {
        // Defensive: a test class that crashed mid-run can leave a container
        // standing, and startKoin would then throw for every later class.
        if (GlobalContext.getOrNull() != null) {
            stopKoin()
        }
        startKoin {
            androidContext(ApplicationProvider.getApplicationContext<Application>())
            modules(this@KoinTestRule.modules)
        }
    }

    override fun after() {
        stopKoin()
    }
}
