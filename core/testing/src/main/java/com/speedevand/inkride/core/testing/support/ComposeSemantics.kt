package com.speedevand.inkride.core.testing.support

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag

/**
 * The literal text rendered by a single tagged text node (e.g. a `TextMMD`
 * carrying a `Modifier.testTag(...)`). Reading semantics directly, rather
 * than matching on formatted/localized strings, keeps assertions robust to
 * decimal-separator/unit differences.
 */
fun SemanticsNodeInteraction.text(): String =
    fetchSemanticsNode()
        .config[SemanticsProperties.Text]
        .joinToString(separator = "") { it.text }

fun ComposeTestRule.textOf(tag: String): String = onNodeWithTag(tag).assertIsDisplayed().text()

/**
 * Polls a tagged node's text against [predicate] on a real wall clock, for
 * state that arrives from a background coroutine rather than Compose's test
 * clock.
 */
fun ComposeTestRule.waitUntilTagText(
    tag: String,
    timeoutMillis: Long = 15_000L,
    predicate: (String) -> Boolean,
) {
    waitUntil(timeoutMillis) {
        runCatching { predicate(textOf(tag)) }.getOrDefault(false)
    }
}

/** The content description of a single tagged node (e.g. an `Icon` carrying a `testTag`). */
fun SemanticsNodeInteraction.contentDescription(): String =
    fetchSemanticsNode()
        .config[SemanticsProperties.ContentDescription]
        .joinToString(separator = "") { it }

fun ComposeTestRule.contentDescriptionOf(tag: String): String = onNodeWithTag(tag).assertIsDisplayed().contentDescription()

/** Same real-clock polling as [waitUntilTagText], against a tagged node's content description. */
fun ComposeTestRule.waitUntilTagContentDescription(
    tag: String,
    timeoutMillis: Long = 15_000L,
    predicate: (String) -> Boolean,
) {
    waitUntil(timeoutMillis) {
        runCatching { predicate(contentDescriptionOf(tag)) }.getOrDefault(false)
    }
}
