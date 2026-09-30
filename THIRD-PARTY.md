# Third-party components

The project license covers original Acoustic Monitor code. It does not relicense
the dependencies, Gradle wrapper, or separately downloaded model weights.

Dependencies and exact versions are declared in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml) and
[`app/build.gradle.kts`](app/build.gradle.kts). Preserve the notices and license
terms supplied with those components when distributing builds.

| Component | Upstream |
| --- | --- |
| AndroidX and Compose | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin and coroutines | https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines |
| MediaPipe / YAMNet integration | https://github.com/google-ai-edge/mediapipe |
| sherpa-onnx | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime | https://github.com/microsoft/onnxruntime |
| LiteRT-LM | https://github.com/google-ai-edge/LiteRT-LM |
| Apache Commons Compress | https://commons.apache.org/proper/commons-compress/ |
| Gradle wrapper | https://github.com/gradle/gradle |

## Downloaded models

[`ModelCatalog.kt`](app/src/main/java/org/lsrith/acousticmonitor/models/ModelCatalog.kt)
contains the download URLs, expected sizes, and checksums. Weights are fetched on
the device and are not committed here. A runtime's license does not necessarily
cover its models. Check each upstream model's terms before use or redistribution;
commercial permission for Acoustic Monitor does not replace those terms.

The catalog includes YAMNet, Silero VAD, Whisper, Omnilingual ASR, WeSpeaker,
pyannote segmentation, toxic-bert, and LiteRT conversions of Qwen, SmolLM, and
Gemma models. Each model and conversion remains subject to its upstream terms.
