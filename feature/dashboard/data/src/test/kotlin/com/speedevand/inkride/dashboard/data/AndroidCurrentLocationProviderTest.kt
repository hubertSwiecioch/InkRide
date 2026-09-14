package com.speedevand.inkride.dashboard.data

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.tracking.LocationError
import com.speedevand.inkride.core.domain.tracking.LocationFix
import com.speedevand.inkride.core.domain.tracking.RideTracker
import com.speedevand.inkride.core.domain.tracking.TrackingState
import com.speedevand.inkride.core.domain.tracking.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidCurrentLocationProviderTest {
    private val context = ApplicationProvider.getApplicationContext<ContextWrapper>()
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Defaults to an idle ride (no active-ride position to prefer) unless a
    // test overrides it -- so tests that only exercise the last-known-fix or
    // fresh-GPS paths don't need to think about RideTracker at all.
    private val rideTrackerState = MutableStateFlow(TrackingState())
    private val rideTracker =
        mock<RideTracker>().also {
            whenever(it.state).thenReturn(rideTrackerState)
        }

    @Test
    fun `returns PERMISSION_DENIED when location permission is not granted`() =
        runTest {
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            val provider = AndroidCurrentLocationProvider(context, rideTracker)

            val result = provider.getCurrentLocation()

            assertThat(result).isEqualTo(Result.Error(LocationError.PERMISSION_DENIED))
        }

    @Test
    fun `returns PROVIDER_UNAVAILABLE when the GPS provider is absent`() =
        runTest {
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            shadowOf(locationManager).removeProvider(LocationManager.GPS_PROVIDER)
            val provider = AndroidCurrentLocationProvider(context, rideTracker)

            val result = provider.getCurrentLocation()

            assertThat(result).isEqualTo(Result.Error(LocationError.PROVIDER_UNAVAILABLE))
        }

    @Test
    fun `returns the fix from a simulated GPS location`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val provider = AndroidCurrentLocationProvider(context, rideTracker)

        var result: Result<LocationFix, LocationError>? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        scope.launch { result = provider.getCurrentLocation() }

        val fix =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 52.0
                longitude = 21.0
            }
        shadowOf(locationManager).simulateLocation(fix)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(result).isEqualTo(Result.Success(LocationFix(52.0, 21.0)))
        scope.cancel()
    }

    @Test
    fun `returns TIMED_OUT when no fix arrives before the timeout`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val provider = AndroidCurrentLocationProvider(context, rideTracker, timeoutMs = 1_000L)

        var result: Result<LocationFix, LocationError>? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        scope.launch { result = provider.getCurrentLocation() }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000L))

        assertThat(result).isEqualTo(Result.Error(LocationError.TIMED_OUT))
        scope.cancel()
    }

    @Test
    fun `a fresh last-known GPS fix is returned immediately without requesting a new one`() =
        runTest {
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            val lastKnown =
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 52.5
                    longitude = 21.5
                    time = System.currentTimeMillis() - 30_000L // 30s old, well within the 2min bound.
                }
            shadowOf(locationManager).setLastKnownLocation(LocationManager.GPS_PROVIDER, lastKnown)
            // If the fast path failed to short-circuit, this would time out the
            // test instead of returning -- no fresh fix is ever simulated.
            val provider = AndroidCurrentLocationProvider(context, rideTracker, timeoutMs = 1_000L)

            val result = provider.getCurrentLocation()

            assertThat(result).isEqualTo(Result.Success(LocationFix(52.5, 21.5)))
        }

    @Test
    fun `a stale last-known fix is ignored and falls through to a fresh GPS request`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val stale =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 52.5
                longitude = 21.5
                time = System.currentTimeMillis() - 5 * 60 * 1000L // 5 minutes old, outside the 2min bound.
            }
        shadowOf(locationManager).setLastKnownLocation(LocationManager.GPS_PROVIDER, stale)
        val provider = AndroidCurrentLocationProvider(context, rideTracker)

        var result: Result<LocationFix, LocationError>? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        scope.launch { result = provider.getCurrentLocation() }

        // Only a fresh fix -- distinct from the stale cached one -- satisfies
        // the request; if the stale fix had short-circuited instead, this
        // simulated location would never be observed.
        val fresh =
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 10.0
                longitude = 10.0
            }
        shadowOf(locationManager).simulateLocation(fresh)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(result).isEqualTo(Result.Success(LocationFix(10.0, 10.0)))
        scope.cancel()
    }

    @Test
    fun `an active ride's current position is preferred over a fresh GPS request`() =
        runTest {
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            rideTrackerState.value =
                TrackingState(status = TrackingStatus.TRACKING, currentPosition = LocationFix(48.0, 17.0))
            // No last-known fix is set, and no fresh fix is ever simulated -- the
            // active-ride position must be what's returned, and immediately.
            val provider = AndroidCurrentLocationProvider(context, rideTracker, timeoutMs = 1_000L)

            val result = provider.getCurrentLocation()

            assertThat(result).isEqualTo(Result.Success(LocationFix(48.0, 17.0)))
        }

    @Test
    fun `a paused ride's current position is still preferred over a fresh GPS request`() =
        runTest {
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            rideTrackerState.value =
                TrackingState(status = TrackingStatus.PAUSED, currentPosition = LocationFix(48.0, 17.0))
            val provider = AndroidCurrentLocationProvider(context, rideTracker, timeoutMs = 1_000L)

            val result = provider.getCurrentLocation()

            assertThat(result).isEqualTo(Result.Success(LocationFix(48.0, 17.0)))
        }
}
