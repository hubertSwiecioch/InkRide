package com.speedevand.inkride.core.database

object TestEntities {
    /** A fixed wall-clock start so ordering assertions are reproducible. */
    const val START_MS = 1_750_000_000_000L

    fun ride(
        id: Long = 0L,
        startTimestamp: Long = START_MS,
        endTimestamp: Long = START_MS + 3_600_000L,
        distanceKm: Double = 25.0,
        movingTimeSeconds: Long = 3_000L,
        elapsedTimeSeconds: Long = 3_600L,
        averageSpeedKmh: Double = 30.0,
        maxSpeedKmh: Double = 45.5,
        elevationGainM: Double = 320.0,
        caloriesKcal: Double = 780.0,
    ): RideHistoryEntity =
        RideHistoryEntity(
            id = id,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            distanceKm = distanceKm,
            movingTimeSeconds = movingTimeSeconds,
            elapsedTimeSeconds = elapsedTimeSeconds,
            averageSpeedKmh = averageSpeedKmh,
            maxSpeedKmh = maxSpeedKmh,
            elevationGainM = elevationGainM,
            caloriesKcal = caloriesKcal,
            averagePowerWatts = 165,
            bikeWeightKg = 9.0,
            bikeType = "ROAD",
        )

    fun lap(
        rideId: Long,
        lapNumber: Int,
        distanceKm: Double = 5.0,
    ): RideLapEntity =
        RideLapEntity(
            rideId = rideId,
            lapNumber = lapNumber,
            distanceKm = distanceKm,
            movingTimeSeconds = 600L,
            averageSpeedKmh = 30.0,
            elevationGainM = 60.0,
        )

    fun trackPoint(
        rideId: Long,
        timestampMs: Long,
        latitude: Double = 52.2297,
        longitude: Double = 21.0122,
    ): RideTrackPointEntity =
        RideTrackPointEntity(
            rideId = rideId,
            timestampMs = timestampMs,
            latitude = latitude,
            longitude = longitude,
            altitudeM = 100.0,
            accuracyM = 5f,
        )

    fun bikeProfile(
        id: Long = 0L,
        name: String = "Road bike",
        weightKg: Double = 8.5,
        type: String = "ROAD",
    ): BikeProfileEntity = BikeProfileEntity(id = id, name = name, weightKg = weightKg, type = type)

    fun userSettings(
        weightKg: Int = 75,
        age: Int = 30,
    ): UserSettingsEntity =
        UserSettingsEntity(
            weightKg = weightKg,
            age = age,
            bikeWeightKg = 10.0,
            bikeType = "ROAD",
            languageCode = "en",
            units = "METRIC",
            showDistance = true,
            showMovingTime = true,
            showAverageSpeed = true,
            showMaxSpeed = true,
            showElevationGain = true,
            showCalories = true,
            showAltitude = true,
            showGrade = true,
            showCompass = true,
            showPower = true,
        )
}
