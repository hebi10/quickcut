# QuickCut API29 — Google APIs vs AOSP Media3 comparison

## Controlled environment

Both jobs run identical test class `BackgroundMedia3ExportInstrumentedTest` (the publish and cancellation cases), from the exact same commit and generated video fixtures, on independent Android 10 (API 29) x86_64 pixel_6 emulators. Emulator RAM 2048M, heap 256M, disk 2048M and 12-minute test timeout are identical. Only the system-image target differs: `google_apis` versus `default` (AOSP).

## Evidence

Each job uploads `quickcut-api29-media3-<system_image>` with an Android device identity file (fingerprint, API, ABI, Google Play services package), phase-marked `QuickCutApi29Probe` records, JUnit XML, all-buffer system logcat, extracted fatal signals and system memory data when available.

Previous Google APIs isolated test crashed in system_server (`ConfigUpdateInstallReceiver.getAltContent` / OutOfMemoryError then DeadSystemException); the date-export isolated test passed. These facts do not establish the cause of earlier full-suite timeouts.

## Interpretations

- Google APIs system crash but AOSP pass: evidence favors system image-specific behavior, not proof all Android 10 devices are affected.
- Same failure phase on both: investigate common Media3 pipeline, export lifecycle and shared emulator factors.
- Both isolated pass but whole suite stalls: investigate inter-test interference, resource accumulation or ordering.
- Different errors: classify separately; incomplete or unavailable system-image installation is not a codec test result.

The full API 29/35/36 instrumentation suite and signed release gates remain unchanged and required. This comparison does not change QuickCut's production media processing or Google Play track.
