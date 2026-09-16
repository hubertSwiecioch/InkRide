package com.speedevand.inkride

import android.app.Application
import com.speedevand.inkride.ble.data.bleDataModule
import com.speedevand.inkride.ble.presentation.blePresentationModule
import com.speedevand.inkride.core.database.databaseModule
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.dashboard.data.dashboardDataModule
import com.speedevand.inkride.dashboard.presentation.dashboardPresentationModule
import com.speedevand.inkride.history.data.historyDataModule
import com.speedevand.inkride.history.presentation.historyPresentationModule
import com.speedevand.inkride.onboarding.presentation.onboardingPresentationModule
import com.speedevand.inkride.settings.data.settingsDataModule
import com.speedevand.inkride.settings.presentation.settingsPresentationModule
import com.speedevand.inkride.tracking.data.trackingDataModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class InkRideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@InkRideApp)
            modules(
                databaseModule,
                trackingDataModule,
                dashboardDataModule,
                historyDataModule,
                settingsDataModule,
                settingsPresentationModule,
                dashboardPresentationModule,
                onboardingPresentationModule,
                historyPresentationModule,
                bleDataModule,
                blePresentationModule,
            )
        }.koin
            .get<RideTracker>()
            // Close any ride a previous process left open. Fire-and-forget: it
            // runs on the tracker's own scope and must not delay startup.
            .recoverUnfinishedRides()
    }
}
