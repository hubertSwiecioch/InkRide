package com.speedevand.inkride.history.presentation

import android.net.Uri
import androidx.core.net.toUri
import com.speedevand.inkride.core.domain.Result

/**
 * Lives here rather than in `:core:testing` because [GpxExporter] is declared in
 * this module, and `:core:testing` must not depend on a feature module.
 */
class FakeGpxExporter : GpxExporter {
    private val _exportedIds = mutableListOf<Long>()
    val exportedIds: List<Long> get() = _exportedIds

    var result: Result<Uri, GpxExportError> =
        Result.Success("content://com.speedevand.inkride.fileprovider/exports/ride-1.gpx".toUri())

    override suspend fun export(rideId: Long): Result<Uri, GpxExportError> {
        _exportedIds += rideId
        return result
    }
}
