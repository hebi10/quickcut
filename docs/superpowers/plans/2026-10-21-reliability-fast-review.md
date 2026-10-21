# Reliability and Fast Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Independent file domains use superpowers:dispatching-parallel-agents; the controller integrates shared interfaces.

**Goal:** Complete the approved 0.4.10 recovery, analysis, precision, preview, batch, diagnostics and release improvements.

**Architecture:** Preserve the existing export pipeline and user consent. Add precise time values and bounded native analysis reuse, persist single-export recovery, and compose file-specific decisions using immutable reviewed snapshots.

**Tech Stack:** Kotlin, Compose, Android Media3 1.11.1, Rust/FFmpeg; no new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-21-reliability-fast-review.md`

## Global Constraints

- Existing dark UI, API 29 minimum, original retention and separate deletion approval.
- No network permissions, source-content telemetry or signing secrets in source.
- Recommendations only move retained boundaries inward and leave at least 100000 us.
- Fast consent never allows video encoding. Restore no destructive consent after process death.
- Final version 0.4.10/code 15; same signing certificate; physical-device limitations remain explicit.

## Review Focus

- Process dies after publish but before completion: adopt the validated output without exporting twice (task 2).
- An fd number is reused or same-size source is modified: no stale analysis result (task 3).
- Different microsecond bounds share displayed milliseconds: consent and output retain exact coordinates (task 1).
- Settings/files change while batch review/preview is open: no old choice applies (task 4).
- Codec cannot preview a boundary: explain it and leave consent/exact-save controls available (task 5).

### Task 1: Precise time foundation (controller)

Files: `editor/EditTimeline.kt`, `media/FastCutSuggestion.kt`, `media/CompositionFactory.kt`, `media/FastMp4Trimmer.kt`, `media/NativeSmartCutPlan.kt`, `ui/FastCutSuggestionText.kt`, related JVM tests.

Interfaces: produce the `PreciseClipRange`, `VideoClip` us properties and `FastCutSuggestion.createUs` API in the spec; Media3 source confirms `setStartPositionUs`/`setEndPositionUs` exist.

- [ ] Add tests for a 333333-us safe boundary, inward-only constraints, stale consent and explicit edit invalidation.
- [ ] Run pure JVM tests and observe failure; implement us-preserving getters/range updates and copy/Media3 conversion.
- [ ] Run tests and commit foundation.

### Task 2: Single export recovery (storage worker)

Files: `media/ExportStagingFiles.kt`, `editor/EditorViewModel.kt`, `editor/EditorSessionStore.kt`, new recovery store/coordinator and JVM/Android tests. Do not edit `VideoExporter.kt`, `QuickCutApp.kt`, native or batch files.

Interfaces: consume `VideoExporter.onOutputCreated/onCommitted`; expose `isRecoveringExport`, recover completion through existing model completion state; persist precise range fields from task 1.

- [ ] Add failing dated staging cleanup test and recovery fixtures for pending/published/foreign/uncertain URI states.
- [ ] Implement atomic journal before writing, validated adoption or owned-output cleanup, and retry block on uncertain cleanup.
- [ ] Run available tests; document Android coverage to run in CI; commit only owned files.

### Task 3: Native analysis and candidates (engine worker)

Files: `native/quickcut-smartcut/**`, minimal core additions if needed, `media/NativeSmartCutTrimmer.kt`, `media/ExportPlanner.kt`; associated native/Android tests. Do not edit shared time model or UI.

Interfaces: consume `clip.startUs/endUs`, `createUs`; produce `suggestCopyRangesUs(...onProgress)` and `ExportPlanner.analyze(...onProgress)`.

- [ ] Add regression tests for cache reuse/mutation/fd reuse/cancellation and non-integer-ms/fallback safe candidates.
- [ ] Implement bounded cache of fully checked source data keyed by stable file metadata; revalidate identity before/after reuse. Never cache cancellation or open fd paths as identity.
- [ ] Separate global packet validation from per-range checks; use the same successful analysis at save, while retaining output count and read failure checks.
- [ ] Search inward candidates safely with bounded work and useful progress; preserve exact bounds and existing rejection safeguards.
- [ ] Add realistic audio/long timestamp coverage, run available tests, commit owned files.

### Task 4: Per-file batch decisions (batch worker)

Files: `batch/BatchFastAdjustmentPolicy.kt`, `batch/BatchViewModel.kt`, `batch/BatchScreen.kt`, new batch choice policy and tests. Do not edit preview implementation, engine or editor files.

Interfaces: consume complete `FastCutSuggestion` us snapshots; call shared `FastCutPreview(suggestion)` (controller supplies). Produce immutable reviewed per-URI EXACT/RECOMMENDED/SKIP choices and per-file ExportMode execution.

- [ ] Add tests for mixed exact/recommended/skipped files, incomplete/foreign choices, source changes and precision.
- [ ] Implement selection UI with exact default, explicit recommendation consent, clear counts, and preserved bulk shortcuts.
- [ ] Preserve invalidation, recovery, original deletion scope and successful-output deduplication; run tests and commit owned files.

### Task 5: Preview, progress and diagnostics (controller)

Files: new `ui/FastCutPreview.kt`, `ui/ExportDialogs.kt`, `ui/QuickCutApp.kt`, new media diagnostic store, `media/VideoExporter.kt`, related tests.

- [ ] Add preview-policy tests for clamped boundary windows and diagnostic redaction; implement accurate-frame comparison and short boundary/join playback with lifecycle cleanup.
- [ ] Wire analysis progress, phase-aware export reporting and local diagnostic view/copy controls. Bound record size and omit path/URI/file content/raw sensitive exception strings.
- [ ] Add Android consent/preview/diagnostic regressions and verify with whole-app CI.

### Task 6: Integration and release (controller)

- [ ] Review all worker changes with a fresh reviewer and fix material findings.
- [ ] Update README, QA instructions, version/code and upgrade fixture; run JVM/native/Android/lint gates on final source tree.
- [ ] Merge verified changes to main without overwriting remote work; verify release tree identity.
- [ ] Sign with existing certificate, publish APK/corresponding source and download/hash-verify assets; deliver links.

## Execution record

- Baseline: clean `db7093b`; v0.4.9 has 117 JVM + 123 Android + 63 native passing tests. Review reproducer confirms dated staging cleanup omission.
- Existing linked worktree reused on `feat/reliability-fast-review-v0410`.
- User explicitly authorized the full feedback, including base-branch cleanup; proceed through implementation/release without redundant approval prompts.
