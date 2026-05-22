package com.speedevand.inkride.dashboard.data

import com.speedevand.inkride.dashboard.model.RideSensorSample
import kotlinx.coroutines.flow.Flow

interface RideSensorDataSource {
    fun observeSamples(): Flow<RideSensorSample>
    fun start()
    fun stop()
}

