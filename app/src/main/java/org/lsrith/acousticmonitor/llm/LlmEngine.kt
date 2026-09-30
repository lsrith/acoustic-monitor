// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.lsrith.acousticmonitor.models.ModelCatalog
import org.lsrith.acousticmonitor.models.ModelSpec
import org.lsrith.acousticmonitor.models.ModelRepository
import java.io.File

/** Result of an ambiguous-case adjudication. */
data class LlmVerdict(
    val concerning: Boolean,
    val reason: String,
    val raw: String,
    val elapsedMs: Double,
)


class LlmEngine(
    private val context: Context,
    private val models: ModelRepository,
    private val choices: org.lsrith.acousticmonitor.models.ChoiceStore,
) {

    @Volatile
    private var engine: Engine? = null

    /**
     * One generation at a time, and no close while one is running.
     *
     * The interval loop and the on-demand button can fire together, and a
     * model switch closes the engine from the UI thread. Two threads inside
     * the native engine at once, or a native close under a running
     * generation, is a process death rather than an exception.
     */
    private val gate = Mutex()

    /**
     * Models that could not be loaded on this device.
     *
     * A persisted choice made a load failure permanent: the bad model was
     * restored on every launch and reasoning stayed silently dead. Recording
     * the failure lets [open] fall back and lets the UI say why.
     */
    private val _unavailable = MutableStateFlow(
        // A model that was mid-open when the process last died, twice running,
        // took the app down with it. Nothing in-process can catch that — the
        // runtime aborts rather than throwing — so the only way to break the
        // loop is to distrust it on the way back up. Twice, not once, so a
        // normal kill during a slow load does not condemn a working model.
        choices.pendingLlmAttempt()
            ?.takeIf { choices.llmFailures() >= ABORT_STRIKES }
            ?.let { setOf(it) }
            ?: emptySet()
    )
    val unavailable: StateFlow<Set<String>> = _unavailable.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _model = MutableStateFlow(
        // The remembered choice, not the compiled-in default. Defaulting on
        // every launch made a deliberate switch look like it had never been
        // applied at all.
        choices.loadLlm()
            ?.let { saved -> ModelCatalog.reasoningModels.firstOrNull { it.id == saved } }
            ?: ModelCatalog.QWEN3_06B
    )
    /** Which reasoning model is selected. Changing it closes the current engine. */
    val model: StateFlow<ModelSpec> = _model.asStateFlow()

    /**
     * Switches model.
     *
     * Returns immediately, whether or not the weights are on the device. The
     * engine is closed rather than swapped in place — generation may be in
     * flight — and the next [open] picks up the choice, by which time a
     * download started alongside this call has usually landed. A selection is
     * a statement of intent, not a request to wait.
     */
    suspend fun selectModel(spec: ModelSpec) {
        if (_model.value.id == spec.id) return
        _model.value = spec
        choices.saveLlm(spec.id)
        _error.value = null
        // A model that was tried before gets another chance when chosen
        // deliberately — the weights may since have finished downloading.
        _unavailable.value = _unavailable.value - spec.id
        // Waits for any generation in flight. Freeing the native engine
        // under a running one kills the process rather than throwing.
        gate.withLock { closeLocked() }
    }

    private val _backend = MutableStateFlow("—")
    /** Which delegate actually loaded. GPU is attempted first. */
    val backend: StateFlow<String> = _backend.asStateFlow()

    /**
     * Generations this engine instance has served.
     *
     * The process aborts inside LiteRT-LM after ~17–27 minutes of judging —
     * not at load, and not on a particular model. Something accumulates across
     * generations. Counted so the pattern is visible rather than inferred.
     */
    private var generations = 0

    private val _generations = MutableStateFlow(0)
    val generationsThisEngine: StateFlow<Int> = _generations.asStateFlow()

    /**
     * Native memory held by this process, sampled after each generation.
     *
     * Measured rather than assumed: a leak per generation and a runtime that
     * simply falls over after N calls look identical from a tombstone, and
     * they need different fixes. This is the number that separates them.
     */
    private val _nativeHeapKb = MutableStateFlow(0L)
    val nativeHeapKb: StateFlow<Long> = _nativeHeapKb.asStateFlow()

    private val _lastBriefing = MutableStateFlow<String?>(null)
    val lastBriefing: StateFlow<String?> = _lastBriefing.asStateFlow()

    suspend fun open(): Boolean = withContext(Dispatchers.Default) {
        gate.withLock { openLocked() }
    }

    private fun openLocked(): Boolean {
        if (engine != null) return true
        val spec = _model.value
        if (spec.id in _unavailable.value) {
            _error.value = "${spec.displayName} crashed this app while loading and " +
                "will not be tried again. Choose another model."
            return false
        }
        if (!models.isCached(spec)) {
            _error.value = "${spec.displayName} is not downloaded"
            return false
        }
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }

        // Written before the attempt, because if this model aborts the process
        // there will be no chance to write anything after it.
        choices.markLlmAttempt(spec.id)

        // GPU first, CPU as fallback. The published mid-range Android figures
        // for this model are GPU numbers; forcing CPU gives away most of that
        // for a delegate failure that may not even occur here. A wrong
        // delegate fails at load, which is recoverable — so try, then fall back.
        for ((backend, name) in listOf(Backend.GPU() to "GPU", Backend.CPU() to "CPU")) {
            try {
                val created = Engine(
                    EngineConfig(
                        modelPath = models.fileFor(spec).absolutePath,
                        backend = backend,
                        maxNumTokens = MAX_TOKENS,
                        cacheDir = cache.absolutePath,
                    )
                )
                created.initialize()

                // Initialisation succeeding does not mean the backend works.
                // On this hardware the GPU delegate initialises happily and
                // then fails at generation with "cannot find OpenCL Library",
                // so a fallback that only catches construction and init has
                // already committed by the time the real failure appears.
                // Prove the backend by generating one token before trusting it.
                probe(created)

                engine = created
                _backend.value = name
                _ready.value = true
                _error.value = null
                // It loaded and generated a token. Whatever killed the process
                // last time, it was not this.
                choices.clearLlmAttempt()
                generations = 0
                _generations.value = 0
                Log.i(TAG, "LLM ready on $name")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "LLM unusable on $name: ${t.message}")
                _error.value = t.message ?: "Could not load ${spec.displayName}"
            }
        }
        // Every backend refused it. Not a transient failure — the same
        // weights on the same hardware will refuse again — so record it and
        // let the next open() reach for something that does run.
        _unavailable.value = _unavailable.value + spec.id
        Log.w(TAG, "marking ${spec.id} unusable on this device")
        _ready.value = false

        val alternative = ModelCatalog.reasoningModels.firstOrNull {
            it.id !in _unavailable.value && models.isCached(it)
        }
        if (alternative != null) {
            Log.i(TAG, "falling back to ${alternative.id}")
            _model.value = alternative
            choices.saveLlm(alternative.id)
            _error.value = "${spec.displayName} could not be loaded; using ${alternative.displayName}"
            return openLocked()
        }
        return false
    }

    /**
     * Generates a single token to prove the backend actually runs.
     *
     * Throws if it does not, which is the point: initialisation succeeding does
     * not mean the delegate works, and without this the GPU path failed only at
     * real generation, long after the fallback had been given up on.
     */
    private fun probe(candidate: Engine) {
        val conversation = candidate.createConversation(ConversationConfig(maxOutputToken = 1))
        conversation.use { it.sendMessage("hi") }
    }

    private fun closeLocked() {
        runCatching { engine?.close() }
        engine = null
        _ready.value = false
    }

    suspend fun close() = gate.withLock { closeLocked() }

    /**
     * Reasons over a window of evidence rather than a single moment.
     *
     * Asked for a verdict word first so the answer is parseable even when the
     * model rambles. Missing observations must remain unknown rather than
     * being interpreted as normal conditions.
     */
    suspend fun reason(evidence: String): ReasonedWindow? = withContext(Dispatchers.Default) {
        if (engine == null) return@withContext null
        if (evidence.isBlank()) return@withContext null

        // Evidence first, question last. Led with eight lines of instruction,
        // small models answered the instructions rather than the timeline —
        // discussing what they had been asked to do instead of doing it.
        val prompt = buildString {
            appendLine("TIMELINE (times are seconds from the start of the window):")
            append(evidence.take(MAX_INPUT_CHARS))
            appendLine()
            appendLine()
            appendLine("Does this timeline warrant a person looking into it?")
            appendLine("Weigh the ORDER of events, not just their presence.")
            appendLine("Routine sounds consistent with the observed environment are ORDINARY.")
            appendLine("Describe measured deviations and their timing; do not infer intent or causes.")
            appendLine("Missing signals are unavailable; do not infer normal conditions from missing data.")
            appendLine()
            appendLine("Reply in exactly this form and nothing else:")
            appendLine("CONCERNING or ORDINARY")
            append("<one sentence about the timeline>")
        }

        runExclusive(REASON_SYSTEM, prompt)?.let { (text, ms) ->
            val judgement = VerdictParser.parse(text) ?: run {
                // No verdict word anywhere. The model produced prose about the
                // task rather than a judgement of the evidence. Reporting that
                // as a finding would be inventing a signal, so it is reported
                // as the failure it is and nothing is recorded.
                Log.w(TAG, "no verdict in reply: ${text.take(200)}")
                _error.value = "${_model.value.displayName} did not return a verdict"
                return@let null
            }
            _error.value = null
            ReasonedWindow(
                atEpochMs = System.currentTimeMillis(),
                concerning = judgement.concerning,
                reason = judgement.reason,
                windowSeconds = 0.0,
                generateMs = ms,
                evidence = "",
                modelName = _model.value.displayName,
            )
        }
    }

    /**
     * Adjudicates text the rules layer could not settle.
     *
     * Asked for a verdict word first so the answer is parseable even when the
     * model rambles — free-form output that has to be interpreted is how a
     * "maybe" silently becomes a "yes".
     */
    suspend fun judge(transcript: String): LlmVerdict? = withContext(Dispatchers.Default) {
        if (engine == null) return@withContext null
        if (transcript.isBlank()) return@withContext null

        val prompt = buildString {
            appendLine("Below is a transcript fragment from an audio monitoring session.")
            appendLine("Decide whether it contains threatening, coercive or intimidating language directed at someone.")
            appendLine("Answer with CONCERNING or ORDINARY on the first line, then one short sentence of reason.")
            appendLine("A keyword match alone does not establish intent; describe only what the transcript supports.")
            appendLine()
            appendLine("Transcript: \"${transcript.take(MAX_INPUT_CHARS)}\"")
        }

        runExclusive(JUDGE_SYSTEM, prompt)?.let { (text, ms) ->
            val judgement = VerdictParser.parse(text) ?: return@let null
            LlmVerdict(
                concerning = judgement.concerning,
                reason = judgement.reason,
                raw = text,
                elapsedMs = ms,
            )
        }
    }


    suspend fun brief(facts: String): String? = withContext(Dispatchers.Default) {
        if (engine == null) return@withContext null
        _busy.value = true
        try {
            val prompt = buildString {
                appendLine("Explain to the user why this audio event triggered an alert.")
                appendLine("Three or four sentences. Plain language. No speculation beyond the signals.")
                appendLine("Say which components drove the decision and which contributed nothing.")
                appendLine("If only one component drove it, say so plainly — that matters.")
                appendLine("State explicitly which signals were unavailable on this device.")
                appendLine("End with a confidence of low, medium or high and why.")
                appendLine()
                append(facts.take(MAX_INPUT_CHARS))
            }
            runExclusive(BRIEF_SYSTEM, prompt)?.first?.trim()?.also { _lastBriefing.value = it }
        } finally {
            _busy.value = false
        }
    }

    /**
     * Serialised: the interval loop, the on-demand button and a model switch
     * all reach this, and the native engine is not known to tolerate two
     * callers.
     */
    private suspend fun runExclusive(system: String, prompt: String): Pair<String, Double>? =
        gate.withLock {
            // Recycled before it can reach whatever it is that kills it. A
            // guard, not a diagnosis: the process aborts deep inside the
            // runtime after tens of generations, and until the counter and the
            // native-heap figure above say which, a fresh engine every so often
            // costs one load and buys a session that does not end in a crash.
            if (generations >= GENERATIONS_PER_ENGINE) {
                Log.i(TAG, "recycling engine after $generations generations")
                closeLocked()
                generations = 0
                _generations.value = 0
                if (!openLocked()) return@withLock null
            }
            val active = engine ?: return@withLock null
            runLocked(active, system, prompt)
        }

    private fun runLocked(active: Engine, system: String, prompt: String): Pair<String, Double>? {
        val started = SystemClock.elapsedRealtimeNanos()
        // Qwen3 reasons out loud by default and spends the whole output budget
        // doing it. The soft switch is part of the prompt, not the config.
        val instruction =
            if (_model.value.id.startsWith("qwen3")) "$system\n/no_think" else system
        return try {
            val conversation: Conversation = active.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(instruction),
                    maxOutputToken = MAX_OUTPUT_TOKENS,
                )
            )
            val reply = conversation.use { it.sendMessage(prompt) }
            val text = reply.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(" ") { it.text }
            val ms = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0

            generations++
            _generations.value = generations
            _nativeHeapKb.value = android.os.Debug.getNativeHeapAllocatedSize() / 1024
            Log.i(
                TAG,
                "generation $generations on ${_model.value.id}, " +
                    "native ${_nativeHeapKb.value} kB, ${ms.toInt()} ms",
            )

            if (text.isBlank()) null else text to ms
        } catch (t: Throwable) {
            Log.e(TAG, "generation failed on ${_backend.value}", t)
            _error.value = "${_backend.value}: ${t.message ?: "generation failed"}"
            // A backend that starts failing mid-session will not recover on its
            // own. Drop it so the next open() re-probes and can land on CPU,
            // rather than repeating a call that cannot work.
            closeLocked()
            null
        }
    }

    companion object {
        private const val TAG = "LlmEngine"
        /** Consecutive deaths mid-open before a model is refused. */
        private const val ABORT_STRIKES = 2

        /**
         * How many generations one engine instance serves before it is rebuilt.
         *
         * Well under the 15–20 that preceded an abort in the tombstones, and
         * high enough that the reload cost — about 21 s — is amortised over
         * roughly a quarter of an hour of judging.
         */
        private const val GENERATIONS_PER_ENGINE = 8

        private const val MAX_TOKENS = 1024
        private const val MAX_OUTPUT_TOKENS = 220
        private const val MAX_INPUT_CHARS = 1500

        private const val REASON_SYSTEM =
            "You review detection timelines from an experimental audio monitor. You are " +
                "one signal among several and never the decision. Be conservative: " +
                "a baseline deviation is not proof of danger. Do not infer intent or diagnose an event."

        private const val JUDGE_SYSTEM =
            "You assess short transcript fragments from an experimental audio monitor. " +
                "You are one signal among several and never the decision. " +
                "Report only explicit language cues; do not infer intent or diagnose an event."

        private const val BRIEF_SYSTEM =
            "You explain why an experimental audio monitoring system alerted, for its " +
                "user. Report only what the signals support, name what is " +
                "missing or silent, and do not treat a deviation as proof of danger."
    }
}
