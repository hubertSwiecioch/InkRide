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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidCurrentLocationProviderTest {
    private val context = ApplicationProvider.getApplicationContext<ContextWrapper>()
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Test
    fun `returns PERMISSION_DENIED when location permission is not granted`() =
        runTest {
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            val provider = AndroidCurrentLocationProvider(context)

            val result = provider.getCurrentLocation()

            assertThat(result).isEqualTo(Result.Error(LocationError.PERMISSION_DENIED))
        }

    @Test
    fun `returns PROVIDER_UNAVAILABLE when the GPS provider is absent`() =
        runTest {
            shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            shadowOf(locationManager).removeProvider(LocationManager.GPS_PROVIDER)
            val provider = AndroidCurrentLocationProvider(context)

            val result = provider.getCurrentLocation()

            assertThat(result).isEqualTo(Result.Error(LocationError.PROVIDER_UNAVAILABLE))
        }

    @Test
    fun `returns the fix from a simulated GPS location`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val provider = AndroidCurrentLocationProvider(context)

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
        val provider = AndroidCurrentLocationProvider(context, timeoutMs = 1_000L)

        var result: Result<LocationFix, LocationError>? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        scope.launch { result = provider.getCurrentLocation() }

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000L))

        assertThat(result).isEqualTo(Result.Error(LocationError.TIMED_OUT))
        scope.cancel()
    }
}
