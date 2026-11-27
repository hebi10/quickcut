#!/usr/bin/env bash
set -euo pipefail
adb install "$RUNNER_TEMP/quickcut-qa.apk"
adb shell pm grant com.hebi.quickcut android.permission.POST_NOTIFICATIONS
adb shell input keyevent 82
adb logcat -c
adb push "$RUNNER_TEMP/QuickCutDex03.mp4" /sdcard/Download/QuickCutDex03.mp4
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/QuickCutDex03.mp4
python3 scripts/android-startup-check.py
python3 scripts/quickcut-dex03-qa.py device
