// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.lsrith.acousticmonitor.audio.AudioPipeline
import org.lsrith.acousticmonitor.monitor.MonitorEngine
import org.lsrith.acousticmonitor.speaker.SpeakerMatch
import org.lsrith.acousticmonitor.ui.walkthrough.DemoMode
import org.lsrith.acousticmonitor.speech.SpeechEngine
import java.util.Locale

@Composable
fun SettingsScreen(
    demoMode: DemoMode,
    onSelectDemoMode: (DemoMode) -> Unit,
    calibration: MonitorEngine.Calibration,
    running: Boolean,
    onRelearn: () -> Unit,
    detectedLanguage: String?,
    secondPassActive: Boolean,
    onOpenModels: () -> Unit,
    asrError: String?,
    enrolled: List<String>,
    lastMatch: SpeakerMatch?,
    speakerReady: Boolean,
    speakerError: String?,
    enrolling: Boolean,
    enrollSecondsRemaining: Int,
    onStartEnroll: (String) -> Unit,
    onRemoveVoice: (String) -> Unit,
    onClearVoices: () -> Unit,
    captureState: AudioPipeline.CaptureState,
    level: Float,
    selectedLlm: org.lsrith.acousticmonitor.models.ModelSpec,
    unavailableLlm: Set<String>,
    onSelectLlm: (org.lsrith.acousticmonitor.models.ModelSpec) -> Unit,
    llmBackend: String,
    modelState: org.lsrith.acousticmonitor.models.ModelRepository.ModelsState,
    onDownloadModel: (org.lsrith.acousticmonitor.models.ModelSpec) -> Unit,
) {
    ScreenColumn {
        Text("Demo layout", style = MaterialTheme.typography.titleMedium)
        DemoLayoutCard(demoMode, onSelectDemoMode)

        Text("Calibration", style = MaterialTheme.typography.titleMedium)
        CalibrationCard(calibration = calibration, running = running, onRelearn = onRelearn)

        Text("Speech model", style = MaterialTheme.typography.titleMedium)
        SpeechModelCard(
            detectedLanguage = detectedLanguage,
            secondPassActive = secondPassActive,
            asrError = asrError,
            onOpenModels = onOpenModels,
        )

        Text("Voiceprints", style = MaterialTheme.typography.titleMedium)
        speakerError?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (!speakerReady) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Voiceprint model not ready", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The speaker model has not been downloaded yet. Enrolment is unavailable until it is.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        EnrolCard(
            ready = speakerReady,
            enrolling = enrolling,
            enrollSecondsRemaining = enrollSecondsRemaining,
            onStartEnroll = onStartEnroll,
        )
        EnrolledCard(enrolled = enrolled, onRemove = onRemoveVoice)
        LastMatchCard(lastMatch)
        ClearAllControl(enabled = enrolled.isNotEmpty(), onClearAll = onClearVoices)

        Text("Reasoning model", style = MaterialTheme.typography.titleMedium)
        LlmModelCard(selectedLlm, unavailableLlm, onSelectLlm, llmBackend, modelState, onDownloadModel)

        Text("Capture", style = MaterialTheme.typography.titleMedium)
        CaptureCard(captureState = captureState, level = level)
    }
}

/**
 * Same engine, two faces. The product view fuses every signal into one
 * verdict; the walkthrough gives each its own page and runs only that page's
 * models, so each can be provoked and timed on its own.
 */
@Composable
private fun DemoLayoutCard(mode: DemoMode, onSelect: (DemoMode) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (mode == DemoMode.WALKTHROUGH) "Signal walkthrough" else "Monitoring view",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (mode == DemoMode.WALKTHROUGH) {
                            "One page per on-device signal. Only the page on screen runs its models."
                        } else {
                            "Every signal fused into one live verdict, as the shipped app would show it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = mode == DemoMode.WALKTHROUGH,
                    onCheckedChange = { on ->
                        onSelect(if (on) DemoMode.WALKTHROUGH else DemoMode.PRODUCT)
                    },
                )
            }
        }
    }
}

@Composable
private fun CalibrationCard(
    calibration: MonitorEngine.Calibration,
    running: Boolean,
    onRelearn: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                !running -> Text(
                    "Monitoring must be running. Confidence builds continuously; the room is usable within a few minutes and keeps improving.",
                    style = MaterialTheme.typography.bodySmall,
                )
                calibration.ready -> Text(
                    "Room learned. Loudness and mic integrity are now judged against it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> {
                    Text(
                        "Learning the room — keep conditions normal. Whatever happens " +
                            "now becomes the baseline everything is later judged against.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { calibration.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StatRow(
                        "Observed",
                        String.format(
                            Locale.US, "%d:%02d",
                            calibration.observedSeconds / 60, calibration.observedSeconds % 60,
                        ),
                    )
                    StatRow(
                        "Confidence",
                        String.format(Locale.US, "%d%%", (calibration.progress * 100).toInt()),
                    )
                }
            }
            TextButton(onClick = onRelearn) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (calibration.neverCalibrated) "Calibrate room" else "Relearn room")
            }
        }
    }
}

