package com.speedevand.inkride.dashboard.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.LocationFix
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidCurrentLocationProvider(
    private val context: Context,
    private val timeoutMs: Long = 15_000L,
) : CurrentLocationProvider {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): Result<LocationFix, LocationError> {
        if (!hasLocationPermission()) {
            return Result.Error(LocationError.PERMISSION_DENIED)
        }
        if (locationManager.getProvider(LocationManager.GPS_PROVIDER) == null) {
            return Result.Error(LocationError.PROVIDER_UNAVAILABLE)
        }

        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            lateinit var listener: LocationListener
            val timeoutRunnable =
                Runnable {
                    locationManager.removeUpdates(listener)
                    if (continuation.isActive) {
                        continuation.resumeWith(kotlin.Result.success(Result.Error(LocationError.TIMED_OUT)))
                    }
                }
            listener =
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        handler.removeCallbacks(timeoutRunnable)
                        locationManager.removeUpdates(this)
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                kotlin.Result.success(Result.Success(LocationFix(location.latitude, location.longitude))),
                            )
                        }
                    }
                }
            continuation.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                locationManager.removeUpdates(listener)
            }
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            handler.postDelayed(timeoutRunnable, timeoutMs)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
