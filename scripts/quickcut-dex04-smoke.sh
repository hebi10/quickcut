#!/usr/bin/env bash
set -euo pipefail
adb install "$RUNNER_TEMP/quickcut-baseline-qa.apk"
if (( QUICKCUT_EMULATOR_API >= 33 )); then
  adb shell pm grant com.hebi.quickcut android.permission.POST_NOTIFICATIONS
fi
adb shell input keyevent 82
adb logcat -c
adb push "$RUNNER_TEMP/QuickCutDex04.mp4" /sdcard/Download/QuickCutDex04.mp4
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/QuickCutDex04.mp4
python3 scripts/android-startup-check.py
python3 scripts/quickcut-dex04-qa.py device
