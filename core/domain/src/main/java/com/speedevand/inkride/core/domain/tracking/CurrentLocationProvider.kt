package com.speedevand.inkride.core.domain.tracking

import com.speedevand.inkride.core.domain.Error
import com.speedevand.inkride.core.domain.Result

/**
 * One-shot "where am I right now" lookup. Deliberately independent of
 * [RideTracker]'s sample pipeline, which only runs while a ride is active
 * (`status != IDLE`) — see the destination-search design doc's "Current-
 * position gap" section for why that pipeline can't be reused here.
 */
interface CurrentLocationProvider {
    suspend fun getCurrentLocation(): Result<LocationFix, LocationError>
}

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
)

enum class LocationError : Error {
    PERMISSION_DENIED,
    TIMED_OUT,
    PROVIDER_UNAVAILABLE,
}
