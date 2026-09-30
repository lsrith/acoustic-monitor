// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.models

/**
 * A model weight file fetched at runtime rather than bundled.
 *
 * [sha256] is not optional bookkeeping: it is how a truncated or corrupted
 * download is distinguished from a good one, and how we know a cached file is
 * still the model we think it is.
 *
 * For [Kind.TAR_BZ2], [sizeBytes] and [sha256] describe the downloaded
 * `.tar.bz2` archive itself, not the files it extracts into.
 */
/** What a model is for, so a list of eleven files is navigable. */
enum class ModelGroup(val title: String, val summary: String) {
    CORE(
        "Core detection",
        "Required. Acoustic events and speech segmentation — the app cannot run without these.",
    ),
    SPEECH_TO_TEXT(
        "Speech to text",
        "Turns speech into words. Pick one; they differ in accuracy, speed and language coverage.",
    ),
    VOICE_IDENTITY(
        "Voice identity",
        "Who is speaking, and how many. Needed for voiceprint enrolment and diarization.",
    ),
    LANGUAGE(
        "Language understanding",
        "Judges what was said. Complements the built-in rules, which need no download.",
    ),
    REASONING(
        "Reasoning",
        "Weighs a window of evidence and explains alerts. Pick one; bigger reasons better and runs slower.",
    ),
}

data class ModelSpec(
    val id: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val displayName: String,
    val purpose: String,
    val kind: Kind = Kind.FILE,
    /** Directory name under models/ that a TAR_BZ2 archive extracts into. Null for FILE. */
    val extractDir: String? = null,
    /**
     * Files that must exist after extraction for this model to be usable.
     *
     * A non-empty directory is not proof of a good extraction. One archive
     * unpacked leaving only its README and tokens behind, and because the
     * directory was not empty it was reported as cached — after which sherpa
     * constructed a recogniser with a null native handle and the process
     * segfaulted on first use. A missing file must be caught here, where it can
     * be reported, not in native code where it cannot.
     */
    val requiredFiles: List<String> = emptyList(),
    val group: ModelGroup = ModelGroup.CORE,
) {
    enum class Kind { FILE, TAR_BZ2 }
}

/**
 * Every model the app can fetch, and which ones the current build needs.
 *
 * Weights are downloaded on first launch and cached in app-private storage, so
 * the APK stays small as later phases add ASR, speaker and LLM models that run
 * to hundreds of megabytes.
 */
object ModelCatalog {

    val YAMNET = ModelSpec(
        id = "yamnet",
        fileName = "yamnet.tflite",
        url = "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite",
        sizeBytes = 4_126_810L,
        sha256 = "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf",
        displayName = "YAMNet",
        purpose = "Acoustic fast path — 521 AudioSet classes",
        group = ModelGroup.CORE,
    )

    val SILERO_VAD = ModelSpec(
        id = "silero-vad",
        fileName = "silero_vad.onnx",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        sizeBytes = 643_854L,
        sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        displayName = "Silero VAD",
        purpose = "Speech segmentation — gates the expensive stages",
        group = ModelGroup.CORE,
    )

    /**
     * Whisper base — the primary recogniser, and the one that identifies the language.
     *
     * Roughly six times tiny's parameter count for 207 MB, and the first thing
     * to try when far-field transcription is poor but the level looks healthy.
     */
    val WHISPER_BASE = ModelSpec(
        id = "whisper-base",
        fileName = "sherpa-onnx-whisper-base.tar.bz2",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2",
        sizeBytes = 207_557_382L,
        sha256 = "911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de",
        displayName = "Whisper base",
        purpose = "Multilingual speech-to-text, stronger than tiny",
        kind = ModelSpec.Kind.TAR_BZ2,
        extractDir = "whisper-base",
        requiredFiles = listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt"),
        group = ModelGroup.SPEECH_TO_TEXT,
    )


    val OMNILINGUAL_300M = ModelSpec(
        id = "omnilingual-300m",
        fileName = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2",
        sizeBytes = 292_571_207L,
        sha256 = "cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed",
        displayName = "Omnilingual 300M",
        purpose = "Speech-to-text covering Malay and Tamil",
        kind = ModelSpec.Kind.TAR_BZ2,
        extractDir = "omnilingual-300m",
        requiredFiles = listOf("model.int8.onnx", "tokens.txt"),
        group = ModelGroup.SPEECH_TO_TEXT,
    )

