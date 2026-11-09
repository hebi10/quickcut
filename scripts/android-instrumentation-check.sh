#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
RESULTS=app/build/outputs/androidTest-results/connected
REPORTS=app/build/reports/androidTests/connected
PREFLIGHT=quickcut-native-preflight-results
first_failure=0
last_status=0

run_checked() {
  local label="$1"
  shift
  last_status=0
  "$@" || last_status=$?
  if [[ "$last_status" -ne 0 ]]; then
    echo "::error::$label failed (exit $last_status)"
    if [[ "$first_failure" -eq 0 ]]; then first_failure="$last_status"; fi
  fi
  return 0
}

# Preserve focused evidence before the mandatory unfiltered run can replace it.
rm -rf "$PREFLIGHT" "$RESULTS" "$REPORTS" quickcut-main10-preflight quickcut-stage20-evidence
mkdir -p "$PREFLIGHT/results" "$PREFLIGHT/reports"
run_checked "Clear preflight Main10 evidence" timeout --kill-after=2s 10s adb shell rm -rf /data/local/tmp/quickcut-main10
run_checked "Native preflight" timeout --kill-after=30s 10m gradle --no-daemon \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hebi.quickcut.media.NativeSmartCutInstrumentedTest
preflight_gradle_status="$last_status"
# Isolate package removal from required post-test evidence collection. This
# device check also proves the installed AGP honored the retention option.
run_checked "Require retained test APK after preflight" timeout --kill-after=2s 10s adb shell pm path com.hebi.quickcut.test
if [[ -d "$RESULTS" ]]; then
  run_checked "Preserve preflight XML" cp -a "$RESULTS/." "$PREFLIGHT/results/"
else
  run_checked "Require preflight XML directory" false
fi
if [[ -d "$REPORTS" ]]; then
  run_checked "Preserve preflight reports" cp -a "$REPORTS/." "$PREFLIGHT/reports/"
fi
# Unique device directories survive app uninstall; snapshot preflight evidence too.
if ! timeout --kill-after=2s 10s adb pull /data/local/tmp/quickcut-native-diagnostics "$PREFLIGHT/diagnostics"; then
  echo "::warning::Optional preflight native diagnostics were unavailable."
fi
run_checked "Pull preflight Main10 evidence" timeout --kill-after=2s 10s adb pull /data/local/tmp/quickcut-main10 quickcut-main10-preflight
run_checked "Verify preflight Main10 evidence" python3 "$SCRIPT_DIR/verify-android-main10.py" quickcut-main10-preflight
printf 'gradle_exit=%s\nphase_exit=%s\n' "$preflight_gradle_status" "$first_failure" > "$PREFLIGHT/status.txt"

# Native failures must not hide UI/recovery results. Always run the full suite,
# using fresh XML so a preflight report cannot satisfy the unfiltered gate.
rm -rf "$RESULTS" "$REPORTS" quickcut-main10-full
run_checked "Clear full-suite Main10 evidence" timeout --kill-after=2s 10s adb shell rm -rf /data/local/tmp/quickcut-main10
# API29: preserve all 297 tests, including five Modern Edit Desk QA cases, but separate the date/Media3
# codec stress classes into fresh instrumentation processes. Consecutive
# API29 media tests have repeatedly wedged the emulator service at test 105.
if [[ "${QUICKCUT_EMULATOR_API:-}" == 29 ]]; then
  phase_dir=quickcut-api29-full-suite
  rm -rf "$phase_dir"
  mkdir -p "$phase_dir" quickcut-host-diagnostics

  run_api29_phase() {
    local phase="$1" selector="$2" budget="$3"
    local phase_status
    local xmls=()
    rm -rf "$RESULTS" "$REPORTS"
    run_checked "API29 full-suite phase $phase" timeout --kill-after=30s "$budget" gradle --no-daemon \
      -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true :app:connectedDebugAndroidTest \
      "$selector"
    phase_status="$last_status"
    if [[ "$phase_status" -ne 0 ]]; then
      timeout --kill-after=2s 15s adb logcat -d -v threadtime \
        -s QuickCutApi29Probe:I TestRunner:I AndroidRuntime:E ActivityManager:E \
        > "quickcut-host-diagnostics/api29-$phase-failure.log" || true
      timeout --kill-after=2s 10s adb shell dumpsys activity processes \
        > "quickcut-host-diagnostics/api29-$phase-processes.txt" || true
    fi
    mapfile -d '' xmls < <(find "$RESULTS" -name 'TEST-*.xml' -print0 2>/dev/null)
    if [[ "${#xmls[@]}" -ne 1 ]]; then
      run_checked "Require one API29 $phase XML result" false
    else
      run_checked "Preserve API29 $phase XML" cp -- "${xmls[0]}" "$phase_dir/TEST-api29-$phase.xml"
    fi
  }
  # Each original test runs once. Run Media3 screen-off stress last, so its
  # codec state cannot contaminate the subsequent date-export test.
  run_api29_phase date \
    "-Pandroid.testInstrumentationRunnerArguments.class=com.hebi.quickcut.media.BatchDateExportInstrumentedTest" 10m
  run_api29_phase remaining \
    "-Pandroid.testInstrumentationRunnerArguments.notClass=com.hebi.quickcut.media.BatchDateExportInstrumentedTest,com.hebi.quickcut.export.BackgroundMedia3ExportInstrumentedTest" 25m
  run_api29_phase media3 \
    "-Pandroid.testInstrumentationRunnerArguments.class=com.hebi.quickcut.export.BackgroundMedia3ExportInstrumentedTest" 15m

  rm -rf "$RESULTS"
  mkdir -p "$RESULTS/debug"
  for phase in date remaining media3; do
    if [[ -f "$phase_dir/TEST-api29-$phase.xml" ]]; then
      run_checked "Restore API29 $phase XML" \
        cp -- "$phase_dir/TEST-api29-$phase.xml" "$RESULTS/debug/"
    fi
  done
  run_checked "Require all 297 API29 tests" \
    python3 "$SCRIPT_DIR/verify-api29-complete-suite.py" "$RESULTS/debug"
