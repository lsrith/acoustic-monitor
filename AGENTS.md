# Development

Acoustic Monitor is an Android app for on-device audio analysis, baseline
comparison, and local incident review.

- Keep one microphone stream; attach analysis stages to the shared pipeline.
- Never fabricate model results or claim accuracy based on a successful build.
- Distinguish observed baseline deviations from conclusions about their cause.
- Keep audio, transcripts, and speaker data local. Network access downloads models.
- Preserve checksum verification for downloaded artifacts and model weights.
- Do not commit signing keys, device recordings, model weights, SDK paths, or APKs.
- Validate relevant changes with `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
- Original source uses PolyForm Noncommercial 1.0.0; preserve third-party notices.
