package com.speedevand.inkride.dashboard.data

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.speedevand.inkride.dashboard.model.RideSensorSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicReference

class AndroidRideSensorDataSource(
    private val context: Context
) : RideSensorDataSource {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private var locationListener: LocationListener? = null
    private var pressureListener: SensorEventListener? = null

    override fun observeSamples(): Flow<RideSensorSample> = callbackFlow {
        val lastPressureHpa = AtomicReference<Float?>(null)

        val localPressureListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.values.isEmpty()) return
                lastPressureHpa.set(event.values[0])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val localLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val pressureHpa = lastPressureHpa.get()
                val altitudeFromBarometer = pressureHpa?.let {
                    SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it).toDouble()
                }

                trySend(
                    RideSensorSample(
                        timestampMs = location.time,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitudeFromGpsM = if (location.hasAltitude()) location.altitude else null,
                        altitudeFromBarometerM = altitudeFromBarometer,
                        speedFromGpsMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                        accuracyM = if (location.hasAccuracy()) location.accuracy else null
                    )
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

        pressureListener = localPressureListener
        locationListener = localLocationListener

        awaitClose {
            stop()
        }
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (!hasLocationPermission()) return

        pressureSensor?.also {
            pressureListener?.let { listener ->
                sensorManager.registerListener(
                    listener,
                    it,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
            }
        }

        locationListener?.let { listener ->
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1_000L,
                    0.5f,
                    listener
                )
            } catch (_: SecurityException) {
                // Runtime permission can still be revoked while tracking is active.
            }
        }
    }

    override fun stop() {
        locationListener?.let { listener ->
            locationManager.removeUpdates(listener)
        }
        pressureListener?.let { listener ->
            sensorManager.unregisterListener(listener)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }
}


