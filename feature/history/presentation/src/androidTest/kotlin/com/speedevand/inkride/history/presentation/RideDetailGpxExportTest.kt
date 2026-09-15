package com.speedevand.inkride.history.presentation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.espresso.intent.matcher.IntentMatchers.isInternal
import androidx.test.espresso.intent.rule.IntentsRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.contains
import com.speedevand.inkride.core.domain.Result
import com.speedevand.inkride.core.domain.history.RideRecord
import com.speedevand.inkride.core.testing.support.TestRides
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.not
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideDetailGpxExportTest : HistoryTestHarness() {
    private companion object {
        const val RIDE_ID = 1L
    }

    override fun initialRides(): List<RideRecord> = listOf(TestRides.record(id = RIDE_ID))

    @get:Rule(order = 0)
    val intentsRule = IntentsRule()

    private fun setContent() {
        composeTestRule.setContent {
            RideDetailRoot(rideId = RIDE_ID, onNavigateBack = {})
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun exportingFiresAShareIntentCarryingTheGpxUri() {
        // Swallow the chooser so nothing actually launches.
        Intents.intending(not(isInternal())).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, null),
        )
        setContent()

        composeTestRule.onNodeWithTag(RideDetailTestTags.EXPORT_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                Intents.intended(hasAction(Intent.ACTION_CHOOSER))
            }.isSuccess
        }
        // The app dispatches a chooser wrapping the ACTION_SEND intent.
        Intents.intended(
            allOf(
                hasAction(Intent.ACTION_CHOOSER),
                hasExtra(
                    Intent.EXTRA_INTENT,
                    allOf(hasAction(Intent.ACTION_SEND), hasType("application/gpx+xml")),
                ),
            ),
        )
        assertThat(gpxExporter.exportedIds).contains(RIDE_ID)
    }

    @Test
    fun aRideWithNoTrackFiresNoIntent() {
        gpxExporter.result = Result.Error(GpxExportError.NO_TRACK)
        setContent()

        composeTestRule.onNodeWithTag(RideDetailTestTags.EXPORT_BUTTON).performClick()
        composeTestRule.waitForIdle()

        // The failure surfaces as a Toast; the observable consequence is that
        // no chooser was launched. (assertNoUnverifiedIntents would also trip
        // over the launcher intent that started the test activity.)
        Intents.intended(hasAction(Intent.ACTION_CHOOSER), times(0))
    }

    @Test
    fun aFailedExportFiresNoIntent() {
        gpxExporter.result = Result.Error(GpxExportError.FAILED)
        setContent()

        composeTestRule.onNodeWithTag(RideDetailTestTags.EXPORT_BUTTON).performClick()
        composeTestRule.waitForIdle()

        Intents.intended(hasAction(Intent.ACTION_CHOOSER), times(0))
    }
}
