# Background and editing preferences implementation plan

> For agentic workers: apply test-driven-development, parallel independent changes, then a fresh whole-branch review.

**Goal:** Preserve confirmed editing choices and avoid unnecessary background codec competition while recording actionable private diagnostics.
**Architecture:** Reuse Application-owned models, existing foreground lease, TemplateStore and SharedPreferences. Separate per-export deletion candidates from persisted preference. Preview resources follow visible lifecycle; export continues independently.
**Tech Stack:** Kotlin, Compose, Media3 1.11.1, Android API29–36; no added dependencies.
**Spec:** docs/superpowers/specs/2026-10-30-background-and-preferences.md

## Constraints and review focus
- No arbitrary exception messages, URI/path/file names or remote transmission in diagnostic records.
- Only newly published successful outputs can arm original deletion. Recovered output must never cause automatic deletion.
- Save-as-new cannot overwrite selected preset; 30 item limit and persistence failure behavior remain.
- App return must restore paused preview position without reacquiring preview codecs while background export remains active.
- Recommendations require visible adjusted range approval; exact fallback remains available.

## Task 1: Editing preferences and presets
Files: QuickCutSettings.kt, ui/QuickCutApp.kt, ui/ExportDialogs.kt, batch/BatchScreen.kt, batch/BatchViewModel.kt, editor/EditorViewModel.kt, BatchExportChoicePolicy.kt and focused settings/batch/UI tests.
- [ ] Add regression assertions first for original-action retention, save-as-new and recommended defaults.
- [ ] Record baseline regression failures using available Android/JVM CI.
- [ ] Persist confirmed OriginalAction; route enable confirmation before selection becomes effective; retain OS confirmation for newly published candidates.
- [ ] Expose bottom presets, explicit new-copy/update; choose validated recommendation by default and emphasize corresponding save action.
- [ ] Verify focused cases and full existing suites.

## Task 2: Background preview resource ownership
Files: ui/QuickTrimScreen.kt, ui/EditorScreen.kt, ui/FastCutPreview.kt, ui/EditorPreviewCoordinator.kt and preview lifecycle tests.
- [ ] First reproduce that background preview stays allocated and add position-preservation lifecycle assertions.
- [ ] Stop/release playback resources when not visible, preserve positions, prepare only upon return and never auto-play.
- [ ] Exercise Media3 fallback after genuine progress, then background/screen off, validate publication/duration/audio and cancellation cleanup.

## Task 3: Processing stages and private failure evidence
Files: media/VideoExporter.kt, ExportStage.kt, ExportDiagnosticStore.kt, ExportDiagnosticCodec.kt, ExportDiagnosticHistory.kt and diagnostics/stage tests.
- [ ] Add failure-code/engine privacy and stage-reset regression tests first.
- [ ] Capture allowlisted Media3/codec facts and lifecycle/service interruption category; no raw messages.
- [ ] Label native/Media3/actual encoder phases accurately; reset percentage at transitions and bounded existing retry.
- [ ] Preserve final ExportReport and output validation; no blind timeout increases or retries of generic IO errors.

## Task 4: Integration and verification
- [ ] Run repository Python checks and diff validation locally; Android SDK/Gradle/Cargo are currently absent locally.
- [ ] Use a development branch/draft PR for Android/JVM/emulator CI, preserving actual remote main parent; never push imported local history.
- [ ] Fresh independent review, address material findings, then commit verified changes and report exact validation/remaining device limits. No version bump or automatic release solely for this improvement request.
