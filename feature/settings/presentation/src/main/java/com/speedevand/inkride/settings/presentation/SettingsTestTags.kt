package com.speedevand.inkride.settings.presentation

/**
 * Stable identifiers for Compose UI tests. The settings screen renders the same
 * row composables many times over (three steppers on one tab, ten switches on
 * another), so every reusable row takes a `key` and tags its interactive leaves
 * with it — text selectors alone would be ambiguous.
 */
object SettingsTestTags {
    const val WEIGHT = "weight"
    const val AGE = "age"
    const val BIKE_WEIGHT = "bike_weight"
    const val ALERT_MAX_SPEED = "alert_max_speed"
    const val ALERT_HR_MIN = "alert_hr_min"
    const val ALERT_HR_MAX = "alert_hr_max"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val BIKE_PROFILES = "bike_profiles"
    const val BLE_SENSORS = "ble_sensors"

    fun tab(tab: SettingsTab): String = "settings_tab_${tab.name}"

    fun stepperValue(key: String): String = "settings_stepper_value_$key"

    fun stepperMinus(key: String): String = "settings_stepper_minus_$key"

    fun stepperPlus(key: String): String = "settings_stepper_plus_$key"

    fun switch(key: String): String = "settings_switch_$key"

    fun radio(key: String): String = "settings_radio_$key"

    fun navRow(key: String): String = "settings_nav_row_$key"
}
