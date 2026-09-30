// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.lsrith.acousticmonitor.data.AlertEventEntity
import org.lsrith.acousticmonitor.detect.EventEvidence
import org.lsrith.acousticmonitor.detect.LabelStats
import org.lsrith.acousticmonitor.detect.LabelSummary
import org.lsrith.acousticmonitor.detect.ReviewLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlertsScreen(
    events: List<AlertEventEntity>,
    onSetReview: (Long, ReviewLabel) -> Unit,
    onPlaySnippet: (String) -> Unit,
    onClearAll: () -> Unit,
    onExport: () -> Unit = {},
    exportMessage: String? = null,
    onBrief: (AlertEventEntity) -> Unit = {},
    briefing: String? = null,
    briefingBusy: Boolean = false,
    llmModelName: String = "the on-device model",
) {
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selected = events.firstOrNull { it.id == selectedId }

    // The detail view is a second navigation level, so the system back gesture
    // has to leave it rather than leave the app. Without this the only way out
    // is the in-content button, which is not where anyone reaches first.
    BackHandler(enabled = selected != null) { selectedId = null }

    if (selected != null) {
        AlertDetail(
            event = selected,
            onBack = { selectedId = null },
            onSetReview = onSetReview,
            onPlaySnippet = onPlaySnippet,
            onBrief = onBrief,
            briefing = briefing,
            briefingBusy = briefingBusy,
            llmModelName = llmModelName,
        )
        return
    }

    AlertList(
        events = events,
        onSelect = { selectedId = it },
        onSetReview = onSetReview,
        onClearAll = onClearAll,
        onExport = onExport,
        exportMessage = exportMessage,
    )
}


