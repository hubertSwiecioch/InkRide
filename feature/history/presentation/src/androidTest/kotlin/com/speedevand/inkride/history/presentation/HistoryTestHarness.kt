package com.speedevand.inkride.history.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.speedevand.inkride.core.domain.history.LifetimeStatsRepository
import com.speedevand.inkride.core.domain.history.RideHistoryRepository
import com.speedevand.inkride.core.domain.history.RideLapRepository
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.domain.history.RideSampleRepository
import com.speedevand.inkride.core.domain.history.RideTrackPointRepository
import com.speedevand.inkride.core.domain.settings.UserSettings
import com.speedevand.inkride.core.domain.settings.UserSettingsRepository
import com.speedevand.inkride.core.testing.fakes.FakeLifetimeStatsRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideHistoryRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideLapRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideSampleRepository
import com.speedevand.inkride.core.testing.fakes.FakeRideTrackPointRepository
import com.speedevand.inkride.core.testing.fakes.FakeUserSettingsRepository
import com.speedevand.inkride.core.testing.rules.KoinTestRule
import com.speedevand.inkride.core.testing.support.TestSettings
import org.junit.Rule
import org.koin.dsl.module

/**
 * Shared setup for the history instrumented tests: the real
 * `historyPresentationModule` with fakes bound over its five collaborators.
 * The real module also binds a `GpxExporter` built from `androidContext()`, so
 * the fake must be registered after it to win.
 */
abstract class HistoryTestHarness {
    protected open fun initialSettings(): UserSettings = TestSettings.default()

    protected open fun initialRides(): List<RideRecord> = emptyList()

    val settingsRepository by lazy { FakeUserSettingsRepository(initialSettings()) }
    val lapRepository by lazy { FakeRideLapRepository() }
    val trackPointRepository by lazy { FakeRideTrackPointRepository() }
    val sampleRepository by lazy { FakeRideSampleRepository() }

    // The lap and track-point fakes are handed to the history fake so that
    // deleting a ride clears them too, reproducing the `onDelete = CASCADE` that
    // RideLapEntity and RideTrackPointEntity declare. Without this wiring the
    // undo test below passes even when the undo path restores nothing — the laps
    // it asserts on were never removed in the first place.
    val historyRepository by lazy {
        FakeRideHistoryRepository(
            initial = initialRides(),
            lapRepository = lapRepository,
            trackPointRepository = trackPointRepository,
            sampleRepository = sampleRepository,
        )
    }
    val lifetimeStatsRepository by lazy { FakeLifetimeStatsRepository() }
    val gpxExporter by lazy { FakeGpxExporter() }

    @get:Rule(order = 1)
    val koinRule by lazy {
        KoinTestRule(
            modules =
                listOf(
                    historyPresentationModule,
                    module {
                        single<UserSettingsRepository> { settingsRepository }
                        single<RideHistoryRepository> { historyRepository }
                        single<RideLapRepository> { lapRepository }
                        single<RideTrackPointRepository> { trackPointRepository }
                        single<RideSampleRepository> { sampleRepository }
                        single<LifetimeStatsRepository> { lifetimeStatsRepository }
                        single<GpxExporter> { gpxExporter }
                    },
                ),
        )
    }

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()
}
