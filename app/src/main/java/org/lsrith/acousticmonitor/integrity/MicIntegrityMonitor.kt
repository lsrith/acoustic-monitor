// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.integrity

import org.lsrith.acousticmonitor.audio.AudioSpec
import org.lsrith.acousticmonitor.monitor.RoomReading

enum class MicState {
    /** Learning the room. No judgement until the baseline exists. */
    WARMING,

    /** Level and spectral shape both consistent with the learned room. */
    OK,

    /** Quieter than usual, but the spectrum still looks like this room. */
    QUIET,

    /** Level down *and* treble disproportionately gone — consistent with covering. */
    OCCLUDED,

    /** Below any physical noise floor. The microphone is not delivering audio. */
    MUTED,
}

data class MicIntegrity(
    val state: MicState = MicState.WARMING,
    val rmsDbfs: Float = SILENCE_DBFS,
    val baselineDbfs: Float = SILENCE_DBFS,
    /** Fraction of energy above ~1 kHz. Falls sharply when the mic is covered. */
    val hfRatio: Float = 0f,
    val baselineHfRatio: Float = 0f,
    val secondsInState: Double = 0.0,
    val baselineReady: Boolean = false,
    /** 0..1 through the learning period. Shown so the wait is not a mystery. */
    val warmupProgress: Float = 0f,
) {
    /** True once the condition has persisted long enough to be worth reporting. */
    val actionable: Boolean
        get() = (state == MicState.OCCLUDED || state == MicState.MUTED) &&
            secondsInState >= SUSTAIN_SECONDS

    companion object {
        const val SILENCE_DBFS = -96f
        const val SUSTAIN_SECONDS = 8.0
    }
}


class MicIntegrityMonitor(private val config: Config = Config()) {

    data class Config(
        /** Level drop below baseline, in dB, that counts as "much quieter". */
        val quietDropDb: Float = 9f,

        /** Treble share must fall to this fraction of baseline to read as occlusion. */
        val occlusionTrebleFactor: Float = 0.55f,

        /** Below this, no physical microphone is delivering room tone. */
        val mutedFloorDbfs: Float = -85f,

    )

    private var state = MicState.WARMING
    private var stateSeconds = 0.0

    fun reset() {
        state = MicState.WARMING
        stateSeconds = 0.0
    }

    /**
     * Judges an already-measured window against the shared room profile.
     *
     * Measurement lives with the profile now — this used to compute the window
     * RMS a second time and learn a second baseline from it, so the two
     * estimates of one quantity drifted apart.
     */
    fun judge(
        dbfs: Float,
        trebleShare: Float,
        room: RoomReading,
        secondsElapsed: Double = AudioSpec.hopSeconds,
    ): MicIntegrity {
        val next = classify(dbfs, trebleShare, room)
        if (next == state) {
            stateSeconds += secondsElapsed
        } else {
            state = next
            stateSeconds = secondsElapsed
        }

        return MicIntegrity(
            state = state,
            rmsDbfs = dbfs,
            baselineDbfs = room.baselineDbfs,
            hfRatio = trebleShare,
            baselineHfRatio = room.baselineTreble,
            secondsInState = stateSeconds,
            baselineReady = room.confidence >= RoomReading.MIN_CONFIDENCE_FOR_OCCLUSION,
            warmupProgress = room.confidence,
        )
    }

    /**
     * Discards the learned room and starts over.
     *
     * Needed because the baseline is only as good as the conditions it was
     * learned in — a warm-up that happened to run with a television on has
     * learned the television.
     */
    fun relearn() = reset()

    private fun classify(db: Float, hf: Float, room: RoomReading): MicState {
        // Decisive and baseline-independent: no room is this quiet, so this
        // works from the first window without any learned profile at all.
        if (db <= config.mutedFloorDbfs) return MicState.MUTED

        // Occlusion is relative, so it needs a room worth comparing against.
        // Unlike the loudness path, which degrades gracefully with confidence,
        // this waits: a false tamper alert accuses someone.
        if (room.confidence < RoomReading.MIN_CONFIDENCE_FOR_OCCLUSION) return MicState.WARMING

        val quieter = db < room.baselineDbfs - config.quietDropDb
        val trebleLost = room.baselineTreble > 0f &&
            hf < room.baselineTreble * config.occlusionTrebleFactor

        return when {
            quieter && trebleLost -> MicState.OCCLUDED
            quieter -> MicState.QUIET
            else -> MicState.OK
        }
    }

}
