# QuickCut Play Registration Implementation Plan

> For agentic workers: Use subagent-driven-development for implementation and review. Steps use checkbox syntax.

**Goal:** Prepare an accurate, signed Play bundle and complete the user's Play registration.
**Architecture:** Existing offline Compose app with bundled privacy text, public matching policy, unchanged native engine. Existing CI gains unsigned AAB output alongside all existing gates.
**Tech Stack:** Kotlin2.3.21, Compose, AGP9.2.1, Gradle9.4.1, Media3 1.11.1.
**Spec:** docs/superpowers/specs/2026-11-02-play-registration-design.md

## Global Constraints
- Canonical base3cad2ab71e3d4778f728eb7e007eb23597d0d1e2; restored local baseline tree must match ebbd16c951978ceb61a38de36a4c84c684860f57.
- Version0.4.16/code21, packagecom.hebi.quickcut, min29/target36, both existing ABIs.
- Preserve existing preferences/export/approval behavior. No SDK or INTERNET permission additions.
- Public support emailasdlkj0104@gmail.com; policy effective2026-11-02.
- Sign certificate64d8bb097fa2fb39401c5a01026e6fc8d36f5136d5684732c1f310e6affb2d67.
- Never push synthetic local ancestry. Sync source with GitHub plugin create_tree/commit using real remote parent and non-force lease.

## Review Focus
- Privacy readable offline and on large font/small screen, close restores home.
- Local persistence and gallery deletion distinctions remain accurate.
- Existing licenses remain accessible, GPL source location directly discoverable.
- Bundle artifact actually contains both native ABIs and correct version; no private data included.
- Store declarations match final manifest and data behavior; Google approval/production access must not be invented.

### Task1: Privacy and source access
- [ ] Add bundled plain-text privacy policy and public matching policy document with all spec disclosures.
- [ ] Add accessible home privacy action and scrollable offline modal with email/source URL as selectable text.
- [ ] Add exact public repo/release source retrieval guidance in existing license modal without removing license access.
- [ ] Add focused device check for privacy open, readable policy/support/source and close at normal/largefont; record baseline missing entry when possible.
- [ ] Review source and commit.

### Task2: Play build delivery
- [ ] Set0.4.16/code21; add factual release notes.
- [ ] Add bundleRelease to Android build and artifact upload, retain all existing checks.
- [ ] Check workflow config and build CI, unit/lint/APK/AAB and relevant device checks.
- [ ] Verify AAB contents/metadata, sign with existing key through local secret-safe helper, validate JAR signature/cert and both ABIs.

### Task3: Store preparation and registration
- [ ] Create app using approved declarations, Korean/free, exact package; verify created app.
- [ ] Populate accurate Korean descriptions, support email/category, public privacy URL, data safety, ads/access/target/content/FGS forms.
- [ ] Prepare required branded graphics and real app screenshots; upload only verified public assets.
- [ ] Upload signed bundle; configure signing continuity with necessary action-time confirmation for private key transfer.
- [ ] Resolve all actionable validation errors, submit only if allowed by actual account. Report testing/identity/legal blockers accurately.
- [ ] Save final browser proof and completion checkpoint; never claim public availability before Google approval.