    val SPEAKER_EMBEDDING = ModelSpec(
        id = "speaker-embedding",
        fileName = "wespeaker_en_voxceleb_CAM++.onnx",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_CAM%2B%2B.onnx",
        sizeBytes = 29_292_684L,
        sha256 = "c46fad10b5f81e1aa4a60c162714208577093655076c5450f8c469e522ec54ef",
        displayName = "WeSpeaker CAM++",
        purpose = "Voiceprint embedding — is this the enrolled speaker?",
        group = ModelGroup.VOICE_IDENTITY,
    )

    val SPEAKER_SEGMENTATION = ModelSpec(
        id = "speaker-segmentation",
        fileName = "sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
        sizeBytes = 6_958_444L,
        sha256 = "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488",
        displayName = "Pyannote segmentation",
        purpose = "Speaker diarization — how many voices, who spoke when",
        kind = ModelSpec.Kind.TAR_BZ2,
        extractDir = "pyannote-segmentation",
        requiredFiles = listOf("model.int8.onnx"),
        group = ModelGroup.VOICE_IDENTITY,
    )

    val TOXIC_BERT = ModelSpec(
        id = "toxic-bert",
        fileName = "toxic_bert_int8.onnx",
        url = "https://huggingface.co/Xenova/toxic-bert/resolve/main/onnx/model_int8.onnx",
        sizeBytes = 110_250_454L,
        sha256 = "6cb1e5e2208afa58e461f070e016bc2cdfe7278327a6df495c92e47bb563d302",
        displayName = "Toxic-BERT",
        purpose = "ONNX text-classification signal — toxicity/threat language over ASR transcript",
        group = ModelGroup.LANGUAGE,
    )

    val TOXIC_BERT_VOCAB = ModelSpec(
        id = "toxic-bert-vocab",
        fileName = "toxic_bert_vocab.txt",
        url = "https://huggingface.co/Xenova/toxic-bert/resolve/main/vocab.txt",
        sizeBytes = 231_508L,
        sha256 = "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3",
        displayName = "Toxic-BERT vocabulary",
        purpose = "WordPiece vocab for Toxic-BERT tokenization",
        group = ModelGroup.LANGUAGE,
    )


    val QWEN3_06B = ModelSpec(
        id = "qwen3-0.6b",
        fileName = "qwen3_0_6b_mixed_int4.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/qwen3_0_6b_mixed_int4.litertlm",
        sizeBytes = 497_664_000L,
        sha256 = "b1baab462f6be49d70eada79d715c2c52cd9ece0cad00bddf6a2c097d23498e9",
        displayName = "Qwen3 0.6B",
        purpose = "Ambiguous-case judgement and incident briefing",
        group = ModelGroup.REASONING,
    )

    /**
     * The smallest reasoning model worth trying — 143 MB.
     *
     * Included because on CPU the practical constraint is speed, not quality,
     * and a weak verdict every few seconds may beat a better one every minute.
     * Which is true here is a measurement, not a guess.
     */
    val SMOLLM2_135M = ModelSpec(
        id = "smollm2-135m",
        fileName = "SmolLM2_135M_Instruct.litertlm",
        url = "https://huggingface.co/litert-community/SmolLM2-135M-Instruct/resolve/main/SmolLM2_135M_Instruct.litertlm",
        sizeBytes = 142_819_328L,
        sha256 = "ccdc5c85735743f081b7d44ca309cab569f76c0f2f0e8e163449a63721969c37",
        displayName = "SmolLM2 135M",
        purpose = "Fastest reasoning option — weakest judgement",
        group = ModelGroup.REASONING,
    )

    /** Apache-2.0, int4. Roughly three times Qwen3-0.6B's parameters. */
    val QWEN3_17B = ModelSpec(
        id = "qwen3-1.7b",
        fileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        sizeBytes = 977_184_032L,
        sha256 = "2eeffef7b51bc3e1225ea69fe7aa5f417397934b56a5b6c20cc068d6fd2c918b",
        displayName = "Qwen3 1.7B",
        purpose = "Stronger reasoning, proportionally slower",
        group = ModelGroup.REASONING,
    )

