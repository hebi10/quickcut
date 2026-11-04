# QuickCut Play Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** Build signed APK/AAB and update existing QuickCut test tracks through GitHub Actions.
**Architecture:** Independent trusted-main Play pipeline reuses existing gates and native build; a bounded Publisher API client validates, uploads and commits test releases idempotently. Official Google auth supplies short-lived credentials.
**Tech Stack:** GitHub Actions, Kotlin/Gradle, Python standard library, official Google auth Action.
**Spec:** docs/superpowers/specs/2026-11-04-play-automation-design.md

## Global Constraints

- Package com.hebi.quickcut; existing pinned signing certificate and four QUICKCUT signing secrets.
- Current-source exact-main build and API29/35/36 smoke successes before publishing; preserve immutable GitHub releases.
- No production/open-test deployment, tester-list changes, credential output or app UI/engine changes.
- Same committed version in APK and AAB; Google test-track updates only; idempotent retries.
- Existing Google review must not be cancelled by automation.

## Review Focus

- Untrusted PR/workflow completion cannot authorize signing or publication.
- Wrong signer, package, native ABI or version fails before upload.
- Existing version, partial track promotion and commit timeout remain recoverable without duplicate upload.
- Unknown/internal/custom track IDs cannot redirect deployment to production or open tests.
- Missing credentials, review conflict and API errors remain clear failures, never claimed publication.

### Task 1: Pipeline and Publisher client

**Files:** Existing release gate/tests; new Play workflow, bundle verifier, Publisher uploader and focused tests; Android workflow test commands, emulator path filters, release setup docs.
**Interfaces:** Gate emits ready,sha,tag,version,version_code. Uploader consumes AAB path, exact package/version, short-lived bearer token via environment, configured closed track, target internal/closed/both; writes bounded nonsecret publication result.

- [ ] Read canonical main snapshots and run baseline Python gate tests.
- [ ] Add focused failing tests for Play gate mode, AAB checks, bounded track routing, duplicates and API failures.
- [ ] Implement minimal pipeline/helpers without modifying production app behavior.
- [ ] Run focused Python tests, shell/YAML validation and independent diff review.
- [ ] Publish reviewed branch and integrate authorized automation configuration.

### Task 2: Account connection and live verification

**Files:** Repository signing configuration and nonsecret Google identity settings; release setup documentation.
**Interfaces:** Existing signing key stays app-compatible; Google principal receives QuickCut test-track permissions only.

- [ ] Inspect existing signing secret names and app-scoped Play authentication; reuse existing credentials if available.
- [ ] Configure the authorized integration where current account access permits; request only truly missing user-held inputs after implementation is reviewable.
- [ ] Run Actions and inspect each gate/build/publication result; verify Play track state when access is available.
- [ ] Record actual readiness, successful checks and any remaining credential or Google-review blocker.
