package com.speedevand.inkride.dashboard.model

data class RideSensorSample(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeFromGpsM: Double?,
    val altitudeFromBarometerM: Double?,
    val speedFromGpsMps: Double?,
    val accuracyM: Float?
)

