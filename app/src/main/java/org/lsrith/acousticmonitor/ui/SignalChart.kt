// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.util.Locale

/** One line on a chart. */
data class Trace(
    val label: String,
    val color: Color,
    val values: List<Float>,
    /**
     * Wall-clock time of each value, same length as [values].
     *
     * Used to break the line where monitoring stopped — not to position points,
     * which are still evenly spaced by index, as the axis label ("N windows")
     * says. Drawn without it, a ten-minute gap between two samples became a
     * straight segment joining them: a value drawn for a window that never
     * happened. A gap now shows as a gap; it just does not show as a wide one.
     */
    val timesMs: List<Long> = emptyList(),
    /** Dashed lines are references — a baseline or a threshold, not a measurement. */
    val dashed: Boolean = false,
)

/**
 * Palette for the traces, chosen so a measurement and the thing it is compared
 * against never share a hue.
 */
object TraceColors {
    val measured = Color(0xFF2962FF)
    val reference = Color(0xFF9E9E9E)
    val alarm = Color(0xFFD32F2F)
    val warn = Color(0xFFF9A825)
    val media = Color(0xFF7B1FA2)
    val speech = Color(0xFF00897B)
    val calm = Color(0xFF43A047)
}

/**
 * Builds a trace from the recorded history, once per change rather than once
 * per frame.
 *
 * The straightforward `history.map { it.field }` at each call site rebuilt
 * eleven boxed float lists on every decision window — a few thousand
 * allocations a second with Diagnostics open, for data that had changed by one
 * element. Keying on the history list collapses that to one rebuild per window.
 */
@Composable
fun rememberTrace(
    label: String,
    color: Color,
    history: List<org.lsrith.acousticmonitor.monitor.SignalHistory.Sample>,
    dashed: Boolean = false,
    select: (org.lsrith.acousticmonitor.monitor.SignalHistory.Sample) -> Float,
): Trace = remember(history, select, color, dashed) {
    Trace(
        label = label,
        color = color,
        values = history.map(select),
        timesMs = history.map { it.atEpochMs },
        dashed = dashed,
    )
}

/**
 * A small multi-series trace over the recent decision windows.
 *
 * The whole point of these charts is comparison: a level means nothing without
 * the room's baseline drawn beside it, and a fused score means nothing without
 * the moving threshold it is being tested against. So a chart never shows a
 * lone series — it shows a measurement and its reference on the same axes.
 *
 * Every value is a recorded decision window. Nothing here is interpolated,
 * smoothed or extended; an empty chart says so rather than drawing a flat line
 * that looks like silence.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalChart(
    title: String,
    explanation: String,
    traces: List<Trace>,
    modifier: Modifier = Modifier,
    /** Fixed axis when the scale is meaningful in itself, e.g. a 0..1 score. */
    fixedMin: Float? = null,
    fixedMax: Float? = null,
    unit: String = "",
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(explanation, style = MaterialTheme.typography.bodySmall)

            val points = traces.maxOfOrNull { it.values.size } ?: 0
            if (points < 2) {
                Text(
                    "Not enough windows yet. The trace fills as monitoring runs.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                return@Column
            }

            val all = traces.flatMap { it.values }
            val lo = fixedMin ?: all.min()
            val hi = fixedMax ?: all.max()
            // A flat trace has zero range; without a floor it would divide by
            // zero and then be drawn along the top edge.
            val span = (hi - lo).takeIf { it > 1e-4f } ?: 1f

            val grid = MaterialTheme.colorScheme.outlineVariant

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)

                traces.forEach { trace ->
                    if (trace.values.size < 2) return@forEach
                    val path = Path()
                    // Every trace is right-aligned to "now", so a series that
                    // started late does not appear shifted in time.
                    val offset = points - trace.values.size
                    var pendingMove = true
                    trace.values.forEachIndexed { i, value ->
                        val x = (offset + i).toFloat() / (points - 1) * size.width
                        val norm = ((value - lo) / span).coerceIn(0f, 1f)
                        val y = size.height - norm * size.height

                        // Lift the pen across a gap. Monitoring stopped, the
                        // app was killed, or the room was recalibrated — and
                        // joining across it would draw values for windows that
                        // did not occur.
                        val gap = i > 0 && trace.timesMs.size == trace.values.size &&
                            trace.timesMs[i] - trace.timesMs[i - 1] > GAP_BREAK_MS

                        if (pendingMove || gap) path.moveTo(x, y) else path.lineTo(x, y)
                        pendingMove = false
                    }
                    drawPath(
                        path = path,
                        color = trace.color,
                        style = Stroke(
                            width = if (trace.dashed) 2f else 3f,
                            pathEffect = if (trace.dashed) {
                                PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                            } else null,
                        ),
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtAxis(lo, unit), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Text("$points windows", style = MaterialTheme.typography.labelSmall)
                Text(fmtAxis(hi, unit), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                traces.forEach { trace ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(trace.color)
                        )
                        Text(
                            "  ${trace.label}${if (trace.dashed) " (reference)" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Longer than any normal window spacing, short enough that a stop-and-start is
 * always a visible break rather than a plausible-looking line.
 */
private const val GAP_BREAK_MS = 5_000L

private fun fmtAxis(value: Float, unit: String): String =
    String.format(Locale.US, "%.2f%s", value, unit)
