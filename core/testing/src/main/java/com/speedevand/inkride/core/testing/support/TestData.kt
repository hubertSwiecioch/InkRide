package com.speedevand.inkride.core.testing.support

import com.speedevand.inkride.core.domain.ble.BleDevice
import com.speedevand.inkride.core.domain.ble.BleSensorType
import com.speedevand.inkride.core.domain.settings.BikeProfile
import com.speedevand.inkride.core.domain.settings.BikeType
import com.speedevand.inkride.core.domain.settings.MeasurementUnits
import com.speedevand.inkride.core.domain.settings.UserSettings

object TestSettings {
    /**
     * A rider with weight and age filled in (both are required for calorie and
     * power estimation) and onboarding already complete, so a screen under test
     * is not redirected to the walkthrough.
     */
    fun default(
        units: MeasurementUnits = MeasurementUnits.METRIC,
        pairedHrmAddress: String? = null,
        pairedCadenceAddress: String? = null,
        activeBikeProfileId: Long? = null,
    ): UserSettings =
        UserSettings(
            weightKg = 75,
            age = 30,
            units = units,
            pairedHrmAddress = pairedHrmAddress,
            pairedCadenceAddress = pairedCadenceAddress,
            activeBikeProfileId = activeBikeProfileId,
            hasCompletedOnboarding = true,
        )
}

object TestBikeProfiles {
    fun road(
        id: Long = 1L,
        name: String = "Road bike",
        weightKg: Double = 8.5,
    ): BikeProfile = BikeProfile(id = id, name = name, weightKg = weightKg, type = BikeType.ROAD)

    fun mtb(
        id: Long = 2L,
        name: String = "MTB",
        weightKg: Double = 13.0,
    ): BikeProfile = BikeProfile(id = id, name = name, weightKg = weightKg, type = BikeType.MTB)
}

object TestBleDevices {
    fun hrm(
        address: String = "AA:BB:CC:DD:EE:01",
        name: String? = "HRM 9000",
    ): BleDevice = BleDevice(address = address, name = name, type = BleSensorType.HEART_RATE)

    fun cadence(
        address: String = "AA:BB:CC:DD:EE:02",
        name: String? = "Cadence 200",
    ): BleDevice = BleDevice(address = address, name = name, type = BleSensorType.CADENCE)
}
