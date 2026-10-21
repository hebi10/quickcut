# Broader exact fast export

User-approved goal: increase the range of ordinary phone videos that can be saved
quickly, retaining requested cuts, sound, orientation, dates, and playback compatibility.
This extends the existing export pipeline; it does not promise that every input or
device can export at file-copy speed. No source media corpus was provided, so no
percentage of all user videos will be claimed.

## Design

- Keep direct packet copying first, native smart cutting second, and exact Media3
  export as the fallback. Cancellation, pending MediaStore publication, recovery,
  original-file consent, and the explicit legacy FAST_ONLY API retain their meaning.
- Permit original-date exports on verified fast paths. Modify only MP4 header dates
  after muxing, before validation/publication; preserve encoded media bytes. Inspect
  every target header before modifying any. Unsupported layouts fall back safely.
- Preserve orthogonal display rotation during native processing without rotating or
  re-encoding every picture. Reject reflection, scale, perspective, and uncertain
  transforms. Verify source and saved orientation and decoded frames.
- Allow a native all-video-copy plan with exact audio re-encoding, reporting the
  actual video/audio work instead of forcing video encoding just to qualify.
  Fix chronological audio decoder state when retained ranges revisit source time,
  with audio-content regressions before enabling those ranges.
- Add a pinned, reproducible 8-bit x265 build and a narrow HEVC Main/SDR native path.
  Use HEVC in-band parameter sets only if Android's extractor/decoder accepts the
  produced stream; exercise first, splice, copied, and last frames on Android.
  Keep HDR, 10-bit and uncertain configurations on the exact fallback until proved.
- Every new path must validate actual packet counts and copied video payloads. A
  successful mux alone is insufficient evidence of retained video or sound.

## Acceptance

Real Android exports must demonstrate original-date H.264 partial processing,
rotated H.264 processing, HEVC arbitrary-boundary partial processing, all-video-copy
with exact sound, and audible reordered ranges without silent gaps. Native host
checks must decode all retained frames and verify unchanged copied payloads.
Full JVM/native/build/lint/emulator gates must pass on the final source tree.

No release/version bump or main merge is requested. Update the existing PR #4.
