# Background and editing preferences verification

## Regression baseline

Regression tests were committed before implementation. GitHub Actions build
`40982791312` on `c206837a2d21d3754b11a4f220362d4053b3b80d` failed compilation
because the new preview lifecycle tests reference the absent `setActive` API.
This is an API-level red result, not a behavioral assertion failure.

API35 instrumentation run `40982791358` executed 185 tests: 179 passed and six
failed. Four failures cover the absent preset/activation-confirmation UI; two
cover missing Media3 error-code/codec diagnostic retention. Failures from the
old behavior must be resolved by the implementation, without weakening the
success-only original-handling or output-integrity checks.

## Implementation snapshot

The existing foreground service and wake lock remain the export owner. Preview
resources now stop while invisible or exporting, preserve position, and prepare
paused when visible again. Decoder initialization may select a compatible
fallback decoder; generic I/O errors and timeouts do not trigger a new retry.

Original handling is a confirmed, persisted preference, separate from each
export's immutable choice and its newly successful output candidates. Recovered
old outputs never arm automatic original handling. Android's approval remains.

Native copy/boundary processing, Media3 processing, and actual encoder creation
have distinct stages. Typed, allowlisted engine/codec/error evidence is stored
without exception messages or source locations.

## Local verification

- `python3 scripts/test-emulator-flow.py`: 9 passed.
- `python3 scripts/test-emulator-failure.py`: 3 passed.
- `python3 scripts/test-release-gate.py`: 15 passed.
- `python3 scripts/test-runtime-publication-metadata.py`: 17 passed.
- `git diff --check`: passed.
- Android SDK, Gradle and Cargo are unavailable locally; Android/JVM/native
  validation uses repository CI. No local Android pass is claimed.

## Integration and fixture investigation

Whole-branch independent review found no Critical, Important or Minor findings.
The implementation tree `32daff58d045347779249b22c332274c1b7d61ab` matched remote
commit `2412a9351828184d685ce44cf4880cb351051379` exactly. Build `40984574357`
passed native/JVM checks, debug/release lint, APK builds and source packaging.
In emulator run `40984574453`, API35 and API36 each passed 192 distinct full-suite
tests without failures or skips. API29 passed 191 and timed out in the new
background completion test after 240 seconds.

The per-test API29 log shows processing continued after display-off at
01:27:43.555. Goldfish then lost four surface frames per short input and waited
20 seconds per input. Twelve six-second clips accumulated these waits before
the existing end-of-composition frame guard and software recovery could run.
Existing foreground tests in the same artifact show the same frame loss and
successful recovery with OMX.google.h264.decoder, so this does not establish a
new background-only application stall.

The fixture now uses two distinct 40-second input files, trimming 36 seconds
from each. It preserves 72 seconds of real Media3 processing, genuine progress
before backgrounding, another app, screen-off completion, the unchanged
240-second bound, full duration/frame/audio validation, exactly one publication,
and cancellation cleanup. It does not claim twelve-stream transition stress
coverage. No application timeout, integrity check or retry policy was relaxed.
Latest CI results are recorded in PR9; this file records the baseline and fixture
investigation.

## Device validation limit

No Fold8 device, affected source file or user error log is available. The original
device failure has not been reproduced and must not be described as proven fixed.
