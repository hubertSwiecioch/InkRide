package com.speedevand.inkride.history.presentation

/**
 * Stable identifiers for the ride-detail screen. Metric rows all render through
 * the same `DetailRow` composable, so each call site supplies a key.
 *
 * The elevation chart's min/max labels are drawn straight onto a `Canvas`, so
 * they carry no semantics and cannot be tagged; only the chart itself is. Their
 * content is asserted in `RideRecordMappingTest` instead.
 */
object RideDetailTestTags {
    const val BACK_BUTTON = "ride_detail_back"
    const val EXPORT_BUTTON = "ride_detail_export"
    const val DELETE_BUTTON = "ride_detail_delete"
    const val CONFIRM_DELETE_DIALOG = "ride_detail_confirm_delete"
    const val CONFIRM_DELETE_ACCEPT = "ride_detail_confirm_delete_accept"
    const val CONFIRM_DELETE_CANCEL = "ride_detail_confirm_delete_cancel"
    const val NOT_FOUND = "ride_detail_not_found"
    const val LAPS_SECTION = "ride_detail_laps"
    const val TRAINING_SECTION = "ride_detail_training"
    const val TSS = "tss"
    const val IF = "intensity_factor"
    const val NP = "normalized_power"
    const val HR_TSS = "hr_tss"
    const val DECOUPLING = "decoupling"
    const val HR_ZONE_BARS = "ride_detail_hr_zones"
    const val POWER_ZONE_BARS = "ride_detail_power_zones"
    const val THRESHOLD_PROPOSAL = "ride_detail_threshold_proposal"
    const val THRESHOLD_ACCEPT = "ride_detail_threshold_accept"
    const val THRESHOLD_REJECT = "ride_detail_threshold_reject"
    const val ELEVATION_CHART = "ride_detail_elevation_chart"
    const val SHOW_ROUTE_BUTTON = "ride_detail_show_route"
    const val ROUTE_SHEET = "ride_detail_route_sheet"
    const val ROUTE_MAP = "ride_detail_route_map"

    const val START = "start"
    const val END = "end"
    const val DISTANCE = "distance"
    const val MOVING_TIME = "moving_time"
    const val ELAPSED_TIME = "elapsed_time"
    const val AVG_SPEED = "avg_speed"
    const val MAX_SPEED = "max_speed"
    const val ELEVATION_GAIN = "elevation_gain"
    const val CALORIES = "calories"
    const val AVG_POWER = "avg_power"

    fun detail(key: String): String = "ride_detail_value_$key"

    fun lapRow(lapNumber: Int): String = "ride_detail_lap_$lapNumber"
}
