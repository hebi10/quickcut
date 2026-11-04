# Scenic store captures

The original generated coastal image is `scenic-coast.png`. It is converted locally
into a new eight-second baseline H.264/AAC video with a gentle camera movement,
1280×720 resolution, 30 fps, and no B-frames. No external
media download or test-pattern source is used in this capture workflow.

The workflow `store-scenic-capture.yml` runs only
`StoreScenicCaptureInstrumentedTest` on API 29. It imports the actual video through
`EditorViewModel`, enters the quick trim values `4` and `0.623`, plays and pauses
both actual preview players, waits for an actually visible scene while playing
before pausing, and verifies a decoded first frame in the actual
untouched device screenshot, sampling the active video area derived from the
player's reported video size and surface bounds, with the actual imported source's
metadata dimensions used when the player has not reported its video size.
The detailed screen also waits for its
actual timeline thumbnail. FFprobe verifies the encoded source is exactly eight
seconds. Android's metadata reader may include a small codec timing difference
(8,023 ms on the API 35 capture device). The test records that actual imported
duration, retains the range from 4,000 ms to the imported duration minus 623 ms,
and verifies the resulting real bounds. Visible UI times use the app's normal
formatting.

The original colorbar regression fixtures and tests are unchanged. Product and
native sources must exactly match commit
`1354bc5f551ebd2f6954cb492706e874abe9d601`; the workflow checks that condition.
Native libraries alone are reused from successful build `41598381521`, with the
same pinned SHA-256 verification as the foreground-service evidence workflow.
The current debug app and test APKs are built together with the runner's debug key.

The artifact `quickcut-store-scenic-api-29` contains the new source video,
`source-media.json`, source checksums, the source commit, both raw screenshots,
`evidence.json`, a success marker, and the focused instrumentation report. The
evidence records the imported source checksum, real trim bounds, player/frame
checks, screenshot content-area pixel statistics, and screenshot checksums.
PixelCopy images, result codes, and pixel statistics are collected as independent
diagnostics; they are not claimed as successful when the copy fails. A failure
also saves the actual app screenshot, player state, surface/video bounds, and
pixel statistics before the activity is torn down. Promotional image composition happens only
after these real captures are reviewed.
