package com.speedevand.inkride.tracking.data

import com.speedevand.inkride.core.domain.tracking.CaloriesEstimator
import com.speedevand.inkride.core.domain.tracking.RideMetricsCalculator
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.core.domain.tracking.training.ThresholdDetector
import com.speedevand.inkride.core.domain.tracking.training.TrainingLoadCalculator
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val trackingDataModule =
    module {
        single<RideSensorDataSource> { AndroidRideSensorDataSource(get()) }
        singleOf(::CaloriesEstimator)
        single { RideMetricsCalculator(get()) }
        singleOf(::TrainingLoadCalculator)
        singleOf(::ThresholdDetector)
        // (sensorDataSource, metricsCalculator, historyRepository, trackPointRepository,
        //  sampleRepository, lapRepository, bleSensorDataSource, userSettingsRepository,
        //  routeFollower, heartRateFilter, trainingLoadCalculator, thresholdDetector)
        single {
            RideTracker(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                trainingLoadCalculator = get(),
                thresholdDetector = get(),
            )
        }
    }
