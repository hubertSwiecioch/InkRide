package com.speedevand.inkride.core.domain.tracking.training

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class PowerZoneCalculatorTest {
    private val calculator = PowerZoneCalculator()

    @Test
    fun `maps power onto the seven Coggan zones by percentage of FTP`() {
        // FTP 200 W: boundaries at 55/75/90/105/120/150 % = 110/150/180/210/240/300 W.
        assertThat(calculator.zoneFor(100, ftpWatts = 200)).isEqualTo(1)
        assertThat(calculator.zoneFor(140, ftpWatts = 200)).isEqualTo(2)
        assertThat(calculator.zoneFor(170, ftpWatts = 200)).isEqualTo(3)
        assertThat(calculator.zoneFor(200, ftpWatts = 200)).isEqualTo(4)
        assertThat(calculator.zoneFor(230, ftpWatts = 200)).isEqualTo(5)
        assertThat(calculator.zoneFor(280, ftpWatts = 200)).isEqualTo(6)
        assertThat(calculator.zoneFor(400, ftpWatts = 200)).isEqualTo(7)
    }

    @Test
    fun `a boundary watt belongs to the zone it opens, not the one it closes`() {
        // Exactly 55 % of FTP starts zone 2; a hair under stays in zone 1.
        assertThat(calculator.zoneFor(110, ftpWatts = 200)).isEqualTo(2)
        assertThat(calculator.zoneFor(109, ftpWatts = 200)).isEqualTo(1)
        // And the top boundary, 150 %, opens zone 7.
        assertThat(calculator.zoneFor(300, ftpWatts = 200)).isEqualTo(7)
        assertThat(calculator.zoneFor(299, ftpWatts = 200)).isEqualTo(6)
    }

    @Test
    fun `a nonsensical FTP degrades to zone 1 rather than dividing by zero`() {
        assertThat(calculator.zoneFor(250, ftpWatts = 0)).isEqualTo(1)
        assertThat(calculator.zoneFor(250, ftpWatts = -10)).isEqualTo(1)
    }
}
