# Broader Exact Fast Export Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, followed by one fresh whole-branch reviewer.

**Goal:** Reduce unnecessary full-video encoding for ordinary phone videos.

**Architecture:** Extend the existing direct/native/Media3 paths. Isolate MP4 header
editing and native metadata checks, preserve transactional publication, and widen
codec support only with actual decoder evidence.

**Tech Stack:** Kotlin/Compose/Media3, Rust/JNI, FFmpeg, x264/x265, JUnit, Android instrumentation.

**Spec:** docs/superpowers/specs/2026-10-19-fast-export-coverage-design.md

## Global Constraints

- Exact requested coordinates; no keyframe snapping or hidden retained content.
- Preserve audio, orientation and original-date behavior before publication.
- Cancellation never triggers fallback. Original input is never modified.
- Explicit FAST_ONLY remains packet copying; automatic export may encode boundaries/audio.
- No universal speed or majority-coverage percentage claim without a user corpus.
- No release/version bump or main merge; existing PR #4 is the delivery target.

## Review Focus

- Old/future MP4 dates and malformed/64-bit boxes must not corrupt output.
- Rotated source transforms must survive without reflecting or double-rotating frames.
- HEVC B-frame dependency chains and parameter-set changes must decode completely.
- Reordered/repeated audio must restart source decoding while keeping output continuity.
- All-copy video must report actual processing and preserve exact audio boundaries.

### Task 1: Regressions and metadata paths

**Files:** NativeSmartCutInstrumentedTest.kt, android-emulator-check.sh,
VideoExporter.kt, ExportPlanner.kt, new Mp4HeaderDates.kt and its JVM tests,
native/quickcut-smartcut/src/lib.rs, avc1.rs, new display.rs, tests.rs.

**Interfaces:** Mp4HeaderDates.update(File, epochMs) edits checked private output;
native display helper copies only validated orthogonal matrices into final remux.

- [ ] Add Android original-date and rotated-export assertions using existing exporter APIs.
- [ ] Run them against the baseline; observe processing-method failures.
- [ ] Add and run pure MP4 date fixtures for v0/v1, oversized boxes, invalid structure and no media-byte changes.
- [ ] Implement date fast-path dispatch, staged header edits and native rotation preservation.
- [ ] Verify actual saved dates, orientation, packet bytes, frames and audio on Android.

### Task 2: Copy video while repairing audio

**Files:** native adapter/JNI, NativeSmartCutTrimmer.kt, NativeSmartCutPlan.kt,
vendor smartcut audio/cut modules if required, native and Android tests.

**Interfaces:** Native result includes actual copied/encoded picture counts and audio processing;
VideoExporter maps zero encoded video to AUDIO_REENCODE or ORIGINAL_COPY correctly.

- [ ] Add all-copy-range and audible reversed-range regressions; observe baseline failures.
- [ ] Permit verified copy-only plans, restart input audio decode on backward seeks without flushing output encoding continuity.
- [ ] Verify audio content at every join, copied VCL bytes, duration, cancellation and publication.

### Task 3: HEVC partial processing

**Files:** native adapter plus HEVC helper, upstream cut options only if needed,
native build/source/license scripts, pinned source manifest, native/Android fixtures.

**Interfaces:** Codec-specific validation dispatches H.264 to fixed avc1 and HEVC
to a validated Android-compatible configuration; both return actual packet statistics.

- [ ] Add an SDR Main HEVC/B-frame arbitrary-cut regression; observe the baseline fallback.
- [ ] Pin x265 source, build static host/Android libraries, include corresponding source/notices.
- [ ] Implement narrow HEVC validation, boundary encoding, output normalization and cleanup.
- [ ] Verify complete host and Android decoding, first/splice/last pictures, copied packets and sound.

### Task 4: Final verification and delivery

- [ ] Run full JVM/native/build/lint/Android suite and inspect actual result artifacts.
- [ ] Run one independent final code review; fix material findings with regression evidence.
- [ ] Document measured support and remaining limits, commit and update existing PR with final CI links.