else
  run_checked "Full instrumentation suite" timeout --kill-after=30s 20m gradle --no-daemon \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true :app:connectedDebugAndroidTest
fi
run_checked "Pull full-suite Main10 evidence" timeout --kill-after=2s 10s adb pull /data/local/tmp/quickcut-main10 quickcut-main10-full
run_checked "Verify full-suite Main10 evidence" python3 "$SCRIPT_DIR/verify-android-main10.py" quickcut-main10-full
# Only fixture-generated MP4s are archived; never capture user-selected media.
mkdir -p quickcut-stage20-media
for name in stage17-source.mp4 stage17-output.mp4 stage17-audio-track.txt; do
  if timeout --kill-after=2s 12s adb exec-out run-as com.hebi.quickcut cat "files/stage20-audio-evidence/$name" > "quickcut-stage20-media/$name" \
      && [[ -s "quickcut-stage20-media/$name" ]]; then
    echo "Synthetic MP4 audio QA evidence captured: $name"
  else
    rm -f "quickcut-stage20-media/$name"
    echo "::warning::Synthetic audio evidence unavailable: $name"
  fi
done
run_checked "Verify full-suite XML" python3 - <<'PYCODE'
from pathlib import Path
import xml.etree.ElementTree as ET

results = Path('app/build/outputs/androidTest-results/connected')
cases = set()
for report in sorted(results.rglob('TEST-*.xml')):
    root = ET.parse(report).getroot()
    for suite in root.iter('testsuite'):
        if any(int(suite.get(field, '0')) for field in ('failures', 'errors', 'skipped')):
            raise SystemExit(f'Full instrumentation suite has failed or skipped tests: {report}')
    for case in root.iter('testcase'):
        key = (case.get('classname'), case.get('name'))
        if not all(key):
            raise SystemExit(f'Instrumentation test has no stable identity: {report}')
        if any(case.find(field) is not None for field in ('failure', 'error', 'skipped')):
            raise SystemExit(f'Full instrumentation test did not pass: {key}')
        cases.add(key)
if len(cases) < 152:
    raise SystemExit(f'Expected at least 152 distinct full-suite tests, found {len(cases)}')
print(f'Full instrumentation suite verified: {len(cases)} distinct tests, no failures, errors, or skips')
PYCODE
# An optional review image cannot replace a required test or verification failure.
if ! timeout --kill-after=2s 10s adb pull /sdcard/Android/data/com.hebi.quickcut/files/quickcut-fast-review.png quickcut-fast-review.png; then
  echo "::warning::Optional fast-review screenshot was unavailable; instrumentation results are unchanged."
fi
if ! timeout --kill-after=2s 10s adb pull /data/local/tmp/quickcut-design-review quickcut-design-review; then
  echo "::warning::Optional design screenshots were unavailable; instrumentation results are unchanged."
