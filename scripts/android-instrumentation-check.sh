#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
RESULTS=app/build/outputs/androidTest-results/connected
REPORTS=app/build/reports/androidTests/connected
PREFLIGHT=quickcut-native-preflight-results
first_failure=0
last_status=0

run_checked() {
  local label="$1"
  shift
  last_status=0
  "$@" || last_status=$?
  if [[ "$last_status" -ne 0 ]]; then
    echo "::error::$label failed (exit $last_status)"
    if [[ "$first_failure" -eq 0 ]]; then first_failure="$last_status"; fi
  fi
  return 0
}

# Preserve focused evidence before the mandatory unfiltered run can replace it.
rm -rf "$PREFLIGHT" "$RESULTS" "$REPORTS" quickcut-main10-preflight
mkdir -p "$PREFLIGHT/results" "$PREFLIGHT/reports"
run_checked "Clear preflight Main10 evidence" timeout --kill-after=2s 10s adb shell rm -rf /data/local/tmp/quickcut-main10
run_checked "Native preflight" timeout --kill-after=30s 10m gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hebi.quickcut.media.NativeSmartCutInstrumentedTest
preflight_gradle_status="$last_status"
if [[ -d "$RESULTS" ]]; then
  run_checked "Preserve preflight XML" cp -a "$RESULTS/." "$PREFLIGHT/results/"
else
  run_checked "Require preflight XML directory" false
fi
if [[ -d "$REPORTS" ]]; then
  run_checked "Preserve preflight reports" cp -a "$REPORTS/." "$PREFLIGHT/reports/"
fi
# Unique device directories survive app uninstall; snapshot preflight evidence too.
if ! timeout --kill-after=2s 10s adb pull /data/local/tmp/quickcut-native-diagnostics "$PREFLIGHT/diagnostics"; then
  echo "::warning::Optional preflight native diagnostics were unavailable."
fi
run_checked "Pull preflight Main10 evidence" timeout --kill-after=2s 10s adb pull /data/local/tmp/quickcut-main10 quickcut-main10-preflight
run_checked "Verify preflight Main10 evidence" python3 "$SCRIPT_DIR/verify-android-main10.py" quickcut-main10-preflight
printf 'gradle_exit=%s\nphase_exit=%s\n' "$preflight_gradle_status" "$first_failure" > "$PREFLIGHT/status.txt"

# Native failures must not hide UI/recovery results. Always run the full suite,
# using fresh XML so a preflight report cannot satisfy the unfiltered gate.
rm -rf "$RESULTS" "$REPORTS" quickcut-main10-full
run_checked "Clear full-suite Main10 evidence" timeout --kill-after=2s 10s adb shell rm -rf /data/local/tmp/quickcut-main10
run_checked "Full instrumentation suite" timeout --kill-after=30s 15m gradle --no-daemon :app:connectedDebugAndroidTest
run_checked "Pull full-suite Main10 evidence" timeout --kill-after=2s 10s adb pull /data/local/tmp/quickcut-main10 quickcut-main10-full
run_checked "Verify full-suite Main10 evidence" python3 "$SCRIPT_DIR/verify-android-main10.py" quickcut-main10-full
run_checked "Verify full-suite XML" python3 - <<'PYCODE'
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
if len(cases) < 152:
    raise SystemExit(f'Expected at least 152 distinct full-suite tests, found {len(cases)}')
print(f'Full instrumentation suite verified: {len(cases)} distinct tests, no failures, errors, or skips')
PYCODE
# An optional review image cannot replace a required test or verification failure.
if ! timeout --kill-after=2s 10s adb pull /sdcard/Android/data/com.hebi.quickcut/files/quickcut-fast-review.png quickcut-fast-review.png; then
  echo "::warning::Optional fast-review screenshot was unavailable; instrumentation results are unchanged."
fi
if ! timeout --kill-after=2s 10s adb pull /data/local/tmp/quickcut-design-review quickcut-design-review; then
  echo "::warning::Optional design screenshots were unavailable; instrumentation results are unchanged."
fi
if [[ "$first_failure" -ne 0 ]]; then
  echo "::error::Instrumentation failed; preserving the first failure (exit $first_failure)."
  exit "$first_failure"
fi
echo "QuickCut Android export instrumentation tests passed"
