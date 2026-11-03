# QuickCut Editor Design Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the approved UI feedback and deliver actual emulator screenshots.

**Architecture:** Keep existing Compose screens and model callbacks. Adjust presentation at shared dropdown, batch form, and editor boundaries without changing media or persistence behavior.

**Tech Stack:** Kotlin, Jetpack Compose Material3, Media3, Android instrumentation, GitHub Actions.

**Spec:** docs/superpowers/specs/2026-11-03-editor-design.md

## Global Constraints

- Preserve dark green/mint colors, existing typography accessibility and minimum 48dp touch targets.
- Preserve exact ms trim boundaries, save authorization, original deletion confirmation, recovery and lifecycle behavior.
- Do not change dependencies, native code, version, signing or Play settings.
- Production changes are limited to the approved UI; actual screenshots must come from an Android emulator.
- Use the existing current-main tree a44ac6e4f7eecf012922ccfea17b6b632b648feb; local synthetic history must never be pushed.

## Review Focus

- Narrow/large-font dropdown anchors and selected/disabled semantics remain usable.
- Empty/importing/recovery batch states retain the same save gating and explain disabled state.
- Destructive save preferences still wait for explicit confirmation.
- Quick trim display never overwrites sub-display-precision boundaries.
- Preview pause/seek/lifecycle and timeline zoom/virtualization retain their behavior.

## Task 1: Shared choice hierarchy

Files: ui/CutChoiceDropdown.kt; existing SaveOptionsAccessibilityInstrumentedTest.kt.
- [ ] Replace primary-looking choice buttons with secondary selectors, align popup width and show a selected check.
- [ ] Preserve public signature, caller modifier, current-value descriptions, disabled behavior and 48dp targets.
- [ ] Verify existing accessibility/selection instrumentation and inspect narrow/large-font popup captures.

## Task 2: Batch hierarchy and density

Files: batch/BatchScreen.kt and affected batch UI assertions.
- [ ] Prioritize add-video in empty state; put count and disabled reason near the corresponding action.
- [ ] Reduce duplicated input hints, spacing and nested containers; retain storage warnings and confirmation callbacks.
- [ ] Normalize header, sound state and save wording; retain all running/recovery controls.
- [ ] Verify existing batch tests and capture empty/selected/options/menu views.

## Task 3: Editor clarity

Files: ui/EditorScreen.kt, QuickTrimScreen.kt, TimelineStrip.kt and affected UI assertions.
- [ ] Normalize save/sound labels and top bars; make timeline zoom purpose explicit.
- [ ] Unify preview controls/time presentation, show original retained interval, preserve precision/lifecycle callbacks.
- [ ] Balance preview/control spacing without fixed-height clipping under narrow/large-font configurations.
- [ ] Verify existing trim/timeline/lifecycle tests and capture quick/detailed screens.

## Task 4: Emulator evidence and review

Files: one focused design instrumentation class; existing CI capture artifacts.
- [ ] Capture the real composables with a deterministic existing video fixture, including large-font/narrow geometry checks.
- [ ] Run existing unit/Lint/build and API29/35/36 emulator suites using a work branch and draft PR where CI execution is required.
- [ ] Independently review the final diff and inspect actual screenshot artifacts; fix verified issues.
- [ ] Save the final screenshots as deliverables and report validation and repository state.