@Composable
private fun ReviewSummaryCard(summary: LabelSummary, onExport: () -> Unit, exportMessage: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Review", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Labelled", style = MaterialTheme.typography.bodySmall)
                Text(
                    "${summary.reviewed} of ${summary.total}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Precision", style = MaterialTheme.typography.bodySmall)
                Text(
                    summary.overallPrecision
                        ?.let { String.format(Locale.US, "%.0f%%", it * 100) }
                        ?: "not yet measurable",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            summary.byCue.filter { it.reviewed > 0 }.forEach { cue ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(cue.cueGroup.lowercase(), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${cue.confirmed}✓ ${cue.falsePositive}✗",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            OutlinedButton(onClick = onExport, enabled = summary.reviewed > 0) {
                Icon(Icons.Default.Download, contentDescription = null)
                Text("  Export labels (CSV)")
            }
            exportMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text(
                "Metadata only — audio stays on this device.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AlertList(
    events: List<AlertEventEntity>,
    onSelect: (Long) -> Unit,
    onSetReview: (Long, ReviewLabel) -> Unit,
    onClearAll: () -> Unit,
    onExport: () -> Unit,
    exportMessage: String?,
) {
    // LazyColumn is the root, not nested in a scrolling Column: the event list
    // is unbounded and must stay lazy. See ScreenColumn's KDoc.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Incidents", style = MaterialTheme.typography.titleMedium) }

        if (events.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No events recorded yet.", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Monitoring must be running for events to appear here.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            return@LazyColumn
        }

        item {
            ReviewSummaryCard(
                summary = LabelStats.summarise(events),
                onExport = onExport,
                exportMessage = exportMessage,
            )
        }
        items(events, key = { it.id }) { event ->
            AlertRow(
                event = event,
                onClick = { onSelect(event.id) },
                onSetReview = onSetReview,
            )
        }
        item { ClearAllControl(onClearAll = onClearAll) }
    }
}

@Composable
private fun ReviewToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlertRow(
    event: AlertEventEntity,
    onClick: () -> Unit,
    onSetReview: (Long, ReviewLabel) -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(event.startedAtEpochMs), style = MaterialTheme.typography.bodySmall)
                SeverityChip(event.severity)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(event.peakCueLabel, style = MaterialTheme.typography.bodyMedium)
                Text(
                    String.format(Locale.US, "%.2f", event.peakFusedScore),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${durationSeconds(event)} s",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                // Labelling inline rather than only in the detail view: reviewing
                // a night's events has to be fast or it will not get done, and
                // unlabelled events make precision unmeasurable.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReviewToggle(
                        icon = Icons.Default.Check,
                        description = "Confirm",
                        active = event.review == ReviewLabel.CONFIRMED.name,
                        activeColor = MaterialTheme.colorScheme.primary,
                        onClick = { onSetReview(event.id, ReviewLabel.CONFIRMED) },
                    )
                    ReviewToggle(
                        icon = Icons.Default.Close,
                        description = "Mark false positive",
                        active = event.review == ReviewLabel.FALSE_POSITIVE.name,
                        activeColor = MaterialTheme.colorScheme.error,
                        onClick = { onSetReview(event.id, ReviewLabel.FALSE_POSITIVE) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClearAllControl(onClearAll: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Clear all events")
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Clear all events?") },
            text = { Text("This permanently deletes all recorded events and their audio snippets.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onClearAll()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Turns an event's signals into a few sentences a professional can act on.
 *
 * Runs on demand rather than for every event: generation is slow on a
 * mid-range handset, and a briefing is only worth producing for something
 * someone is actually looking at.
 */

@Composable
private fun ContributionsCard(event: AlertEventEntity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Contributing components", style = MaterialTheme.typography.titleSmall)
            EventEvidence.of(event).forEach { c ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            c.component,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (c.contributed) FontWeight.Medium else FontWeight.Normal,
                            color = if (c.contributed) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (c.contributed) "contributed" else "silent",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (c.contributed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(c.kind, style = MaterialTheme.typography.bodySmall)
                    Text(
                        c.contribution,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun BriefingCard(
    event: AlertEventEntity,
    onBrief: (AlertEventEntity) -> Unit,
    briefing: String?,
    busy: Boolean,
    modelName: String,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Why this alerted", style = MaterialTheme.typography.titleSmall)
            if (busy) {
                Text("Generating on-device…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                OutlinedButton(onClick = { onBrief(event) }) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Text("  Explain with on-device LLM")
                }
            }
            briefing?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Text(
                    // Named from the selection rather than baked in: this said
                    // Qwen3 0.6B regardless of what actually generated it.
                    "Generated on this device by $modelName from the components above. " +
                        "It is an explanation of the signals, not a finding, and can be wrong.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AlertDetail(
    event: AlertEventEntity,
    onBack: () -> Unit,
    onSetReview: (Long, ReviewLabel) -> Unit,
    onPlaySnippet: (String) -> Unit,
    onBrief: (AlertEventEntity) -> Unit,
    briefing: String?,
    briefingBusy: Boolean,
    llmModelName: String,
) {
    ScreenColumn {
        TextButton(onClick = onBack) { Text("< Back") }

        ContributionsCard(event)

        BriefingCard(
            event = event,
            onBrief = onBrief,
            briefing = briefing,
            busy = briefingBusy,
            modelName = llmModelName,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatRow("Started", formatTime(event.startedAtEpochMs))
                StatRow("Ended", formatTime(event.endedAtEpochMs))
                StatRow("Severity", event.severity)
                StatRow("Peak fused score", String.format(Locale.US, "%.2f", event.peakFusedScore))
                StatRow("Peak cue", "${event.peakCueGroup} / ${event.peakCueLabel}")
                StatRow("Cue summary", event.cueSummary)
                StatRow("Media score at peak", String.format(Locale.US, "%.2f", event.mediaScoreAtPeak))
                StatRow("Speech score at peak", String.format(Locale.US, "%.2f", event.speechScoreAtPeak))
                StatRow("Windows", event.windowCount.toString())
                StatRow("Snippet", event.snippetPath?.let { fileName(it) } ?: "none")
            }
        }

        Text("Review", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewButton(
                label = "Confirmed",
                active = event.review == ReviewLabel.CONFIRMED.name,
                onClick = { onSetReview(event.id, ReviewLabel.CONFIRMED) },
            )
            ReviewButton(
                label = "False positive",
                active = event.review == ReviewLabel.FALSE_POSITIVE.name,
                onClick = { onSetReview(event.id, ReviewLabel.FALSE_POSITIVE) },
            )
        }

        event.snippetPath?.let { path ->
            OutlinedButton(onClick = { onPlaySnippet(path) }) { Text("Play snippet") }
        }
    }
}

@Composable
private fun ReviewButton(label: String, active: Boolean, onClick: () -> Unit) {
    if (active) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SeverityChip(severity: String) {
    val high = severity == "HIGH"
    val color = if (high) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Text(
        severity,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun reviewLabelText(review: String): String? = when (review) {
    ReviewLabel.CONFIRMED.name -> "✓ confirmed"
    ReviewLabel.FALSE_POSITIVE.name -> "✗ false positive"
    else -> null
}

private fun durationSeconds(event: AlertEventEntity): Long =
    (event.endedAtEpochMs - event.startedAtEpochMs) / 1000

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date(epochMs))

private fun fileName(path: String): String = path.substringAfterLast('/')
