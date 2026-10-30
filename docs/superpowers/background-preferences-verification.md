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

## Pending validation

Whole-branch independent review and implementation CI are pending. The added
Media3 background tests exercise genuine transform progress, another app,
screen-off completion, and cancellation cleanup. No Fold8 device, affected
source file or user error log is available; the original device failure has not
been reproduced and must not be described as proven fixed.
