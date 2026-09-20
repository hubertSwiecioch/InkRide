package com.speedevand.inkride.core.domain.tracking.training

/**
 * Coggan's seven power training zones as a percentage of FTP. Zone 1 is active
 * recovery, zone 7 neuromuscular. Boundaries: 55, 75, 90, 105, 120 and 150 %.
 */
class PowerZoneCalculator {
    fun zoneFor(
        powerWatts: Int,
        ftpWatts: Int,
    ): Int {
        if (ftpWatts <= 0) return 1
        val percent = powerWatts.toDouble() / ftpWatts * 100.0
        return when {
            percent < 55.0 -> 1
            percent < 75.0 -> 2
            percent < 90.0 -> 3
            percent < 105.0 -> 4
            percent < 120.0 -> 5
            percent < 150.0 -> 6
            else -> 7
        }
    }
}
