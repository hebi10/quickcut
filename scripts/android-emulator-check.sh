#!/usr/bin/env bash
set -euo pipefail

on_failure() {
  local status="$?"
  if [ "$status" -ne 0 ]; then
    echo "::error::QuickCut emulator smoke test failed (exit $status)"
    adb logcat -d -s AndroidRuntime:E | tail -100 || true
  fi
}
trap on_failure EXIT

# An older version-code fixture uses this runner's same debug key. This checks
# Android's update/data retention path, not compatibility with old distributed APKs.
gradle --no-daemon -PquickcutTestVersionCode=5 :app:assembleDebug
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
if [[ ! $(adb shell dumpsys package com.hebi.quickcut) =~ versionCode=5[[:space:]] ]]; then
  echo "::error::Older-version update fixture was not installed"
  exit 1
fi
adb shell run-as com.hebi.quickcut mkdir -p files
printf 'quickcut-update-data' | adb shell run-as com.hebi.quickcut sh -c '"cat > files/update-sentinel"'
gradle --no-daemon :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
if [[ ! $(adb shell dumpsys package com.hebi.quickcut) =~ versionCode=6[[:space:]] ]]; then
  echo "::error::Update candidate was not installed"
  exit 1
fi
if [ "$(adb shell run-as com.hebi.quickcut cat files/update-sentinel | tr -d '\r')" != "quickcut-update-data" ]; then
  echo "::error::Same-signature update did not retain application data"
  exit 1
fi
adb shell run-as com.hebi.quickcut rm files/update-sentinel
echo "Same-signature version-code 5 to 6 update retained application data"
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

gradle --no-daemon :app:connectedDebugAndroidTest
echo "QuickCut Android export instrumentation tests passed"


