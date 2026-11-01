# QuickCut 0.4.15 — batch usability and fast-save recovery

The user requested six changes: original-date save defaults; the label 앞뒤 구간 자르기; recovery from ReencodeRequiredException; both recommended and selected save actions in the fixed review footer; dropdown option selection; and modal batch-template management.

## Scope and behavior

- New/unset or invalid save-date preferences default to ORIGINAL. Preserve explicit stored CURRENT choices and restored job dates. Templates retain their existing edit-only format; date and confirmed original handling remain shared preferences.
- Rename the remove-edges editing option and associated trim descriptions to 자르기 without changing retained intervals or user-created template names.
- Use a shared accessible dropdown for edit mode, save date, original handling and per-file review choices. Keep destructive enable confirmation and reencode consent explicit.
- Open template selection, creation/update and removal in a bounded vertically scrollable modal. Preserve draft values, selection, 30-template limit and checked persistence. Show selected template summary outside it; remove horizontal chips.
- Keep recommended fast-save and selected-save actions together in the review footer, with wrapping/stacking on narrow or large-font screens. Keep requested/recommended interval preview and explanations visible in the content. No button may bypass preview/consent or include a known rejected fast attempt.
- A runtime fast-save refusal is recoverable, not repeated indefinitely. Bind a structured persisted refusal marker to the original reviewed request and relevant source/date/settings context. The same request must offer original exact intervals with explicit reencode consent, excluding the failed recommendation. Changed edit requests may be assessed again. No silent reencode, original deletion, duplicate publication, or clearing uncertain pending output without verification.
- Show a meaningful Korean reencode-required explanation while retaining machine-readable diagnostic classification.

## Verification and delivery

Regression coverage must exercise dropdown selection, disabled controls, original-action confirmation, template modal selection/save/remove, narrow/large-font footer access, fast refusal retry and recovery persistence/output safety. Existing native, HDR, background and publication checks remain intact. Production Android builds use pinned versions and the exact-source API 29/35/36 matrix before signing with the existing certificate and publishing the authorized APK release.

The user's specific failing video is unavailable; do not claim it was reproduced or that all sources can be cut without encoding.
