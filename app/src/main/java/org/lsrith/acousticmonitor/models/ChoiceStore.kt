// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.models

import android.content.Context

/**
 * Remembers which reasoning model was chosen, across restarts and reinstalls.
 *
 * Without this the app reverted to its compiled-in defaults on every launch,
 * so a deliberate switch to a larger reasoning model silently became the
 * smallest one again the next time the process started — and looked, from the
 * outside, exactly like the selection being hardcoded.
 *
 * Stores identifiers rather than enum ordinals: a catalogue entry added or
 * reordered in a later build must not silently repoint an existing choice at a
 * different model.
 */
class ChoiceStore(context: Context) {

    private val prefs = context.getSharedPreferences("choices", Context.MODE_PRIVATE)

    // The speech arrangement is fixed rather than chosen, so there is nothing
    // to remember about it — see SpeechEngine.PRIMARY_LABEL.
    fun loadLlm(): String? = prefs.getString(KEY_LLM, null)

    fun saveLlm(id: String) {
        prefs.edit().putString(KEY_LLM, id).apply()
        clearLlmAttempt()
    }

    /**
     * Records that a reasoning model is being opened, before it is opened.
     *
     * LiteRT-LM does not throw when it cannot run a model — it calls abort(),
     * which no catch block in this process can intercept. The only evidence
     * that survives is what was written down beforehand. Committed
     * synchronously, because an apply() may not reach disk before the process
     * dies.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun markLlmAttempt(id: String) {
        val failures = if (prefs.getString(KEY_LLM_ATTEMPT, null) == id) llmFailures() + 1 else 1
        prefs.edit()
            .putString(KEY_LLM_ATTEMPT, id)
            .putInt(KEY_LLM_FAILURES, failures)
            .commit()
    }

    /** Called once a model has actually produced output. */
    fun clearLlmAttempt() {
        prefs.edit().remove(KEY_LLM_ATTEMPT).remove(KEY_LLM_FAILURES).apply()
    }

    /** The model that was mid-open when the process last died, if any. */
    fun pendingLlmAttempt(): String? = prefs.getString(KEY_LLM_ATTEMPT, null)

    fun llmFailures(): Int = prefs.getInt(KEY_LLM_FAILURES, 0)

    /**
     * Which demonstration layout was last chosen.
     *
     * Stored by name for the same reason the model choice is: an ordinal would
     * silently repoint at a different layout if the enum were reordered.
     */
    fun loadDemoMode(): String? = prefs.getString(KEY_DEMO_MODE, null)

    fun saveDemoMode(name: String) {
        prefs.edit().putString(KEY_DEMO_MODE, name).apply()
    }

    private companion object {
        const val KEY_LLM = "llm_model"
        const val KEY_LLM_ATTEMPT = "llm_open_attempt"
        const val KEY_LLM_FAILURES = "llm_open_failures"
        const val KEY_DEMO_MODE = "demo_mode"
    }
}
