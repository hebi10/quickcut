# Scenic store captures

The original generated coastal image is `scenic-coast.png`. It is converted locally
into a new eight-second H.264/AAC video with a gentle camera movement. No external
media download or test-pattern source is used in this capture workflow.

The workflow `store-scenic-capture.yml` runs only
`StoreScenicCaptureInstrumentedTest` on API 35. It imports the actual video through
`EditorViewModel`, enters the quick trim values `4` and `0.623`, plays and pauses
both actual preview players, and verifies a decoded first frame with `PixelCopy`
before each untouched device screenshot. The detailed screen also waits for its
actual timeline thumbnail. The source is 8,000 ms; the retained range is
4,000–7,377 ms and the result is 3,377 ms. Visible UI times use the app's normal
formatting.

The original colorbar regression fixtures and tests are unchanged. Product and
native sources must exactly match commit
`1354bc5f551ebd2f6954cb492706e874abe9d601`; the workflow checks that condition.
Native libraries alone are reused from successful build `41598381521`, with the
same pinned SHA-256 verification as the foreground-service evidence workflow.
The current debug app and test APKs are built together with the runner's debug key.

The artifact `quickcut-store-scenic-api-35` contains the new source video,
`source-media.json`, source checksums, the source commit, both raw screenshots,
`evidence.json`, a success marker, and the focused instrumentation report. The
evidence records the imported source checksum, real trim bounds, player/frame
checks, and screenshot checksums. Promotional image composition happens only
after these real captures are reviewed.
