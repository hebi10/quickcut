#!/usr/bin/env bash
set -euo pipefail

output=quickcut-store-scenic
mkdir -p "$output"
adb shell settings put system screen_off_timeout 600000
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put secure anr_show_background 0
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell rm -rf /data/local/tmp/quickcut-store-scenic

status=0
gradle --no-daemon -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hebi.quickcut.ui.StoreScenicCaptureInstrumentedTest \
  || status=$?

adb pull /data/local/tmp/quickcut-store-scenic "$output/evidence" || status=1
adb logcat -d -v threadtime > "$output/logcat.txt" || true
adb exec-out screencap -p > "$output/device-final.png" || true
if [[ -d app/build/outputs/androidTest-results/connected ]]; then
  cp -a app/build/outputs/androidTest-results/connected "$output/results"
fi
if [[ -d app/build/reports/androidTests/connected ]]; then
  cp -a app/build/reports/androidTests/connected "$output/reports"
fi
cp app/src/androidTest/assets/quickcut_store_scenic.mp4 "$output/source.mp4"
if [[ "$status" -ne 0 ]]; then exit "$status"; fi

python3 - <<'PY'
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path("quickcut-store-scenic")
reports = list((root / "results").rglob("TEST-*.xml"))
assert reports, "Missing real device test results"
cases = []
for report in reports:
    suite = ET.parse(report).getroot()
    assert all(int(suite.get(key, "0")) == 0 for key in ("failures", "errors", "skipped"))
    cases.extend(suite.iter("testcase"))
assert len(cases) == 1, "Only the focused scenic capture test may run"
assert cases[0].get("classname") == "com.hebi.quickcut.ui.StoreScenicCaptureInstrumentedTest"
assert cases[0].get("name") == "captureQuickAndDetailedWithNewScenicVideo"
assert cases[0].find("failure") is None
evidence = root / "evidence"
assert (evidence / "passed.txt").is_file(), "Missing successful capture evidence"
record = json.loads((evidence / "evidence.json").read_text())
assert record["encodedDurationMs"] == 8000
assert 8000 <= record["sourceDurationMs"] <= 8050
assert record["leadingRemovedMs"] == 4000
assert record["trailingRemovedMs"] == 623
assert record["selectedStartMs"] == 4000
assert record["selectedEndMs"] == record["sourceDurationMs"] - 623
assert record["resultDurationMs"] == record["selectedEndMs"] - 4000
source_hash = hashlib.sha256((root / "source.mp4").read_bytes()).hexdigest()
assert record["sourceSha256"] == source_hash, "Imported source differs from the generated file"
assert len(record["captures"]) == 2
expected_names = {"store_scenic_quick_trim.png", "store_scenic_detailed.png"}
assert {item["file"] for item in record["captures"]} == expected_names
for item in record["captures"]:
    image = evidence / item["file"]
    assert image.is_file() and image.stat().st_size > 10000
    assert image.read_bytes().startswith(b"\x89PNG\r\n\x1a\n")
    assert hashlib.sha256(image.read_bytes()).hexdigest() == item["sha256"]
    assert all(item[key] for key in ("frameMetadataObserved", "visibleScreenshotSceneVerified", "playerReady", "paused"))
    assert item["verificationType"] == "uiAutomationScreenshotContentRegion"
    assert item["scene"]["accepted"]
    assert item["timelineThumbnailVisible"] == (item["file"] == "store_scenic_detailed.png")
print("Focused actual-device capture passed; new source hash and both raw screenshots verified.")
PY
