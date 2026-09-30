// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.lsrith.acousticmonitor.ui.AcousticMonitorApp

class MainActivity : ComponentActivity() {

    private var micGranted by mutableStateOf(false)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        micGranted = result[Manifest.permission.RECORD_AUDIO] ?: micGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val app = application as AcousticMonitorApplication
        // Recognise what is already on disk before any download runs.
        app.models.refreshFromDisk()
        fetchModels()

        setContent {
            AcousticMonitorApp(
                engine = app.engine,
                models = app.models,
                alerts = app.alerts,
                speaker = app.speaker,
                llm = app.llm,
                reasoningLoop = app.reasoning,
                diarization = app.diarization,
                choices = app.choices,
                micGranted = micGranted,
                onRequestPermissions = { requestPermissions.launch(neededPermissions()) },
                onRetryModels = { fetchModels() },
            )
        }
    }

    /** Cached weights short-circuit without touching the network, so this is cheap to call on every launch. */
    private fun fetchModels() {
        lifecycleScope.launch {
            (application as AcousticMonitorApplication).models.ensureAll()
        }
    }

    private fun neededPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}
