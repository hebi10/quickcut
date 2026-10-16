#!/usr/bin/env bash
set -euo pipefail

on_failure() {
  local status="$?"
  if [ "$status" -ne 0 ]; then
    echo "::error::QuickCut emulator smoke test failed (exit $status)"
    adb logcat -d -s AndroidRuntime:E | tail -100 || true
    if ! adb pull /sdcard/Android/data/com.hebi.quickcut/files/native-diagnostics quickcut-native-diagnostics; then
      echo "::warning::Native failure diagnostics were unavailable; the original failure is unchanged."
    fi
  fi
  return "$status"
}
trap on_failure EXIT

# An older version-code fixture uses this runner's same debug key. This checks
# Android's update/data retention path, not compatibility with old distributed APKs.
gradle --no-daemon -PquickcutTestVersionCode=9 :app:assembleDebug
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
if [[ ! $(adb shell dumpsys package com.hebi.quickcut) =~ versionCode=9[[:space:]] ]]; then
  echo "::error::Older-version update fixture was not installed"
  exit 1
fi
adb shell run-as com.hebi.quickcut mkdir -p files
printf 'quickcut-update-data' | adb shell run-as com.hebi.quickcut sh -c '"cat > files/update-sentinel"'
gradle --no-daemon :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
if [[ ! $(adb shell dumpsys package com.hebi.quickcut) =~ versionCode=10[[:space:]] ]]; then
  echo "::error::Update candidate was not installed"
  exit 1
fi
if [ "$(adb shell run-as com.hebi.quickcut cat files/update-sentinel | tr -d '\r')" != "quickcut-update-data" ]; then
  echo "::error::Same-signature update did not retain application data"
  exit 1
fi
adb shell run-as com.hebi.quickcut rm files/update-sentinel
echo "Same-signature version-code 9 to 10 update retained application data"
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell am start -W -n com.hebi.quickcut/.MainActivity
sleep 3
adb exec-out screencap -p > quickcut-start.png

found_screen=false
for attempt in 1 2 3 4 5; do
  if adb shell uiautomator dump /sdcard/quickcut-ui.xml >/dev/null 2>&1 &&
     adb shell cat /sdcard/quickcut-ui.xml | grep -q "빠른 자르기"; then
    found_screen=true
    break
  fi
  sleep 2
done
if [ "$found_screen" != true ]; then
  echo "::error::QuickCut start screen could not be verified"
  exit 1
fi
echo "QuickCut start screen verified"

adb shell wm size 1280x800
adb shell wm density 160
sleep 3
adb exec-out screencap -p > quickcut-tablet-start.png
adb shell wm size reset
adb shell wm density reset

mkdir -p app/src/androidTest/assets
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i color=c=blue:s=320x240:r=30 \
  -f lavfi -i sine=frequency=440:sample_rate=44100 \
  -t 3 -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -c:a aac -b:a 64k -shortest \
  -metadata creation_time=2021-01-01T00:00:00Z \
  app/src/androidTest/assets/quickcut_fixture.mp4

# Deterministic 2-second sync boundaries: reproduce a 4-second head trim.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i testsrc2=size=320x240:rate=30 \
  -f lavfi -i sine=frequency=880:sample_rate=44100 \
  -t 8 -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -g 60 -keyint_min 60 -sc_threshold 0 \
  -c:a aac -b:a 64k -shortest \
  app/src/androidTest/assets/quickcut_keyframes.mp4

# Closed GOP B-frames reproduce downloaded videos with reordered timestamps.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i testsrc2=size=320x240:rate=30 \
  -f lavfi -i sine=frequency=660:sample_rate=44100 \
  -t 8 -c:v libx264 -preset medium -pix_fmt yuv420p \
  -bf 2 -g 60 -keyint_min 60 -sc_threshold 0 -flags +cgop \
  -c:a aac -b:a 64k -shortest \
  app/src/androidTest/assets/quickcut_bframes.mp4

# Leading B-frames after an open-GOP sync sample must not be silently discarded.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i testsrc2=size=320x240:rate=30 \
  -f lavfi -i sine=frequency=660:sample_rate=44100 \
  -t 8 -c:v libx264 -preset medium -pix_fmt yuv420p \
  -bf 2 -g 60 -keyint_min 60 -sc_threshold 0 \
  -x264-params open-gop=1:b-adapt=0:b-pyramid=0 \
  -c:a aac -b:a 64k -shortest \
  app/src/androidTest/assets/quickcut_open_gop.mp4

# Color changes exactly at the 2s keyframe; a 1.95s trim must retain a red frame.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i color=c=red:size=320x240:rate=30 \
  -f lavfi -i sine=frequency=440:sample_rate=44100 \
  -vf "drawbox=x=0:y=0:w=iw:h=ih:color=blue:t=fill:enable='gte(t,2)'" \
  -t 6 -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -bf 0 -g 60 -keyint_min 60 -sc_threshold 0 \
  -c:a aac -b:a 64k -shortest \
  app/src/androidTest/assets/quickcut_exact_boundary.mp4

# Safe monotonic retained prefix, reordered discarded video tail, short audio.
# This catches unnecessary tail extraction without device-speed assertions.
fixture_work=$(mktemp -d)
for segment in prefix tail; do
  bframes=0
  if [ "$segment" = tail ]; then bframes=2; fi
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i testsrc2=size=320x240:rate=30 -t 3 \
    -c:v libx264 -preset medium -pix_fmt yuv420p -bf "$bframes" \
    -g 30 -keyint_min 30 -sc_threshold 0 -an "$fixture_work/$segment.mp4"
done
printf "file '%s/prefix.mp4'\nfile '%s/tail.mp4'\n" "$fixture_work" "$fixture_work" > "$fixture_work/concat.txt"
ffmpeg -hide_banner -loglevel error -y \
  -f concat -safe 0 -i "$fixture_work/concat.txt" -c copy "$fixture_work/video.mp4"
ffmpeg -hide_banner -loglevel error -y \
  -i "$fixture_work/video.mp4" -f lavfi -t 0.5 -i sine=frequency=440:sample_rate=44100 \
  -map 0:v -map 1:a -c:v copy -c:a aac -b:a 64k \
  app/src/androidTest/assets/quickcut_unequal_tail.mp4
rm -rf "$fixture_work"

# Run the native boundary checks first to collect focused failures quickly.
# The unfiltered suite below remains mandatory after this preflight succeeds.
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hebi.quickcut.media.NativeSmartCutInstrumentedTest
# Remove preflight results so an up-to-date task or stale XML cannot satisfy the
# full-suite gate. Only generated instrumentation output is removed.
rm -rf app/build/outputs/androidTest-results/connected
gradle --no-daemon :app:connectedDebugAndroidTest
python3 - <<'PYCODE'
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
if len(cases) < 99:
    raise SystemExit(f'Expected at least 99 distinct full-suite tests, found {len(cases)}')
print(f'Full instrumentation suite verified: {len(cases)} distinct tests, no failures, errors, or skips')
PYCODE
# UiAutomation may return no bitmap. This optional review image must not turn
# a successful instrumentation run into a test failure.
if ! adb pull /sdcard/Android/data/com.hebi.quickcut/files/quickcut-fast-review.png quickcut-fast-review.png; then
  echo "::warning::Optional fast-review screenshot was unavailable; instrumentation results are unchanged."
fi
echo "QuickCut Android export instrumentation tests passed"


