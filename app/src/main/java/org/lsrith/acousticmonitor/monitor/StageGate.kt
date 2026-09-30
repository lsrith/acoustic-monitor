// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which analysis stages may spend compute right now.
 *
 * The product view runs everything at once, because that is what a shipped
 * app would do. The signal walkthrough shows one page at a time, and a page
 * that is not on screen should not be running two recognisers and a language
 * model behind it: the phone's compute goes to the signal being demonstrated,
 * so its latency is its own and the screen stays smooth.
 *
 * The microphone stream, level metering, room baseline, mic integrity and
 * prosody are not gated. They are cheap DSP over every window, and the
 * baseline in particular must keep learning regardless of what is shown.
 *
 * Stages here are gates on *work*, not on models being loaded. A gated stage
 * that is already open stays open; a stage that is closed when its gate opens
 * is opened on next use, the same way it would be at start.
 */
class StageGate {

    data class Stages(
        /** YAMNet over every window, and the decision layer that reads it. */
        val classifier: Boolean = true,
        /** Both speech recognisers over each finished utterance. */
        val transcription: Boolean = true,
        /** Voiceprint comparison and diarization over each finished utterance. */
        val voices: Boolean = true,
        /** The language-model loop over the evidence timeline. */
        val reasoning: Boolean = true,
    ) {
        companion object {
            val ALL = Stages()
            val NONE = Stages(classifier = false, transcription = false, voices = false, reasoning = false)
        }
    }

    private val _stages = MutableStateFlow(Stages.ALL)
    val stages: StateFlow<Stages> = _stages.asStateFlow()

    /** Read on the inference threads; the flow's value is volatile. */
    val current: Stages get() = _stages.value

    fun set(stages: Stages) {
        _stages.value = stages
    }
}
