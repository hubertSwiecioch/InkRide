package com.speedevand.inkride.core.testing.support

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Resolves a string resource through the real app context, so assertions
 * compare against the actual localized string the UI renders instead of a
 * hardcoded English literal that would silently drift from `strings.xml`.
 *
 * In a library module's `androidTest`, the library and its test code share one
 * APK, so `targetContext` resolves that library's own resources.
 */
fun stringRes(
    @StringRes id: Int,
): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

fun stringRes(
    @StringRes id: Int,
    vararg formatArgs: Any,
): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *formatArgs)