/**
 * Chooses which model does the reasoning, and fetches it if absent.
 *
 * Only one runs at a time and they total several gigabytes, so all but the
 * default are fetched here rather than at first launch. A model that is not
 * present is offered with its size rather than silently unavailable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LlmModelCard(
    selected: org.lsrith.acousticmonitor.models.ModelSpec,
    unavailable: Set<String>,
    onSelect: (org.lsrith.acousticmonitor.models.ModelSpec) -> Unit,
    backend: String,
    modelState: org.lsrith.acousticmonitor.models.ModelRepository.ModelsState,
    onDownload: (org.lsrith.acousticmonitor.models.ModelSpec) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selected.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    org.lsrith.acousticmonitor.models.ModelCatalog.reasoningModels.forEach { spec ->
                        val item = modelState.itemFor(spec)
                        val present = item?.status == org.lsrith.acousticmonitor.models.ModelRepository.Status.READY
                        // Tried and refused by this hardware. Listed rather
                        // than hidden, for the same reason as the speech
                        // models: a model that vanishes looks like a bug.
                        val incompatible = spec.id in unavailable
                        DropdownMenuItem(
                            enabled = !incompatible,
                            text = {
                                Text(
                                    "${spec.displayName} · ${formatSize(spec.sizeBytes)}" + when {
                                        incompatible -> " · incompatible"
                                        present -> ""
                                        else -> " · not downloaded"
                                    },
                                    color = if (incompatible) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            onClick = {
                                onSelect(spec)
                                expanded = false
                            },
                        )
                    }
                }
            }

            val selectedItem = modelState.itemFor(selected)
            val ready = selectedItem?.status == org.lsrith.acousticmonitor.models.ModelRepository.Status.READY
            StatRow("Size", formatSize(selected.sizeBytes))
            StatRow("Backend", backend)
            StatRow("Status", if (ready) "ready" else selectedItem?.status?.name?.lowercase() ?: "not downloaded")

            if (!ready) {
                if (selectedItem?.status == org.lsrith.acousticmonitor.models.ModelRepository.Status.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { selectedItem.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedButton(onClick = { onDownload(selected) }) {
                        Text("Download ${formatSize(selected.sizeBytes)}")
                    }
                }
            }
            Text(
                "Bigger reasons better and runs slower. The reasoning loop paces itself " +
                    "to whatever this device manages, so a slower model means coarser " +
                    "cadence rather than a backlog.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The speech arrangement, which is fixed rather than chosen.
 *
 * There was a dropdown here. Once Whisper tiny and Moonshine were removed and
 * Omnilingual became the second pass rather than an alternative primary, it
 * offered one viable option — and the other option it offered could not
 * identify the language it was hearing, so selecting it was always a mistake.
 * A control with no valid alternative is not a setting; it is a way to break
 * something.
 */
@Composable
private fun SpeechModelCard(
    detectedLanguage: String?,
    secondPassActive: Boolean,
    asrError: String?,
    onOpenModels: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatRow("Recogniser", SpeechEngine.PRIMARY_LABEL)
            StatRow(
                "Non-English",
                if (secondPassActive) SpeechEngine.SECOND_PASS_LABEL else "not downloaded",
            )

            Text(
                if (secondPassActive) {
                    "Whisper identifies the language. Anything it reports as not-English " +
                        "is re-transcribed by Omnilingual, which covers Malay and Tamil. " +
                        "Both run on the same segment; neither works alone here."
                } else {
                    "Whisper transcribes every language itself, which is weak for Malay " +
                        "and Tamil. Download Omnilingual to add the second pass."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            if (!secondPassActive) {
                OutlinedButton(onClick = onOpenModels) { Text("Open models") }
            }

            // Reported, never chosen. A monitor that runs unattended cannot be
            // told in advance which language it will hear.
            Text(
                detectedLanguage?.let { "Last segment detected as: $it" }
                    ?: "No language reported for the last segment.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            asrError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EnrolCard(
    ready: Boolean,
    enrolling: Boolean,
    enrollSecondsRemaining: Int,
    onStartEnroll: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enrol", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Child, Adult A, …") },
                enabled = !enrolling,
                modifier = Modifier.fillMaxWidth(),
            )
            if (enrolling) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    // Speech still needed, not seconds still to wait. The
                    // countdown used to run whether or not anyone was talking,
                    // so standing silent for five seconds "enrolled" the room.
                    "Keep talking — ${enrollSecondsRemaining}s more speech needed",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Button(
                    onClick = {
                        onStartEnroll(name)
                        name = ""
                    },
                    enabled = ready && name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Record 5s voiceprint") }
            }
        }
    }
}

@Composable
private fun EnrolledCard(enrolled: List<String>, onRemove: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Enrolled voices", style = MaterialTheme.typography.titleSmall)
            if (enrolled.isEmpty()) {
                Text(
                    "No voiceprints enrolled yet. Verification cannot run without at least one.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                enrolled.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onRemove(name) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LastMatchCard(lastMatch: SpeakerMatch?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last match", style = MaterialTheme.typography.titleSmall)
            if (lastMatch == null) {
                Text("No verification yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                StatRow("Name", lastMatch.name)
                StatRow("Similarity", String.format(Locale.US, "%.2f", lastMatch.similarity))
                StatRow("Match", if (lastMatch.isMatch) "yes" else "no")
                Text(
                    "Similarity is the threshold the match cleared, not the true cosine " +
                        "closeness — the underlying engine reports pass/fail only, so this " +
                        "figure is a conservative lower bound, not a real similarity score.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ClearAllControl(enabled: Boolean, onClearAll: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { confirming = true },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Remove all voiceprints") }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove all voiceprints?") },
            text = { Text("This permanently deletes every enrolled voiceprint. Verification will not run again until at least one is re-enrolled.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onClearAll()
                }) { Text("Remove all") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CaptureCard(captureState: AudioPipeline.CaptureState, level: Float) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatRow("State", if (captureState.running) "running" else "stopped")
            StatRow("Audio source", captureState.audioSourceName)
            StatRow("Level", String.format(Locale.US, "%.1f dBFS", level))
            StatRow(
                "Windows",
                "${captureState.windowsEmitted} emitted, ${captureState.droppedWindows} dropped",
            )
        }
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
