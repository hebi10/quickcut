# Review Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** Resolve the accepted 2026-10-23 feedback without relaxing export safety.
**Architecture:** Keep existing engines and extract narrow preview/recovery/diagnostic responsibilities. Preserve explicit consent and fail closed on uncertain outputs.
**Tech Stack:** Kotlin, Compose, Media3, Rust, FFmpeg, existing Gradle and GitHub Actions.
**Spec:** docs/superpowers/specs/2026-10-23-review-followup-design.md

## Global Constraints

- No new dependency, remote analytics, original deletion without fresh approval, or implicit video re-encoding.
- Preserve current visual design and existing API 29 minimum.
- Do not print credentials or sign with a replacement key.
- Tests must exercise production behavior; device-only validation remains explicitly pending.

## Review Focus

- Pause/split and composition replacement must not consume stale playback callbacks.
- Null provider responses, corrupt journals, and silent persistence failures must retain recovery handles.
- All-intra copy must pass without permitting unsafe boundary re-encoding or incompatible formats.
- Failed batch diagnostics must survive subsequent success and remain bounded/private.
- Large source and API/device coverage must not be represented as proof for the user's original file.

### Task 1: Preview synchronization and accessibility
- Files: ui/EditorScreen.kt, new preview coordinator, ui/ExportDialogs.kt, batch/BatchScreen.kt; related unit/instrumented tests.
- [ ] Write regression tests for pause/split, old composition positions, selection semantics.
- [ ] Extract and integrate preview coordination; fix selectable rows/groups.
- [ ] Verify focused tests and existing UI tests.

### Task 2: Conservative shared recovery
- Files: batch/BatchSessionStore.kt, batch/BatchViewModel.kt, editor recovery access/store helpers and tests.
- [ ] Add null query, corrupt journal, write failure, no-duplicate recovery tests.
- [ ] Preserve uncertain records and use verified atomic persistence/output access.
- [ ] Run recovery suites and review original deletion remains excluded.

### Task 3: Native eligibility and analysis
- Files: native/quickcut-smartcut and narrowly scoped native/vendor/smartcut core hooks; native tests.
- [ ] Add all-intra copy/suggestion and analysis behavior regression tests.
- [ ] Share eligibility checks, retain copy/encode distinction, reduce duplicate packet reads safely.
- [ ] Run full native suite with independent decoded-frame checks.

### Task 4: Diagnostic history and timing
- Files: media/ExportDiagnosticStore.kt, media/VideoExporter.kt, ui/ExportDiagnosticButton.kt, batch result integration; new focused model/tests.
- [ ] Add bounded history/failure retention/timing/privacy regression tests.
- [ ] Persist structured recent/failed diagnostics and expose per-operation IDs in result records.
- [ ] Run unit and instrumented persistence tests.

### Task 5: Validation and delivery
- Files: scripts/android-emulator-check.sh, .github/workflows, QA docs, version metadata if needed.
- [ ] Broaden regression/device-layout checks and add realistic large-source benchmark harness with measured stage output.
- [ ] Run builds/lint/native/Android tests on the final commit; inspect failures and output artifacts.
- [ ] Complete independent code review, publish source changes to a PR, report exact validation and remaining device limits.
