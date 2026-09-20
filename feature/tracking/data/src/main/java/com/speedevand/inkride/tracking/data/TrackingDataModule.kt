package com.speedevand.inkride.tracking.data

import com.speedevand.inkride.core.domain.tracking.CaloriesEstimator
import com.speedevand.inkride.core.domain.tracking.RideMetricsCalculator
import com.speedevand.inkride.core.domain.tracking.RideSensorDataSource
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.core.domain.tracking.training.DecouplingCalculator
import com.speedevand.inkride.core.domain.tracking.training.ThresholdDetector
import com.speedevand.inkride.core.domain.tracking.training.TrainingLoadCalculator
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val trackingDataModule =
    module {
        single<RideSensorDataSource> { AndroidRideSensorDataSource(get()) }
        singleOf(::CaloriesEstimator)
        single { RideMetricsCalculator(get()) }
        // Constructed explicitly, not with singleOf: every parameter these
        // three take is a tuning constant with a default (window lengths,
        // factors, collaborators). singleOf would try to resolve each one from
        // the graph and fail at app start rather than use the default.
        single { TrainingLoadCalculator() }
        single { ThresholdDetector() }
        single { DecouplingCalculator() }
        // (sensorDataSource, metricsCalculator, historyRepository, trackPointRepository,
        //  sampleRepository, lapRepository, bleSensorDataSource, userSettingsRepository,
        //  routeFollower, heartRateFilter, trainingLoadCalculator, thresholdDetector,
        //  decouplingCalculator)
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
                decouplingCalculator = get(),
            )
        }
    }
