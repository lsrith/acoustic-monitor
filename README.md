# Acoustic Monitor

An experimental Android app for running audio models on the device, comparing
signals with a learned room baseline, and reviewing incidents when configured
thresholds are crossed. Audio processing runs locally; network access downloads
model files.

## What it does

- Classifies sound events with YAMNet.
- Detects speech and compares transcription models through sherpa-onnx.
- Measures vocal strain with signal processing, and supports speaker enrollment
  and diarization.
- Combines acoustic cues, baseline-relative loudness, language rules, and vocal
  strain into an inspectable incident score.
- Records incidents with contributing signals and short audio snippets for local
  review, labeling, and metadata export.
- Runs optional local language models to summarize signal timelines.
- Shows inference latency, capture status, model availability, and signal charts.

Monitoring view combines the signals. Signal walkthrough gives each analysis its
own page and limits expensive inference to the selected stage.

## Baselines and incidents

The room baseline estimates microphone level and spectral balance. Its confidence
rises with observation; large positive level deviations pause adaptation so a
loud event is not immediately learned as normal. Calibration can re-anchor the
baseline for a new environment.

The current fusion is deliberately simple: it compares the strongest weighted
acoustic cue, confidence-weighted level deviation, and a decaying language-rule
score against configured thresholds. Vocal strain adjusts acoustic cues; media
and laughter adjust acoustic thresholds. Persistence and sustained near-threshold
activity affect incident creation. This is a combination of baseline comparison
and heuristic rules, not a learned baseline for every model output.

An incident means the configured rules crossed a threshold. It does not establish
its cause, intent, or whether a situation is safe. Default weights and thresholds
are experimental. Language-model summaries can be wrong and do not drive the
incident decision. The toxicity classifier is available in the code but is not
currently connected to fusion.

## Build and install

Requires an Android SDK with platform 36, Java 25 for the configured Gradle daemon,
and an ARM64 Android device running Android 10 or newer. The sources target Java
17 bytecode. Set `sdk.dir` in an untracked `local.properties`, or configure the SDK
in Android Studio.

```sh
./gradlew :app:distDebug :app:testDebugUnitTest
```

The signed debug APK is written to `dist/acoustic-monitor-0.1.0-<commit>.apk`.
The build downloads and checksum-verifies the sherpa-onnx AAR. Model weights are
not included in the APK: download the required core models in the app and choose
optional models as needed. Some downloads are large; use Wi-Fi.

```sh
adb install -r dist/acoustic-monitor-0.1.0-<commit>.apk
```

Grant microphone and notification permissions, then start a monitoring session.
The application ID is `org.lsrith.acousticmonitor`. Retain the same local debug
keystore for future updates; signing keys are excluded from version control.

On Linux ARM64 hosts, Google's SDK resource compiler may require x86-64 emulation.
A compatible compiler can be selected with
`-Pandroid.aapt2FromMavenOverride=/absolute/path/to/aapt2`. On constrained hosts,
use `--max-workers=1` and a smaller Gradle heap.

## Data and limitations

Audio is processed in memory. Incident snippets, event metadata, downloaded models,
and enrolled speaker data are stored in app-private storage. CSV export writes
reviewed incident metadata locally; it does not upload recordings. Clearing events
removes their records and snippets. Uninstalling removes app data and models.

A build and passing unit tests establish compilation and tested rule behavior,
not on-device accuracy. Model speed, microphone processing, GPU support, and
background service survival vary by device. Long-running local language-model
inference has previously caused native process crashes; continuous operation
needs device testing.

## License

Copyright © 2026 Samrith Ly.

**Source-available for noncommercial use under the
[PolyForm Noncommercial License 1.0.0](LICENSE.md). Commercial use requires prior
written approval from Samrith Ly under separate licensing terms.** See
[commercial licensing](COMMERCIAL-LICENSING.md) for requests.

This is not an OSI open-source license. Third-party libraries, tools, and model
weights retain their own terms; see [third-party components](THIRD-PARTY.md).
