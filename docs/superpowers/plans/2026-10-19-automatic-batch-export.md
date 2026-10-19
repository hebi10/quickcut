# Automatic batch saving and clear duration input

**Goal:** Save every valid batch item at the requested cut coordinates using the fastest supported exact path, and make batch durations easy to understand and enter.

**Architecture:** Keep the existing export queue and publication transaction. The batch screen offers automatic saving as its normal action. Extend the native smart-cut adapter to process several ranges from the same source in one indexed pass; other timelines retain the existing Media3 fallback. Represent minute/second edits in the existing text draft and persist milliseconds without a settings migration.

**Tech stack:** Kotlin, Jetpack Compose, Media3, Rust/JNI, FFmpeg/x264, JUnit and Android instrumentation.

**Spec:** User-approved automatic fallback, multi-range optimization, and simpler batch time inputs (2026-10-19).

**Global constraints:** Never move a requested cut to a keyframe. Never skip an otherwise valid item solely because fast processing is unavailable. Preserve cancellation, original-date behavior, pending-output cleanup, recovery, and original-file consent. Do not claim universal fast export. No dependency upgrade or release in this change.

**Review focus:** Range order and picture retention; audio at joins; safe fallback and publication; minute/second precision, inactive fields, draft restoration; UI validation and accessibility.

## Tasks

1. Add behavioral regressions for automatic batch review, mixed batches, joined-range exports, and minute/second entry. Observe baseline failures.
2. Implement bounded, decimal-safe duration parsing, contextual minute/second controls, inline errors and a live edit summary. Preserve existing stored durations and disabled input values.
3. Make automatic saving the batch action. Keep the legacy explicit fast-only API for compatibility, but remove skipping/position-shifting alternatives from the normal batch screen.
4. Pass eligible same-source timelines to one native multi-range operation. Validate every plan's exact bounds, count retained packets across all ranges, allocate distinct parameter sets across segments, and retain verified fallback for unsupported cases.
5. Run pure JVM tests, native host codec tests, Android build/lint and emulator regressions. Inspect results and correct concrete failures.
6. Perform one independent final code review, fix supported findings, update documentation, and push the verified result to the existing development PR.
