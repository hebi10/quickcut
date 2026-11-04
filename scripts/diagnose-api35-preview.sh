#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_dir"
results=app/build/outputs/androidTest-results/connected
reports=app/build/reports/androidTests/connected
output=quickcut-preview-diagnostic-results
method=com.hebi.quickcut.ui.EditorDesignInstrumentedTest#quickAndDetailedShowResultTimeWithoutChangingTrimBounds
first_failure=0

mkdir -p "$output" app/src/androidTest/assets
git rev-parse HEAD > "$output/source-commit.txt"
git rev-parse 'HEAD^{tree}' > "$output/source-tree.txt"
printf '%s\n' "$method" > "$output/method.txt"
timeout --kill-after=2s 10s adb shell getprop > "$output/device-properties.txt"

for profile in original_pattern solid_blue; do
  destination="$output/$profile"
  mkdir -p "$destination"
  rm -rf "$results" "$reports"
  timeout --kill-after=2s 10s adb shell rm -rf /data/local/tmp/quickcut-preview-diagnostics /data/local/tmp/quickcut-design-review
  timeout --kill-after=2s 10s adb logcat -b all -c || true
  video_filter=testsrc2=size=320x240:rate=30
  color_filter=none
  output_filters=()
  if [[ "$profile" == solid_blue ]]; then
    color_filter=drawbox=x=0:y=0:w=iw:h=ih:color=blue:t=fill
    output_filters=(-vf "$color_filter")
  fi
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "$video_filter" \
    -f lavfi -i sine=frequency=880:sample_rate=44100 \
    -t 8 "${output_filters[@]}" -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
    -g 60 -keyint_min 60 -sc_threshold 0 \
    -c:a aac -b:a 64k -shortest \
    app/src/androidTest/assets/quickcut_keyframes.mp4
  fixture=app/src/androidTest/assets/quickcut_keyframes.mp4
  cp "$fixture" "$destination/fixture.mp4"
  (cd "$destination" && sha256sum fixture.mp4 > fixture.sha256)
  ffprobe -v error -show_format -show_streams -of json "$fixture" > "$destination/fixture.json"
  ffmpeg -hide_banner -loglevel error -y -ss 4 -i "$fixture" -frames:v 1 "$destination/reference-frame-4000ms.png"
  printf 'profile=%s\nvideo_filter=%s\ncolor_filter=%s\nmethod=%s\n' "$profile" "$video_filter" "$color_filter" "$method" > "$destination/profile.txt"

  gradle_status=0
  timeout --kill-after=30s 10m gradle --no-daemon \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    -Pandroid.testInstrumentationRunnerArguments.class="$method" \
    :app:connectedDebugAndroidTest > "$destination/gradle.log" 2>&1 || gradle_status=$?
  cat "$destination/gradle.log"
  if [[ "$gradle_status" -ne 0 && "$first_failure" -eq 0 ]]; then first_failure="$gradle_status"; fi

  timeout --kill-after=2s 10s adb logcat -b all -d -v threadtime > "$destination/logcat.txt" 2>&1 || true
  grep -E 'QuickCutPreviewTrace|QuickCutPreviewTest' "$destination/logcat.txt" > "$destination/preview-trace.txt" || true
  timeout --kill-after=2s 10s adb exec-out screencap -p > "$destination/after-test-screen.png" 2> "$destination/screencap-status.txt" || true
  timeout --kill-after=2s 10s adb shell dumpsys activity activities > "$destination/after-test-activity.txt" 2>&1 || true
  for directory in quickcut-preview-diagnostics quickcut-design-review; do
    timeout --kill-after=2s 10s adb pull "/data/local/tmp/$directory" "$destination/$directory" >> "$destination/collection.log" 2>&1 || true
  done
  if [[ -d "$results" ]]; then cp -a "$results" "$destination/results"; fi
  if [[ -d "$reports" ]]; then cp -a "$reports" "$destination/reports"; fi

  xml_status=0
  python3 - "$destination/results" <<'PY' || xml_status=$?
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

expected = ('com.hebi.quickcut.ui.EditorDesignInstrumentedTest',
            'quickAndDetailedShowResultTimeWithoutChangingTrimBounds')
cases = []
for report in sorted(Path(sys.argv[1]).rglob('TEST-*.xml')):
    for case in ET.parse(report).iter('testcase'):
        identity = (case.get('classname'), case.get('name'))
        if identity != expected or any(case.find(field) is not None for field in ('failure', 'error', 'skipped')):
            raise SystemExit('Diagnostic exact-method result failed: ' + repr(identity))
        cases.append(identity)
if cases != [expected]:
    raise SystemExit('Diagnostic must execute exactly one fresh expected test; found ' + str(len(cases)))
print('Diagnostic exact method passed with the original assertions')
PY
  if [[ "$xml_status" -ne 0 && "$first_failure" -eq 0 ]]; then first_failure="$xml_status"; fi
  printf 'gradle_exit=%s\nxml_exit=%s\n' "$gradle_status" "$xml_status" > "$destination/status.txt"
done

printf 'first_failure=%s\n' "$first_failure" > "$output/status.txt"
exit "$first_failure"