fi
# Stage 7 screenshots are stored in the debuggable app's private files. Pull
# them with run-as instead of relying on scoped-storage shell access or UIAutomation pipes.
mkdir -p quickcut-design-review
for file in stage7_home.png stage7_projects_empty.png stage7_onboarding.png; do
  if timeout --kill-after=2s 20s adb exec-out run-as com.hebi.quickcut cat "files/design-review/$file" > "quickcut-design-review/$file" && [[ -s "quickcut-design-review/$file" ]]; then
    echo "Stage 7 screenshot preserved: $file"
  else
    echo "::error::Stage 7 screenshot missing or empty: $file"
    if [[ "$first_failure" -eq 0 ]]; then first_failure=1; fi
  fi
done
# Stage 1-A theme evidence is rendered from real Compose UI in both selected modes.
for file in theme_light_home.png theme_dark_home.png; do
  if timeout --kill-after=2s 20s adb exec-out run-as com.hebi.quickcut cat "files/design-review/$file" > "quickcut-design-review/$file" && [[ -s "quickcut-design-review/$file" ]]; then
    echo "Theme 1-A screenshot preserved: $file"
  else
    echo "::error::Theme 1-A screenshot missing or empty: $file"
    if [[ "$first_failure" -eq 0 ]]; then first_failure=1; fi
  fi
done
run_checked "Verify light/dark theme screenshots" python3 - "$SCRIPT_DIR/stage20-evidence-verify.py" <<'PYTHEME'
from pathlib import Path
import importlib.util
import sys

spec = importlib.util.spec_from_file_location("stage20_images", sys.argv[1])
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
for name in ("theme_light_home.png", "theme_dark_home.png"):
    dimensions = validator.verify_png(Path("quickcut-design-review") / name)
    print("Verified theme screenshot: " + name + " " + str(dimensions))
PYTHEME
# Stage 13 reference states are rendered from decoded local video fixtures, not demo placeholders.
# Keep them as a separate required screenshot set without weakening the Stage 7 evidence.
for file in stage13_home_populated.png stage13_projects_populated.png stage13_tools.png \
    stage13_settings.png stage13_gallery_selected.png stage13_gallery_one_selected.png \
    stage13_projects_empty_large_font.png; do
  if timeout --kill-after=2s 20s adb exec-out run-as com.hebi.quickcut cat "files/design-review/$file" > "quickcut-design-review/$file" && [[ -s "quickcut-design-review/$file" ]]; then
    echo "Stage 13 screenshot preserved: $file"
  else
    echo "::error::Stage 13 screenshot missing or empty: $file"
    if [[ "$first_failure" -eq 0 ]]; then first_failure=1; fi
  fi
done
# Modern Edit Desk 7-A: require real dark-screen capture on 320dp/160% as well
# as each media editor and save dialog; missing/invalid PNG cannot pass release QA.
for file in modern_dark_home_320_font160.png modern_dark_projects_320_font160.png \
    modern_dark_tools_320_font160.png modern_dark_settings_320_font160.png \
    modern_dark_crop.png modern_dark_audio.png modern_dark_text.png \
    modern_dark_export_dialog.png; do
  if timeout --kill-after=2s 20s adb exec-out run-as com.hebi.quickcut cat "files/design-review/$file" > "quickcut-design-review/$file" && [[ -s "quickcut-design-review/$file" ]]; then
    echo "Modern dark QA screenshot captured: $file"
  else
    echo "::error::Modern dark QA screenshot missing or empty: $file"
    if [[ "$first_failure" -eq 0 ]]; then first_failure=1; fi
  fi
done
run_checked "Verify dark-mode UI screenshots" python3 - "$SCRIPT_DIR/stage20-evidence-verify.py" <<'PYMODERN'
import importlib.util
from pathlib import Path
import sys
spec = importlib.util.spec_from_file_location("quickcut_png_integrity", sys.argv[1])
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
for name in (
    "modern_dark_home_320_font160.png", "modern_dark_projects_320_font160.png",
    "modern_dark_tools_320_font160.png", "modern_dark_settings_320_font160.png",
    "modern_dark_crop.png", "modern_dark_audio.png", "modern_dark_text.png",
    "modern_dark_export_dialog.png",
):
    dimensions = validator.verify_png(Path("quickcut-design-review") / name)
    print("Verified Modern Edit Desk screenshot: " + name + " " + str(dimensions))
