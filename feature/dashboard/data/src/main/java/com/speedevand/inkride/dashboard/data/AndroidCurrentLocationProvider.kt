package com.speedevand.inkride.dashboard.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.CurrentLocationProvider
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.LocationFix
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.core.domain.tracking.TrackingStatus
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * "Where am I right now" for destination search, which — unlike [RideTracker]'s
 * own sample pipeline — has to work *before* a ride starts: cold GPS chip,
 * indoors, in a garage. A blind 15s GPS-only request fails in exactly that
 * common case, so fixes are tried cheapest-and-most-likely-to-succeed first:
 *
 * 1. [lastKnownFreshFix] — a recent cached fix from the OS (GPS or network
 *    provider) is near-instant and good enough to seed a route origin.
 * 2. [activeRidePosition] — if a ride is already TRACKING/PAUSED/AUTO_PAUSED,
 *    [RideTracker] already has a live, Kalman-filtered position; reuse it
 *    instead of starting a second, redundant GPS request.
 * 3. [requestFreshFix] — an actual single-fix GPS request, only reached when
 *    neither shortcut above has anything to offer. API 30+ goes through
 *    `getCurrentLocation()`; older devices keep the deprecated
 *    `requestSingleUpdate` path. Both are held to [timeoutMs].
 */
class AndroidCurrentLocationProvider(
    private val context: Context,
    private val rideTracker: RideTracker,
    private val timeoutMs: Long = 15_000L,
    // How old a cached last-known fix is allowed to be and still be trusted
    // outright, skipping a fresh GPS request entirely.
    private val lastKnownMaxAgeMs: Long = 2 * 60 * 1000L,
) : CurrentLocationProvider {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun getCurrentLocation(): Result<LocationFix, LocationError> {
        if (!hasLocationPermission()) {
            return Result.Error(LocationError.PERMISSION_DENIED)
        }

        lastKnownFreshFix()?.let { return Result.Success(it) }

        activeRidePosition()?.let { return Result.Success(it) }

        if (locationManager.getProvider(LocationManager.GPS_PROVIDER) == null) {
            return Result.Error(LocationError.PROVIDER_UNAVAILABLE)
        }

        return requestFreshFix()
    }

    /**
     * The freshest of GPS/network last-known locations, if any is within
     * [lastKnownMaxAgeMs]. `NETWORK_PROVIDER` may not exist on a de-googled
     * device (no GMS location services), so it's queried defensively.
     */
    @SuppressLint("MissingPermission")
    private fun lastKnownFreshFix(): LocationFix? {
        val now = System.currentTimeMillis()
        return listOfNotNull(
            runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
        ).filter { now - it.time <= lastKnownMaxAgeMs }
            .maxByOrNull { it.time }
            ?.let { LocationFix(it.latitude, it.longitude) }
    }

    /** [RideTracker]'s current position, only while a ride is actively being tracked. */
    private fun activeRidePosition(): LocationFix? {
        val state = rideTracker.state.value
        return if (state.status != TrackingStatus.IDLE) state.currentPosition else null
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshFix(): Result<LocationFix, LocationError> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestFreshFixModern()
        } else {
            requestFreshFixLegacy()
        }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
    private suspend fun requestFreshFixModern(): Result<LocationFix, LocationError> =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            val handler = Handler(Looper.getMainLooper())

            // getCurrentLocation() enforces a timeout of its own (30 s by
            // default), which is twice the budget this class documents. Without
            // an explicit one the rider waits half a minute on a cold chip
            // before destination search admits it has nothing — so the same
            // [timeoutMs] the legacy path honours is applied here too, by
            // cancelling the request rather than racing it.
            val timeoutRunnable =
                Runnable {
                    cancellationSignal.cancel()
                    if (continuation.isActive) {
                        continuation.resumeWith(kotlin.Result.success(Result.Error(LocationError.TIMED_OUT)))
                    }
                }
            continuation.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                cancellationSignal.cancel()
            }

            locationManager.getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                cancellationSignal,
                ContextCompat.getMainExecutor(context),
            ) { location ->
                handler.removeCallbacks(timeoutRunnable)
                if (!continuation.isActive) return@getCurrentLocation
                val result =
                    if (location == null) {
                        // The platform resolves the consumer with null on timeout
                        // or when the provider can't produce a fix.
                        Result.Error(LocationError.TIMED_OUT)
                    } else {
                        Result.Success(LocationFix(location.latitude, location.longitude))
                    }
                continuation.resumeWith(kotlin.Result.success(result))
            }
            handler.postDelayed(timeoutRunnable, timeoutMs)
        }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshFixLegacy(): Result<LocationFix, LocationError> =
        suspendCancellableCoroutine { continuation ->
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

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
