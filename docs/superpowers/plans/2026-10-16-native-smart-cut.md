# Native exact-position smart cut

## Goal and authorization

Implement the user's approved public-engine alternative in QuickCut. For eligible MP4/H.264 single clips, encode the boundary portions and copy complete middle GOPs without changing the requested trim. Keep the existing exact export fallback and background export service. Deliver a verified, fixed-key signed APK and update PR #4; do not merge or contact a vendor.

This is a bounded first integration, not a promise that every video can be processed quickly. Source: DeepRegular/SmartCut at `510ee91614a29f9c7fd6a446b75e9767f0df3d8a`, FFmpeg 7.1 series and libx264. Pin all native inputs and include their licenses and corresponding source with the binary delivery.

## Architecture and constraints

- Keep the existing safe, lossless `FastMp4Trimmer` path first. `FAST_ONLY` continues to mean no video re-encoding.
- Before the Media3 fallback, try the native path for a single clip saved with the current date. The initial native adapter accepts only verified MP4, progressive SDR 8-bit H.264 with usable IDR boundaries and supported AAC/no audio. Unsupported metadata, rotation, additional tracks or unsafe plans decline to the existing exact path.
- Use `plan_range`, not upstream `plan_on`'s time-grid normalization. Require the returned plan bounds to equal the requested microseconds and require a real copy region and actual IDR at its start. Reject altered bounds; never hide deleted material with an MP4 edit list.
- Add a strict native mode: decoder/read failures or discarded requested pictures cannot be reported as success. Count successfully written copied and encoded video packets. Report partial re-encoding only after validation and gallery publication.
- Audio uses sample-accurate trim and AAC re-encoding. Muted clips contain no audio. Audio work is separate from the copied video middle.
- Keep a seekable Android file descriptor open throughout the blocking JNI call and use its local `/proc/self/fd` path. Decline providers with offsets or non-seekable input rather than duplicating a potentially huge source into cache.
- Native work runs on IO, checks cancellation during scan and cut, and writes only a unique cache file. Progress is dispatched to Main. Cancellation waits for native cleanup before releasing the descriptor or deleting the temporary file.
- Native decline/failure can fall back only before gallery publication. The existing single publication, recovery, original deletion, date and background-service contracts remain in force.
- Build arm64-v8a and x86_64 from pinned sources with 16 KB ELF alignment. No undocumented proprietary API, downloaded unverified binary, network-enabled media protocol, or silent missing-library success path.

## Ownership and work sequence

1. **Native feasibility/build:** compile the pinned headless Rust core with FFmpeg/libx264 for Android; record dependency hashes and reproducible commands. Establish real host output for 1.95–5.5 s and 3–7.5 s.
2. **Regression tests first:** add Android tests through the existing `VideoExporter` API. Observe current whole-reencode behavior failing the new partial/copy assertions before integration. Native strict-mode guards also require negative tests before implementation.
3. **Native adapter:** isolate desktop playback, add strict errors and actual packet counters, expose JNI with cancellation and progress callbacks. Reject unsupported inputs and exactness violations.
4. **App integration:** add Kotlin descriptor/cancellation bridge and try-native-before-Media3 flow. Keep publication outside fallback catches. Update version, native build inputs, notices and source-delivery scripts.
5. **Verification:** host native tests; Android unit/lint/build; on-device decode, packet-copy, audio, near-keyframe, fallback and cancellation checks; existing background and recovery regression suite. Independent code review of cancellation, JNI lifecycle, exactness and gallery ownership.
6. **Delivery:** download verified CI artifacts, inspect native ELF alignment, sign with the existing release key, verify certificate/version/non-debuggable APK and ship corresponding source. Upload APK to the previously authorized location, update PR with actual evidence, leave it open.

## Concrete acceptance tests

- External x264 `quickcut_exact_boundary.mp4`, 1.95–5.5 s: first retained picture remains red, duration is within one 30 fps frame, report is partial, and VCL payloads of the complete 2–4 s GOP are copied.
- External x264 `quickcut_keyframes.mp4`, 3–7.5 s: first picture corresponds to 3 s, complete 4–6 s GOP is copied with correct presentation timestamps, duration is 4.5 s within one frame, audio is playable through its expected end.
- Near-keyframe 1.98 s and 2.01 s: no unauthorized shift to a keyframe; a declined native plan uses exact fallback and reports the actual method.
- Unsupported and multi-clip input: valid exact fallback; one output-created callback, one commit and one report; input timeline unchanged.
- Cancellation: no published partial output, descriptor/temporary file cleaned up after native termination, no automatic restart into Media3.
- Previously tested Home/other-app/screen-off exports, update/data retention and original-file protection continue to pass.

## Review focus and remaining uncertainty

Upstream tolerates some damaged data and adjusts some planning bounds. Its success result alone is insufficient. The strict wrapper and tests must prove the bounded path before it is enabled. Android decoding across parameter-set changes, AAC timing, native cancellation and source completeness are release gates. Short generated fixtures establish correctness of those conditions, not long 4K device performance or universal format support.