PYMODERN
# Stage 15: real Android save-settings screens, including unsupported capability feedback.
for file in stage15_export_auto.png stage15_export_quality.png \
    stage15_export_unsupported.png; do
  if timeout --kill-after=2s 20s adb exec-out run-as com.hebi.quickcut cat "files/design-review/$file" > "quickcut-design-review/$file" && [[ -s "quickcut-design-review/$file" ]]; then
    echo "Stage 15 screenshot preserved: $file"
  else
    echo "::error::Stage 15 screenshot missing or empty: $file"
    if [[ "$first_failure" -eq 0 ]]; then first_failure=1; fi
  fi
done
# A failed adb cat may write an error string and still produce a non-empty file.
# Require PNG signature, IHDR integrity and plausible device dimensions.
run_checked "Verify Stage 15 screenshots are actual PNG images" python3 - <<'PYVERIFY'
from pathlib import Path
import struct
import zlib

for name in ("stage15_export_auto.png", "stage15_export_quality.png", "stage15_export_unsupported.png"):
    path = Path("quickcut-design-review") / name
    data = path.read_bytes() if path.exists() else b""
    if len(data) < 33 or data[:8] != bytes.fromhex("89504e470d0a1a0a"):
        raise SystemExit(f"Not a PNG screenshot: {path}")
    if struct.unpack(">I", data[8:12])[0] != 13 or data[12:16] != b"IHDR":
        raise SystemExit(f"Missing PNG IHDR: {path}")
    width, height = struct.unpack(">II", data[16:24])
    if not (200 <= width <= 10000 and 200 <= height <= 10000):
        raise SystemExit(f"Invalid screenshot dimensions: {path} ({width}x{height})")
    recorded = struct.unpack(">I", data[29:33])[0]
    if recorded != zlib.crc32(data[12:29]) & 0xffffffff:
        raise SystemExit(f"Corrupted PNG IHDR checksum: {path}")
    print(f"Verified Stage 15 PNG: {name} ({width}x{height})")
PYVERIFY
# Stage 16 real crop UI renders must exist on every tested API.
for file in stage16_crop_vertical.png stage16_crop_square.png \
    stage16_crop_free.png stage16_crop_largefont.png; do
  if timeout --kill-after=2s 20s adb exec-out run-as com.hebi.quickcut cat "files/design-review/$file" > "quickcut-design-review/$file" && [[ -s "quickcut-design-review/$file" ]]; then
    echo "Stage 16 screenshot preserved: $file"
  else
    echo "::error::Stage 16 screenshot missing or empty: $file"
    if [[ "$first_failure" -eq 0 ]]; then first_failure=1; fi
  fi
done
run_checked "Verify Stage 16 screenshots are actual PNG images" python3 - <<'PYCROP'
from pathlib import Path
import struct
import zlib
for name in ("stage16_crop_vertical.png", "stage16_crop_square.png",
             "stage16_crop_free.png", "stage16_crop_largefont.png"):
    p = Path("quickcut-design-review") / name
    data = p.read_bytes() if p.exists() else b""
    if len(data) < 33 or data[:8] != bytes.fromhex("89504e470d0a1a0a"):
        raise SystemExit(f"Invalid Stage16 PNG: {p}")
    if data[12:16] != b"IHDR" or int.from_bytes(data[8:12], "big") != 13:
        raise SystemExit(f"Missing Stage16 IHDR: {p}")
    width, height = struct.unpack(">II", data[16:24])
    if width < 200 or height < 200:
        raise SystemExit(f"Bad Stage16 capture geometry: {p}")
    if zlib.crc32(data[12:29]) & 0xffffffff != int.from_bytes(data[29:33], "big"):
        raise SystemExit(f"Corrupt Stage16 capture header: {p}")
PYCROP
# Stage 14: phone, three-segment and large-font tablet screenshots from the real Android UI.
for file in stage14_extraction_one.png stage14_extraction_three.png \
    stage14_extraction_tablet_largefont.png; do
  if timeout --kill-after=2s 20s adb exec-out run-as com.hebi.quickcut cat "files/design-review/$file" > "quickcut-design-review/$file" && [[ -s "quickcut-design-review/$file" ]]; then
    echo "Stage 14 screenshot preserved: $file"
  else
    echo "::error::Stage 14 screenshot missing or empty: $file"
    if [[ "$first_failure" -eq 0 ]]; then first_failure=1; fi
  fi
