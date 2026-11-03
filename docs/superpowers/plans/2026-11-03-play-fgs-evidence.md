# QuickCut foreground-service declaration

> Execute inline; review the complete capture branch before publication.

**Goal:** Resolve the Play Console foreground-service declaration with accurate API34 and API35 demonstrations.

**Architecture:** Capture real user-interface file selection and video export in disposable Android emulators. Preserve production source and the signed 0.4.17(23) bundle.

**Spec:** The current Play form requires video URLs for DATA_SYNC local transcoding/export and MEDIA_PROCESSING transcoding. ExportWorkService uses DATA_SYNC below API35 and MEDIA_PROCESSING from API35.

## Constraints
- Production source remains exactly main 1354bc5f551ebd2f6954cb492706e874abe9d601.
- Use only generated test media, no user media or credentials.
- Show app launch, explicit file selection, save action, background notification, completion and notification removal.
- Assert a real Media3 transcode, output validation, and foreground-service lifecycle; do not substitute mocked work.
- Capture-only branch; no new app version or signing changes.

## Tasks
- [ ] Add a recording instrumentation scenario and a bounded API34/35 capture workflow reusing provenance-checked native artifacts.
- [ ] Verify compilation and both real device scenarios, inspect the videos and obtain a fresh review.
- [ ] Save the demonstrations, provide reviewer-accessible video links, save the declaration and recheck the release error.
- [ ] Update the durable deployment checkpoint with the actual final Play state.

## Review focus
- File selection must operate the actual Android document picker, with no fabricated activity results.
- Export must start through actual app save controls and produce a validated output.
- A recording must not be accepted if the scenario fails or no foreground notification is observed.
- Native artifacts and production code must match the already-tested release source.
- Only synthetic demonstration videos may receive reviewer access.

## Execution record
- Static workflow/shell validation passed. The first CI build compiled the capture test successfully on API35.
- Fresh review found two Important evidence gaps: early recorder exit and unchecked runtime service type/lifecycle.
- Fixed recorder exit validation after a regression reproduced false acceptance; all three disposable-process flow tests now pass.
- Added real foreground service/type checks (API34 DATA_SYNC; API35 MEDIA_PROCESSING) and service disappearance checks. Production source remains unchanged.
- The first complete capture attempt failed at document selection on both APIs; the API35 movie shows both fixture items present. Use real bounded long-press/tap gestures on the observed accessibility item bounds instead of requiring DocumentsUI recycler items to expose a long-click action. Short fixture names avoid ambiguous truncated titles.
- API34 denied the shell recorder's /sdcard output; record in shell-owned /data/local/tmp instead. API35 system diagnostics add a 0x prefix to the foreground type, verified against AOSP source; accept both hexadecimal renderings.
- The second capture's accessibility diagnostics identified a Pixel Launcher ANR modal blocking the document picker on both APIs. Increase emulator RAM to3072MB and dismiss only that exact unrelated launcher startup dialog; a QuickCut ANR is never dismissed or accepted.
- The next recording reached file items but opened the system Photos preview instead of selecting files. Accessibility text search also returns preview controls whose descriptions contain the filename; require the exact filename text node before any physical item gesture.
