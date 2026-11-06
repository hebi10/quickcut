#!/usr/bin/env bash
set -euo pipefail

mode="${1:?Provide media3 or date}"
case "$mode" in
  media3) selector="com.hebi.quickcut.export.BackgroundMedia3ExportInstrumentedTest";;
  date) selector="com.hebi.quickcut.media.BatchDateExportInstrumentedTest";;
  *) echo "Invalid probe mode" >&2; exit 2;;
esac
output="quickcut-api29-probe/$mode"
mkdir -p "$output"
rm -rf app/build/outputs/androidTest-results/connected app/build/reports/androidTests/connected
timeout --kill-after=2s 10s adb logcat -c
status=0
timeout --kill-after=30s 12m gradle --no-daemon   -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true   ":app:connectedDebugAndroidTest"   "-Pandroid.testInstrumentationRunnerArguments.class=$selector" || status=$?
printf 'gradle_exit=%s\nmode=%s\nselector=%s\n' "$status" "$mode" "$selector" > "$output/status.txt"
timeout --kill-after=2s 15s adb logcat -d -v threadtime   -s QuickCutApi29Probe:I TestRunner:I AndroidRuntime:E MediaCodec:E OMXNodeInstance:E   > "$output/filtered-logcat.txt" || true
if [[ -d app/build/outputs/androidTest-results/connected ]]; then
  mkdir -p "$output/results"
  cp -a app/build/outputs/androidTest-results/connected/. "$output/results/"
fi
python3 - "$output" "$selector" "$status" <<'PY'
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

output, expected_class, gradle_status = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3])
cases = []
for path in sorted((output / 'results').rglob('TEST-*.xml')):
    root = ET.parse(path).getroot()
    for case in root.iter('testcase'):
        identity = (case.get('classname'), case.get('name'))
        cases.append(identity)
    for suite in root.iter('testsuite'):
        if any(int(suite.get(field, '0')) for field in ('failures', 'errors', 'skipped')):
            raise SystemExit(f'Focused suite failed or skipped: {path}')
(output / 'case-identities.txt').write_text(
    '\n'.join(f'{cls}#{name}' for cls, name in cases) + '\n', encoding='utf-8'
)
if gradle_status:
    raise SystemExit(f'Focused Gradle command failed or timed out: {gradle_status}')
if len(cases) != 2 or len(set(cases)) != 2 or any(cls != expected_class for cls, _ in cases):
    raise SystemExit(f'Expected exactly two focused cases from {expected_class}, got {cases}')
print('Exactly two focused tests passed; no skipped, failed, or stale cases')
PY
