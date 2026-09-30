// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.lsrith.acousticmonitor.models.ModelRepository
import java.util.Locale

/**
 * First-launch gate while model weights are fetched.
 *
 * Weights live in app-private storage rather than the APK, so this runs once.
 * A resumed or cached download reaches Ready without touching the network.
 */
@Composable
fun SetupScreen(
    state: ModelRepository.ModelsState,
    onRetry: () -> Unit,
) {
    ScreenColumn(spacing = 16.dp, padding = 24.dp) {
        Text("Preparing models", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Model weights are downloaded once and kept on this device. " +
                "Nothing is uploaded — the network is used only to fetch them.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state.bytesTotal > 0) {
            Text(
                "${formatBytes(state.bytesDone)} of ${formatBytes(state.bytesTotal)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        state.requiredItems.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.spec.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            // A stalled progress bar is indistinguishable from a
                            // slow one, so a retry in progress has to say so.
                            if (item.attempt > 0) "retrying ${item.attempt}/${item.maxAttempts}"
                            else item.status.name.lowercase(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (item.status == ModelRepository.Status.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Text(item.spec.purpose, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { item.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    item.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (!state.working && state.error != null) {
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f kB", bytes / 1e3)
    else -> "$bytes B"
}