    /**
     * The strongest of the set, and the slowest.
     *
     * Its published GSM8K holds at int4 (81%, matching bf16), which is the one
     * reasoning figure available for any candidate here — though grade-school
     * arithmetic is a poor proxy for judging a household timeline.
     */
    val SMOLLM3_3B = ModelSpec(
        id = "smollm3-3b",
        fileName = "SmolLM3-3B_q4_block32_ekv4096.litertlm",
        url = "https://huggingface.co/litert-community/SmolLM3-3B/resolve/main/SmolLM3-3B_q4_block32_ekv4096.litertlm",
        sizeBytes = 2_002_257_840L,
        sha256 = "38d7bf55e243f5a95d8b16c21b1f7ef7debe6ce6bb2ddb66eb54f4bc2be8e6eb",
        displayName = "SmolLM3 3B",
        purpose = "Best judgement available here, at the highest latency",
        group = ModelGroup.REASONING,
    )

    /**
     * Qwen3.5 0.8B, int8 — the newest of the set, published this week.
     *
     * Worth having precisely because it is current: the reasoning quality of
     * these small models moves quickly, and the assumption that a 2025 model is
     * the best available at this size has a short shelf life.
     */
    val QWEN35_08B = ModelSpec(
        id = "qwen3.5-0.8b",
        fileName = "Qwen3.5-0.8B_int8.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3.5-0.8B/resolve/main/Qwen3.5-0.8B_int8.litertlm",
        sizeBytes = 978_249_216L,
        sha256 = "d1bc816351be3862c16b443bcabf6ba81efcba7507d45429e5571bd2861a966c",
        displayName = "Qwen3.5 0.8B",
        purpose = "Newest small reasoner",
        group = ModelGroup.REASONING,
    )


    val GEMMA4_E2B = ModelSpec(
        id = "gemma-4-e2b",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        displayName = "Gemma 4 E2B",
        purpose = "Google's smallest ungated reasoner — largest here",
        group = ModelGroup.REASONING,
    )

    /** Everything the catalogue knows about. */
    val all: List<ModelSpec> = listOf(
        YAMNET, SILERO_VAD, WHISPER_BASE,
        OMNILINGUAL_300M, SPEAKER_EMBEDDING, SPEAKER_SEGMENTATION,
        TOXIC_BERT, TOXIC_BERT_VOCAB,
        SMOLLM2_135M, QWEN3_06B, QWEN35_08B, QWEN3_17B, GEMMA4_E2B, SMOLLM3_3B,
    )

    /**
     * Models fetched before the app will start.
     *
     * Fetching large weights on demand failed in practice for the ASR/speaker
     * stages: the feature that needed them simply appeared broken while a
     * silent 116 MB download ran with no progress anywhere in the UI. One
     * visible download up front is slower to start but honest about what it
     * is doing, and every feature works the moment the app opens.
     *
     * No exceptions, including the toxicity classifier: anything fetched
     * lazily is a feature that will look broken the first time someone opens
     * it. One honest download covering everything is the whole point.
     */
    /**
     * Fetched before the app will start — the core only, about 5 MB.
     *
     * Everything else is downloaded deliberately from Settings. Making the
     * whole set mandatory meant a first launch waited on well over a gigabyte,
     * and one slow extraction held the entire app at "Preparing models" with no
     * way past it. A feature whose model is absent says so and offers to fetch
     * it; the app itself starts in seconds.
     */
    val required: List<ModelSpec> = listOf(YAMNET, SILERO_VAD)

    fun inGroup(group: ModelGroup): List<ModelSpec> = all.filter { it.group == group }

    /** Selectable reasoning models, smallest first. */
    val reasoningModels: List<ModelSpec> =
        listOf(SMOLLM2_135M, QWEN3_06B, QWEN35_08B, QWEN3_17B, GEMMA4_E2B, SMOLLM3_3B)

    val totalBytes: Long get() = required.sumOf { it.sizeBytes }
}
