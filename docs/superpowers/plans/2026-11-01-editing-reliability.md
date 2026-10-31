# Editing Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve work and completed outputs, diagnose and recover remaining editing failures, and deliver a verified update APK.

**Architecture:** Extend the existing Android editor/export pipeline and local diagnostics; keep the native engine conservative and memory-bounded. Independent code domains use separate worktrees and are integrated by the controller. The user already authorized all feedback fixes and the existing APK deployment workflow.

**Tech Stack:** Kotlin/Compose, Media3 1.11.1, Rust/FFmpeg, Gradle9.4.1/AGP9.2.1/Kotlin2.3.21.

**Spec:** `docs/superpowers/specs/2026-11-01-editing-reliability-design.md`

## Global Constraints

- Android min API29/target36, arm64-v8a/x86_64; preserve original approvals, templates, artwork and precise boundaries.
- No silent HDR/resolution/quality change, no arbitrary codec retries, no publication after cancellation, no original deletion without Android approval.
- Diagnostic records contain enumerated/numeric facts only; no URI/name/path/content/raw exception text.
- Version0.4.14/code19; exact-main full CI including teardown/evidence before signing/publication; existing certificate only.
- Local repository history is a restored snapshot. Never push its synthetic history. Remote commits must use the real current main as parent, with non-forced expected-SHA ref updates.

## Review Focus

- Provider transient failure/cancellation while an older import or restore finishes: preserve saved edits and ignore stale callbacks (Task1).
- Journal fsync failure after publication versus before publication: retain the correct output/recovery handle and original consent (Task2).
- Codec error retry followed by cancellation, another codec failure or frame mismatch: bounded attempts, resources released, one publication (Task3).
- High frame count/B-frame analysis vectors and Source clones: fail before exceeding working-memory limits; fallback reason survives JNI (Task4).
- Preview idle-after-error and old diagnostic formats: real re-prepare, correct error domain, safe history and accessible UI (Task5).

### Task 1: Editor import and saved-source recovery

**Files:** EditorViewModel.kt; focused editor recovery/import tests; optional small editor-owned production policy helpers.
**Consumes:** MediaOperationDiagnostics and the stage names/signatures in the spec.
**Produces:** restoreRecoveryNotice/hasSourceReconnect/dismissRestoreNotice/reconnectSavedSource/cancelImport interfaces in the spec.
- [ ] Write regressions for transient unreadable restore preserving the snapshot, invalid replacement leaving it unchanged, valid replacement preserving all edit attributes, and cancelled/older import never mutating a newer session.
- [ ] Run focused tests against the baseline and record failure/limitations.
- [ ] Implement validated reconnection and cancellable, generation-fenced import; preserve existing successful workflows.
- [ ] Run focused tests and existing editor tests; commit only this domain; report exact tests and limits.

### Task 2: Preserve published batch outputs on journal failure

**Files:** GalleryWriter.kt, BatchViewModel.kt and publication/journal regression tests.
**Consumes:** Existing pending-output and journal contracts.
**Produces:** Publication completed despite a completion-record error; warning/recovery state prevents duplicate retry and unsafe source deletion.
- [ ] Add a fault-injection regression for failure specifically in onCommitted after IS_PENDING clears; keep pre-publication failure/cleanup assertions.
- [ ] Record red evidence where runnable.
- [ ] Repair the publication boundary and batch completion handling; preserve valid output and recovery handles.
- [ ] Test cancellation, failed pending journaling and post-publication recording failure; commit domain and report.

### Task 3: Bounded Media3 codec recovery

**Files:** VideoExporter.kt, ExactExportFrameGuard.kt, new focused retry policy/helper, retry tests.
**Consumes:** Existing ExportFailureEvidence fields (read-only; controller owns schema).
**Produces:** At most one additional attempt shared by frame and codec recovery; resource cleanup before new graph; original constraints retained.
- [ ] Add tests for recoverable/transient/resource errors, decoder recovery, permanent/permission/storage/cleanup errors, exhausted retries and cancellation.
- [ ] Observe meaningful baseline failure with the local pure runner where possible.
- [ ] Implement classified, cancellable recovery and release the failed Transformer before retry; never change output quality implicitly.
- [ ] Run recovery/frame guard tests and Android integration checks via CI; commit/report.

### Task 4: Native analysis working-memory bound

**Files:** Native adapter/core indexing/cache and targeted native tests; Kotlin FastExportReason mapping only if needed, coordinated with controller.
**Consumes:** Existing native unavailable/fallback result protocol.
**Produces:** A typed analysis-budget decline, fallible pre-growth allocation, retained small-source behavior and unchanged format safety guards.
- [ ] Add small-budget regression fixtures and long/high-frame-count allocation tests that exercise real admission/growth behavior.
- [ ] Record actual execution capability; do not claim native tests ran if Rust/dependencies are unavailable.
- [ ] Bound peak metadata work before allocations, including duplicated vectors/clones, rather than limiting only the completed cache.
- [ ] Run native tests in the available host or trusted CI; verify exact fallback/report mapping and commit/report.

### Task 5: Operation diagnostics and preview/UI integration

**Files:** ExportStage/DiagnosticStore/FailureEvidence/Codec, MediaOperationDiagnostics.kt, EditorPreviewCoordinator.kt, EditorScreen.kt, QuickCutApp.kt, ExportDiagnosticButton.kt, focused tests.
**Consumes:** Task1 interfaces and existing batch/export diagnostics.
**Produces:** Structured import/restore/preview/review diagnostics, truthful error messages, actual preview retry, cancel/reselect controls.
- [ ] Add tests for distinct playback/export error domains, history round trips/no private identifiers, error-to-reprepare at the current position and failed retry preservation.
- [ ] Implement shared diagnostic APIs first; integrate all call sites and accessible recovery controls.
- [ ] Exercise existing timeline/seek/lifecycle tests alongside the new regressions; verify failure controls on Android.
- [ ] Commit/report integrated domain; resolve cross-domain changes without removing other fixes.

### Task 6: Whole review, CI and updated delivery

**Files:** Release metadata/docs and source-reproducibility inputs if necessary.
- [ ] Review spec coverage and integrated diff, run all applicable local script/JVM checks and inspect native/source packaging constraints.
- [ ] Commit version0.4.14/code19, review all changes independently, synchronize source through GitHub tools with real-main parent and expected SHA.
- [ ] Require exact-main build and API29/35/36 workflows to finish successfully; inspect real failure logs before fixes/retries.
- [ ] Download matching unsigned/source artifacts, verify source identity, sign using existing key, verify signatures/metadata/ELF/ZIP alignment and source hashes.
- [ ] Save and publish signed APK/exact source/guide/checksums; verify published tag/asset hashes and provide actual download links. Keep physical failing-source/Fold8 validation status accurate.
