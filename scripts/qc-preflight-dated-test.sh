#!/usr/bin/env bash
set -euo pipefail

gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
suite=com.hebi.quickcut.media.NativeSmartCutInstrumentedTest
adb shell am instrument -w -r \
    -e class "${suite}#originalDateDoesNotForceFullVideoEncoding,${suite}#originalDateCopyRemainsAvailableInFastOnlyMode" \
    com.hebi.quickcut.test/androidx.test.runner.AndroidJUnitRunner | tee dated-instrumentation.txt
if ! grep -F 'OK (2 tests)' dated-instrumentation.txt ||
    grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -2' dated-instrumentation.txt; then
    adb logcat -d -s QuickCutExport SmartCutFailureDiagnostics | tail -160
    exit 1
fi
