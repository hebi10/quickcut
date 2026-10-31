# Editing reliability follow-up

The user approved implementing all findings from the 0.4.13 review. Improve successful editing and useful recovery while preserving original media and the Pocket Cut Studio UI. The exact failing user video and physical Fold8 remain unavailable; never claim that failure reproduced or fixed without evidence.

## Approach

Keep Kotlin/Compose, Media3 1.11.1 and the pinned Rust/FFmpeg engine. Repair state/publication errors, extend existing private local diagnostics, add one classified codec recovery attempt, and bound native analysis before allocations grow. This is the builder's implementation choice. A full universal FFmpeg transcoder and automatic quality/HDR reduction would materially change behavior and are outside this bug-fix release.

## Required behavior

- A temporarily unreadable saved source must retain the complete saved timeline. Retry and explicit source reselection/reconnection preserve clip IDs, exact ranges, order, mute state and editor position. Validate the replacement before persisting or applying it.
- Import can be cancelled immediately at the UI. Late results cannot replace a newer edit, restore a cancelled import, or erase an existing session. Blocking provider calls may finish later; cancellation must fence their effects.
- Record loading, restoration, preview and export-review failures in bounded on-device diagnostics. Never persist original URI/path/name/content, raw exception messages or stack traces. Preserve existing export error codes and add a distinct playback error-code field.
- Preview retry must prepare the current composition again, retaining the requested edit position. Existing pause, seek, lifecycle release and stale-composition protections remain.
- After successful gallery publication, an onCommitted journal failure must preserve the valid output and its recovery handle, report completion with a recording warning, block unsafe duplicate retry, and keep original-deletion consent safe. Before publication, journal failure still aborts and cleans owned pending output.
- Media3 recovery is at most one additional processing attempt across frame-loss and classified codec recovery. Retry only recoverable/transient/resource failures or a specific decoder-processing failure that has a software-decoder recovery path. Never retry permission, malformed/unsupported input, storage, validation, cleanup or cancellation errors. Release failed processing resources before retry; use a new graph/output. Keep cut boundaries, audio, dates, HDR/quality contract and publication-once semantics. No silent format/resolution/HDR downgrade is introduced.
- Native initial analysis has a conservative working-memory budget checked before growing metadata vectors; use fallible reservations. Exceeding the budget returns a typed unavailable reason before output/publication and lets the existing explicitly allowed exact path proceed. The completed-cache limit is not a working-memory limit. Tests must trigger a small injected budget without giant fixtures and exercise long/high-frame-count inputs where feasible.
- Preserve current renderer artwork, original approval persistence, templates, native format guards, existing test assertions, exact-frame validation, min API29/target36 and arm64-v8a/x86_64.
- Release 0.4.14/versionCode19 with the existing certificate only after exact-current-main Android build and API29/35/36 smoke workflows fully succeed, including teardown/evidence. Signed APK plus exact corresponding source, guide and SHA256SUMS remain required.

## Shared interfaces

The controller owns `MediaOperationDiagnostics` and diagnostic schema/UI integration. Add `ExportStage.IMPORTING`, `RESTORING`, `PREVIEWING`, `REVIEWING`.

```kotlin
internal object MediaOperationDiagnostics {
    fun begin(context: Context, stage: ExportStage, sourceCount: Int = 0): ExportDiagnosticSession?
    fun recordFailure(context: Context, stage: ExportStage, error: Throwable, sourceCount: Int = 0): String?
    fun message(stage: ExportStage, error: Throwable): String
}
```

Diagnostic session calls are optional: `session?.finish("완료"/"취소"/"실패", error)` must never gate the actual operation. Existing report capture/schema is extended compatibly; unknown historical fields remain safe.

Editor model owner exposes `restoreRecoveryNotice: String?`, `hasSourceReconnect: Boolean`, `fun dismissRestoreNotice()`, `fun reconnectSavedSource(context: Context, replacement: Uri)` and `fun cancelImport()`. Existing `restoreSavedSession(context)` retries preserved state. Restore failure uses the dedicated notice rather than overlapping the generic error dialog. Controller wires the OpenDocument launcher, retry/reselect dialog and importing cancel/back control.

## Verification

Write regressions before each fix and record actual red/green evidence where a runnable toolchain exists. Cached standalone Kotlin/JUnit supports pure tests; full Android compilation/instrumentation and native dependency integration use trusted GitHub CI. Do not downgrade the app toolchain or skip assertions to make local checks pass. Independently review each domain and the final integrated diff. Record toolchain limits honestly.
