package com.speedevand.inkride.history.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.speedevand.inkride.core.toClockString

/**
 * Time in each training zone as horizontal bars.
 *
 * Drawn rather than composed per-segment so the whole breakdown is one E-Ink
 * redraw. Filled black on an outlined track: on a monochrome display a bar is
 * read by its length, and shading zones differently would only make them harder
 * to compare.
 */
@Composable
fun ZoneBars(
    secondsInZone: Map<Int, Long>,
    zoneCount: Int,
    modifier: Modifier = Modifier,
) {
    val longest = secondsInZone.values.maxOrNull()?.takeIf { it > 0L } ?: return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..zoneCount).forEach { zone ->
            val seconds = secondsInZone[zone] ?: 0L
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextMMD(
                    text = "Z$zone",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(28.dp),
                )
                val outline = MaterialTheme.colorScheme.outline
                val fill = MaterialTheme.colorScheme.primary
                Canvas(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(14.dp),
                ) {
                    drawRect(color = outline, size = size, style = Stroke(width = 1f))
                    val fraction = (seconds.toDouble() / longest).toFloat().coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        drawRect(color = fill, size = Size(size.width * fraction, size.height))
                    }
                }
                TextMMD(
                    // Zero-length zones still get a row: the gaps are part of
                    // reading where the effort actually went.
                    text = seconds.toClockString(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(60.dp),
                )
            }
        }
    }
}