done
# Stage 17 audio controls must be real Android PNG captures, not generated mockups.
for file in stage17_audio_default.png stage17_audio_fades.png \
    stage17_audio_muted.png stage17_audio_largefont.png; do
  if timeout --kill-after=2s 20s adb exec-out run-as com.hebi.quickcut cat "files/design-review/$file" > "quickcut-design-review/$file" && [[ -s "quickcut-design-review/$file" ]]; then
    echo "Stage 17 screenshot captured: $file"
  else
    echo "::error::Stage 17 screenshot missing or empty: $file"
    if [[ "$first_failure" -eq 0 ]]; then first_failure=1; fi
  fi
done
run_checked "Verify Stage 17 screenshots are actual Android PNG images" python3 - <<'PYPNG'
from pathlib import Path
import struct
import zlib
names = ("stage17_audio_default.png", "stage17_audio_fades.png",
         "stage17_audio_muted.png", "stage17_audio_largefont.png")
for name in names:
    p = Path("quickcut-design-review") / name
    data = p.read_bytes() if p.exists() else b""
    if len(data) < 50 or data[:8] != bytes.fromhex("89504e470d0a1a0a"):
        raise SystemExit(f"Invalid Stage 17 PNG: {p}")
    if int.from_bytes(data[8:12], "big") != 13 or data[12:16] != b"IHDR":
        raise SystemExit(f"Missing Stage 17 PNG IHDR: {p}")
    w, h = struct.unpack(">II", data[16:24])
    if w < 200 or h < 200:
        raise SystemExit(f"Invalid Stage 17 screenshot dimensions: {p} ({w}x{h})")
    if zlib.crc32(data[12:29]) & 0xffffffff != int.from_bytes(data[29:33], "big"):
        raise SystemExit(f"Corrupt Stage 17 PNG header: {p}")
    print(f"Stage 17 verified: {name} ({w}x{h})")
PYPNG
# Stage 18: text layer UI must be verified using screenshots from the real Android app.
for file in stage18_text_empty.png stage18_text_selected.png \
    stage18_text_styled.png stage18_text_largefont.png; do
  if timeout --kill-after=2s 20s adb exec-out run-as com.hebi.quickcut cat "files/design-review/$file" > "quickcut-design-review/$file" && [[ -s "quickcut-design-review/$file" ]]; then
    echo "Stage 18 screenshot preserved: $file"
  else
    echo "::error::Stage 18 screenshot missing: $file"
    if [[ "$first_failure" -eq 0 ]]; then first_failure=1; fi
  fi
done
run_checked "Verify Stage 18 screenshots are PNG images" python3 - <<'PYSTAGE18'
from pathlib import Path
from struct import unpack
import zlib
for name in ("stage18_text_empty.png", "stage18_text_selected.png",
             "stage18_text_styled.png", "stage18_text_largefont.png"):
    p = Path("quickcut-design-review") / name
    data = p.read_bytes() if p.exists() else b""
    if len(data) < 50 or data[:8] != bytes.fromhex("89504e470d0a1a0a"):
        raise SystemExit(f"Invalid Stage 18 PNG: {p}")
    if unpack(">I", data[8:12])[0] != 13 or data[12:16] != b"IHDR":
        raise SystemExit(f"Invalid Stage 18 PNG IHDR: {p}")
    w, h = unpack(">II", data[16:24])
    if w < 200 or h < 200:
        raise SystemExit(f"Bad Stage 18 screenshot size: {p} ({w}x{h})")
    if unpack(">I", data[29:33])[0] != zlib.crc32(data[12:29]) & 0xffffffff:
        raise SystemExit(f"Corrupt Stage 18 PNG: {p}")
    print(f"Verified Stage 18 PNG: {name} {w}x{h}")
PYSTAGE18
if [[ "$first_failure" -eq 0 ]]; then
  run_checked "Stage 20 mandatory test and PNG evidence" python3 "$SCRIPT_DIR/stage20-evidence-verify.py" \
    --xml "$RESULTS" --screenshots quickcut-design-review \
    --manifest quickcut-stage20-evidence/verified-screens-and-tests.json
else
  # Still evaluate all evidence for diagnostics; never issue an approved manifest after an earlier failure.
  run_checked "Stage 20 mandatory test and PNG evidence" python3 "$SCRIPT_DIR/stage20-evidence-verify.py" \
    --xml "$RESULTS" --screenshots quickcut-design-review
fi
if [[ "$first_failure" -ne 0 ]]; then
  echo "::error::Instrumentation failed; preserving the first failure (exit $first_failure)."
  exit "$first_failure"
fi
echo "QuickCut Android export instrumentation tests passed"
