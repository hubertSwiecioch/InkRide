package com.speedevand.inkride.core.domain.tracking.training

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.speedevand.inkride.core.domain.settings.UserSettings
import org.junit.jupiter.api.Test

class AthleteThresholdsTest {
    @Test
    fun `LTHR falls back to 90 percent of age-predicted HRmax but FTP is never guessed`() {
        val settings = UserSettings(weightKg = 75, age = 30, ftpWatts = null, lthrBpm = null)

        val thresholds = AthleteThresholds.from(settings)

        // Tanaka HRmax(30) = 187; 90 % = 168.
        assertThat(thresholds.lthrBpm).isEqualTo(168)
        assertThat(thresholds.ftpWatts).isNull()
    }

    @Test
    fun `explicit thresholds win over the fallback`() {
        val settings = UserSettings(weightKg = 75, age = 30, ftpWatts = 265, lthrBpm = 172)

        val thresholds = AthleteThresholds.from(settings)

        assertThat(thresholds.ftpWatts).isEqualTo(265)
        assertThat(thresholds.lthrBpm).isEqualTo(172)
    }

    @Test
    fun `the LTHR fallback tracks the rider's age`() {
        // An older rider has a lower predicted HRmax, so the same absent LTHR
        // must not resolve to the same number for everyone.
        val younger = AthleteThresholds.from(UserSettings(weightKg = 75, age = 20, lthrBpm = null))
        val older = AthleteThresholds.from(UserSettings(weightKg = 75, age = 60, lthrBpm = null))

        assertThat(younger.lthrBpm).isEqualTo(174)
        assertThat(older.lthrBpm).isEqualTo(149)
    }

    @Test
    fun `the age used for heart-rate zones comes from the rider's profile`() {
        val thresholds = AthleteThresholds.from(UserSettings(weightKg = 75, age = 44))

        assertThat(thresholds.ageForHrZones).isEqualTo(44)
    }
}
