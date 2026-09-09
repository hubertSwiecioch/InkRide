package com.speedevand.inkride.dashboard.data

import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.PlaceSearchService
import com.speedevand.inkride.core.domain.tracking.RoutingService
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dashboardDataModule =
    module {
        single<PlaceSearchService> { NominatimPlaceSearchService(userAgent = androidContext().packageName) }
        single<RoutingService> { OsrmRoutingService() }
        single<CurrentLocationProvider> { AndroidCurrentLocationProvider(androidContext(), get()) }
    }
