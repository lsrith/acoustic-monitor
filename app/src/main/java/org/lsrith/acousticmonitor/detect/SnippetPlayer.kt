// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Plays back a stored snippet during review.
 *
 * One player at a time — starting a new snippet stops the previous one, which
 * is what you want when stepping through a list of events.
 */
class SnippetPlayer {

    private var player: MediaPlayer? = null

    fun play(path: String) {
        stop()
        val file = File(path)
        if (!file.isFile) {
            Log.w(TAG, "snippet missing: $path")
            return
        }
        player = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
                start()
            } catch (t: Throwable) {
                Log.e(TAG, "playback failed", t)
                runCatching { release() }
                player = null
            }
        }
    }

    fun stop() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
    }

    companion object {
        private const val TAG = "SnippetPlayer"
    }
}
