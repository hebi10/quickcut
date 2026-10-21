#!/usr/bin/env bash
set -euo pipefail

on_failure() {
  local status="$?"
  if [ "$status" -ne 0 ]; then
    echo "::error::QuickCut emulator smoke test failed (exit $status)"
    if [ "$(timeout --kill-after=2s 5s adb get-state 2>/dev/null)" = device ]; then
      timeout --kill-after=2s 10s adb logcat -d -s AndroidRuntime:E | tail -100 || true
      if ! timeout --kill-after=2s 10s adb pull /sdcard/Android/data/com.hebi.quickcut/files/native-diagnostics quickcut-native-diagnostics; then
        echo "::warning::Native failure diagnostics were unavailable; the original failure is unchanged."
      fi
    else
      echo "::warning::Emulator is unavailable; skipping device diagnostics and preserving the original failure."
    fi
  fi
  return "$status"
}
trap on_failure EXIT

# An older version-code fixture uses this runner's same debug key. This checks
# Android's update/data retention path, not compatibility with old distributed APKs.
gradle --no-daemon -PquickcutTestVersionCode=14 :app:assembleDebug
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
if [[ ! $(adb shell dumpsys package com.hebi.quickcut) =~ versionCode=14[[:space:]] ]]; then
  echo "::error::Older-version update fixture was not installed"
  exit 1
fi
adb shell run-as com.hebi.quickcut mkdir -p files
printf 'quickcut-update-data' | adb shell run-as com.hebi.quickcut sh -c '"cat > files/update-sentinel"'
gradle --no-daemon :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
if [[ ! $(adb shell dumpsys package com.hebi.quickcut) =~ versionCode=15[[:space:]] ]]; then
  echo "::error::Update candidate was not installed"
  exit 1
fi
if [ "$(adb shell run-as com.hebi.quickcut cat files/update-sentinel | tr -d '\r')" != "quickcut-update-data" ]; then
  echo "::error::Same-signature update did not retain application data"
  exit 1
fi
adb shell run-as com.hebi.quickcut rm files/update-sentinel
echo "Same-signature version-code 14 to 15 update retained application data"
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

# Measured sync samples include microseconds which cannot be represented in ms.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i testsrc2=size=320x240:rate=30000/1001 -t 8 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -bf 0 \
  -g 50 -keyint_min 50 -sc_threshold 0 -an \
  app/src/androidTest/assets/quickcut_fractional.mp4

# Closed GOP B-frames reproduce downloaded videos with reordered timestamps.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i testsrc2=size=320x240:rate=30 \
  -f lavfi -i 'aevalsrc=0.1*sin(2*PI*(440+220*floor(t/2))*t):s=44100' \
  -t 8 -c:v libx264 -preset medium -pix_fmt yuv420p \
  -bf 2 -g 60 -keyint_min 60 -sc_threshold 0 -flags +cgop \
  -c:a aac -b:a 64k -shortest \
  app/src/androidTest/assets/quickcut_bframes.mp4

# Preserve a phone-style display transform without rotating encoded pixels.
ffmpeg -hide_banner -loglevel error -y \
  -display_rotation:v:0 -90 -i app/src/androidTest/assets/quickcut_bframes.mp4 -c copy \
  app/src/androidTest/assets/quickcut_rotated.mp4
python3 - <<'PYROTATION'
import json, subprocess
stream = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
    '-show_entries', 'stream_side_data', '-of', 'json',
    'app/src/androidTest/assets/quickcut_rotated.mp4']))['streams'][0]
assert any(data.get('rotation') == -90 for data in stream.get('side_data_list', [])), 'Rotated fixture needs an actual display matrix'
PYROTATION

# SDR HEVC with closed GOPs and reordered frames, independently encoded by CLI.
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i testsrc2=size=320x240:rate=30 \
  -f lavfi -i sine=frequency=660:sample_rate=44100 \
  -t 8 -c:v libx265 -preset fast -pix_fmt yuv420p \
  -x265-params pools=1:frame-threads=1:keyint=60:min-keyint=60:scenecut=0:open-gop=0:bframes=2:log-level=error \
  -tag:v hvc1 -c:a aac -b:a 64k -shortest \
  app/src/androidTest/assets/quickcut_hevc.mp4
python3 - <<'PYHEVC'
import json, pathlib, subprocess
source = pathlib.Path('app/src/androidTest/assets/quickcut_hevc.mp4')
data = bytearray(source.read_bytes())
packet = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
    '-show_packets', '-show_entries', 'packet=pos,size', '-of', 'json', str(source)]))['packets'][0]
at = int(packet['pos']); end = at + int(packet['size']); patched = False
while at < end:
    length = int.from_bytes(data[at:at + 4], 'big'); at += 4
    assert length >= 2 and at + length <= end
    if (data[at] >> 1) & 63 in (19, 20):
        assert length >= 3 and data[at + 2] & 0x80
        data[at + 2] |= 0x40
        patched = True
    at += length
assert patched, 'HEVC fixture needs a first IDR'
source.with_name('quickcut_hevc_no_output.mp4').write_bytes(data)
PYHEVC

ffmpeg -v error -y -i app/src/androidTest/assets/quickcut_bframes.mp4 \
  -c:v copy -c:a libmp3lame -ac 2 -b:a 192k app/src/androidTest/assets/quickcut_mp3.mp4
ffmpeg -v error -y -i app/src/androidTest/assets/quickcut_bframes.mp4 \
  -c:v copy -c:a ac3 -ac 6 -ar 48000 -b:a 384k app/src/androidTest/assets/quickcut_ac3.mp4
for hdr_variant in sdr pq hlg; do
  hdr_primaries=bt2020
  hdr_matrix=bt2020nc
  hdr_extra=''
  case "$hdr_variant" in
    sdr) hdr_primaries=bt709; hdr_matrix=bt709; hdr_transfer=bt709 ;;
    pq) hdr_transfer=smpte2084; hdr_extra=':master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50):max-cll=1000,400' ;;
    hlg) hdr_transfer=arib-std-b67 ;;
  esac
  ffmpeg -v error -y -f lavfi -i 'testsrc2=size=320x240:rate=30' \
    -f lavfi -i 'sine=frequency=660:sample_rate=44100' -t 8 \
    -c:v libx265 -preset fast -pix_fmt yuv420p10le -color_range tv \
    -color_primaries "$hdr_primaries" -colorspace "$hdr_matrix" -color_trc "$hdr_transfer" \
    -x265-params "pools=1:frame-threads=1:keyint=60:min-keyint=60:scenecut=0:open-gop=0:bframes=2:log-level=error:repeat-headers=1$hdr_extra" \
    -tag:v hvc1 -c:a aac -b:a 64k -shortest "app/src/androidTest/assets/quickcut_main10_$hdr_variant.mp4"
done

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
adb shell rm -rf /data/local/tmp/quickcut-main10
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hebi.quickcut.media.NativeSmartCutInstrumentedTest
rm -rf quickcut-main10-preflight
adb pull /data/local/tmp/quickcut-main10 quickcut-main10-preflight
python3 scripts/verify-android-main10.py quickcut-main10-preflight
# Remove preflight results so an up-to-date task or stale XML cannot satisfy the
# full-suite gate. Only generated instrumentation output is removed.
rm -rf app/build/outputs/androidTest-results/connected
adb shell rm -rf /data/local/tmp/quickcut-main10
gradle --no-daemon :app:connectedDebugAndroidTest
rm -rf quickcut-main10-full
adb pull /data/local/tmp/quickcut-main10 quickcut-main10-full
python3 scripts/verify-android-main10.py quickcut-main10-full
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


