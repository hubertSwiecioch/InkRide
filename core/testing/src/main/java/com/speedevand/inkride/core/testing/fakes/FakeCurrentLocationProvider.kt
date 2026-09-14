package com.speedevand.inkride.core.testing.fakes

import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.LocationFix

class FakeCurrentLocationProvider : CurrentLocationProvider {
    var result: Result<LocationFix, LocationError> =
        Result.Success(LocationFix(latitude = 52.2297, longitude = 21.0122))

    override suspend fun getCurrentLocation(): Result<LocationFix, LocationError> = result
}
