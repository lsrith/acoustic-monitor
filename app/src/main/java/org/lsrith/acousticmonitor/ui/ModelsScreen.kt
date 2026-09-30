// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Everything the app can download, and what each thing is for.
 *
 * Its own destination rather than a section of Setup: only the core arrives
 * automatically, so this is where most features are actually turned on, and
 * burying that under calibration and voiceprints made it unfindable.
 */
@Composable
fun ModelsScreen(
    modelState: org.lsrith.acousticmonitor.models.ModelRepository.ModelsState,
    onDownload: (org.lsrith.acousticmonitor.models.ModelSpec) -> Unit,
) {
    ScreenColumn {
        Text("Models", style = MaterialTheme.typography.titleMedium)

        val present = modelState.items.count {
            it.status == org.lsrith.acousticmonitor.models.ModelRepository.Status.READY
        }
        val bytes = modelState.items
            .filter { it.status == org.lsrith.acousticmonitor.models.ModelRepository.Status.READY }
            .sumOf { it.spec.sizeBytes }
        Text(
            "$present of ${modelState.items.size} downloaded · ${formatSize(bytes)} on this device. " +
                "Only the core arrives automatically; everything else is fetched here.",
            style = MaterialTheme.typography.bodySmall,
        )

        ModelGroups(modelState, onDownload)
    }
}

/**
 * Every model, grouped by what it is for.
 *
 * Only the core is fetched at launch. Eleven files with opaque names is not a
 * choice anyone can make, so each group says what it is for and whether
 * anything in it is present — the question being answered is "what do I need
 * to download to make this feature work".
 */
@Composable
private fun ModelGroups(
    modelState: org.lsrith.acousticmonitor.models.ModelRepository.ModelsState,
    onDownload: (org.lsrith.acousticmonitor.models.ModelSpec) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        org.lsrith.acousticmonitor.models.ModelGroup.entries.forEach { group ->
            val specs = org.lsrith.acousticmonitor.models.ModelCatalog.inGroup(group)
            if (specs.isEmpty()) return@forEach
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(group.title, style = MaterialTheme.typography.titleSmall)
                    Text(group.summary, style = MaterialTheme.typography.bodySmall)
                    specs.forEach { spec ->
                        val item = modelState.itemFor(spec)
                        ModelRow(spec, item, onDownload)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    spec: org.lsrith.acousticmonitor.models.ModelSpec,
    item: org.lsrith.acousticmonitor.models.ModelRepository.ItemState?,
    onDownload: (org.lsrith.acousticmonitor.models.ModelSpec) -> Unit,
) {
    val status = item?.status ?: org.lsrith.acousticmonitor.models.ModelRepository.Status.PENDING
    val ready = status == org.lsrith.acousticmonitor.models.ModelRepository.Status.READY
    val busy = status == org.lsrith.acousticmonitor.models.ModelRepository.Status.DOWNLOADING ||
        status == org.lsrith.acousticmonitor.models.ModelRepository.Status.VERIFYING ||
        status == org.lsrith.acousticmonitor.models.ModelRepository.Status.EXTRACTING

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(spec.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (ready) "ready" else if (busy) status.name.lowercase() else formatSize(spec.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = when {
                    ready -> MaterialTheme.colorScheme.primary
                    status == org.lsrith.acousticmonitor.models.ModelRepository.Status.FAILED ->
                        MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(spec.purpose, style = MaterialTheme.typography.bodySmall)
        item?.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        when {
            ready -> Unit
            busy -> {
                // Extraction has no byte-wise progress to report, so an
                // indeterminate bar is honest where a stalled one would not be.
                if (status == org.lsrith.acousticmonitor.models.ModelRepository.Status.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { item?.fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            else -> TextButton(onClick = { onDownload(spec) }) {
                Text("Download ${formatSize(spec.sizeBytes)}")
            }
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%.0f kB", bytes / 1e3)
}
