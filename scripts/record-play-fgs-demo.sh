#!/usr/bin/env bash
set -euo pipefail
mkdir -p quickcut-play-fgs
adb shell settings put system screen_off_timeout 600000
adb shell screenrecord --size 720x1600 --bit-rate 2000000 --time-limit 180 \
  /data/local/tmp/quickcut-play-demo.mp4 > quickcut-play-fgs/recorder.log 2>&1 &
recorder=$!
finish_recording() {
  timeout 10s adb shell pkill -2 screenrecord || true
  wait "$recorder" || true
}
trap finish_recording EXIT
sleep 2
status=0
gradle --no-daemon -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hebi.quickcut.export.PlayForegroundDemoInstrumentedTest || status=$?
if ! kill -0 "$recorder" 2>/dev/null; then
  echo '::error::The recorder ended before the demonstration scenario completed.'
  status=1
fi
finish_recording
trap - EXIT
adb pull /data/local/tmp/quickcut-play-demo.mp4 quickcut-play-fgs/demo.mp4
adb pull /sdcard/Android/data/com.hebi.quickcut/files/play-fgs-demo quickcut-play-fgs/evidence || status=1
cp -a app/build/outputs/androidTest-results/connected quickcut-play-fgs/results
if [[ "$status" -ne 0 ]]; then exit "$status"; fi
python3 - <<'PY'
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
root = Path('quickcut-play-fgs')
reports = list((root / 'results').rglob('TEST-*.xml'))
assert reports, 'Missing real device test results'
cases = []
for report in reports:
    tree = ET.parse(report).getroot()
    assert all(int(tree.get(k, '0')) == 0 for k in ('failures', 'errors', 'skipped'))
    cases.extend(tree.iter('testcase'))
assert len(cases) == 1 and cases[0].find('failure') is None
assert list((root / 'evidence').rglob('passed.txt')), 'Missing successful scenario evidence'
duration = float(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries',
    'format=duration', '-of', 'default=noprint_wrappers=1:nokey=1', str(root / 'demo.mp4')]))
assert 10 < duration < 179, 'Recording was missing or reached its hard time limit'
print(f'Real foreground-service demonstration passed; video duration {duration:.1f}s')
PY
