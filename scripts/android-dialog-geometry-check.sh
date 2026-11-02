#!/usr/bin/env bash
# Only for the disposable API 36 CI emulator, after a successful full suite.
set -euo pipefail

RESULTS=app/build/outputs/androidTest-results/connected
REPORTS=app/build/reports/androidTests/connected
EVIDENCE=quickcut-real-dialog-results
REMOTE=/data/local/tmp/quickcut-design-review
CLASS=com.hebi.quickcut.batch.BatchFastAdjustmentInstrumentedTest
SELECTORS="$CLASS#reviewKeepsBothSaveActionsInFixedFooter,$CLASS#templateManagerRemainsUsable,com.hebi.quickcut.ui.PrivacyPolicyInstrumentedTest#privacyCanBeReadOfflineAndClosedFromHome"
first_failure=0
last_status=0
size_changed=0
density_changed=0
font_changed=0
auto_rotation_changed=0
user_rotation_changed=0

adb_bounded() { timeout --kill-after=2s 10s adb "$@"; }
run_checked() {
  local label="$1"
  shift
  last_status=0
  "$@" || last_status=$?
  if (( last_status != 0 )); then
    echo "::error::$label failed (exit $last_status)"
    if (( first_failure == 0 )); then first_failure=$last_status; fi
  fi
  return 0
}

# A connected phone, local environment, or other emulator API must never be changed.
[[ "${CI:-}" == true && "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Disposable CI emulator required' >&2; exit 1; }
serial=$(adb_bounded get-serialno)
[[ "$serial" == emulator-* ]] || { echo 'Refusing non-emulator serial' >&2; exit 1; }
[[ $(adb_bounded shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
[[ $(adb_bounded shell getprop ro.build.version.sdk | tr -d '\r') == 36 ]]
export ANDROID_SERIAL="$serial"

[[ -d "$RESULTS" && -n $(find "$RESULTS" -name 'TEST-*.xml' -print -quit) ]]
rm -rf "$EVIDENCE"
mkdir -p "$EVIDENCE/full-suite-original/results"
cp -a "$RESULTS/." "$EVIDENCE/full-suite-original/results/"
reports_present=0
if [[ -d "$REPORTS" ]]; then
  reports_present=1
  mkdir -p "$EVIDENCE/full-suite-original/reports"
  cp -a "$REPORTS/." "$EVIDENCE/full-suite-original/reports/"
fi

hash_full() {
  python3 - "$RESULTS" "$REPORTS" <<'PY'
import hashlib, json, pathlib, sys
result = {}
for name, directory in zip(('results', 'reports'), sys.argv[1:]):
    base = pathlib.Path(directory)
    result[name] = None if not base.exists() else {
        str(path.relative_to(base)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(base.rglob('*')) if path.is_file()
    }
print(json.dumps(result, sort_keys=True))
PY
}
hash_full > "$EVIDENCE/full-suite-before.json"

original_size=$(adb_bounded shell wm size | tr -d '\r')
original_density=$(adb_bounded shell wm density | tr -d '\r')
size_override=$(sed -n 's/^Override size: //p' <<< "$original_size")
density_override=$(sed -n 's/^Override density: //p' <<< "$original_density")
original_font=$(adb_bounded shell settings get system font_scale | tr -d '\r')
original_auto_rotation=$(adb_bounded shell settings get system accelerometer_rotation | tr -d '\r')
original_user_rotation=$(adb_bounded shell settings get system user_rotation | tr -d '\r')
printf '%s\n%s\nfont_scale=%s\naccelerometer_rotation=%s\nuser_rotation=%s\n' \
  "$original_size" "$original_density" "$original_font" "$original_auto_rotation" "$original_user_rotation" > "$EVIDENCE/device-before.txt"

restore_setting() {
  if [[ "$2" == null ]]; then adb_bounded shell settings delete system "$1";
  else adb_bounded shell settings put system "$1" "$2"; fi
}
host_configuration() {
  adb_bounded shell wm size | tr -d '\r' || return
  adb_bounded shell wm density | tr -d '\r' || return
  local key value
  for key in font_scale accelerometer_rotation user_rotation; do
    value=$(adb_bounded shell settings get system "$key" | tr -d '\r') || return
    printf '%s=%s\n' "$key" "$value"
  done
}
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  if (( first_failure == 0 && status != 0 )); then first_failure=$status; fi
  if (( size_changed )); then run_checked 'Restore size' adb_bounded shell wm size "${size_override:-reset}"; fi
  if (( density_changed )); then run_checked 'Restore density' adb_bounded shell wm density "${density_override:-reset}"; fi
  if (( font_changed )); then run_checked 'Restore font' restore_setting font_scale "$original_font"; fi
  if (( auto_rotation_changed )); then run_checked 'Restore auto rotation' restore_setting accelerometer_rotation "$original_auto_rotation"; fi
  if (( user_rotation_changed )); then run_checked 'Restore user rotation' restore_setting user_rotation "$original_user_rotation"; fi
  run_checked 'Read restored settings' host_configuration > "$EVIDENCE/device-after.txt"
  run_checked 'Verify exact restored settings' cmp "$EVIDENCE/device-before.txt" "$EVIDENCE/device-after.txt"
  run_checked 'Clear focused canonical XML/reports' rm -rf "$RESULTS" "$REPORTS"
  run_checked 'Restore full XML directory' mkdir -p "$RESULTS"
  run_checked 'Restore original full XML' cp -a "$EVIDENCE/full-suite-original/results/." "$RESULTS/"
  if (( reports_present )); then
    run_checked 'Restore reports directory' mkdir -p "$REPORTS"
    run_checked 'Restore original full reports' cp -a "$EVIDENCE/full-suite-original/reports/." "$REPORTS/"
  fi
  run_checked 'Hash restored full suite' hash_full > "$EVIDENCE/full-suite-after.json"
  run_checked 'Verify full-suite hash restoration' python3 - "$EVIDENCE" <<'PY'
import json, pathlib, sys
base = pathlib.Path(sys.argv[1])
before = json.loads((base / 'full-suite-before.json').read_text())
after = json.loads((base / 'full-suite-after.json').read_text())
restored = before == after
(base / 'restoration.json').write_text(json.dumps(dict(before=before, after=after, restored=restored), indent=2) + '\n')
if not restored: raise SystemExit('Original full-suite evidence was not restored exactly')
PY
  printf 'exit=%s\n' "$first_failure" > "$EVIDENCE/status.txt"
  exit "$first_failure"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Lock natural orientation before changing display geometry; touch only differing values.
if [[ "$original_auto_rotation" != 0 ]]; then
  auto_rotation_changed=1
  run_checked 'Lock auto rotation' adb_bounded shell settings put system accelerometer_rotation 0
fi
if [[ "$original_user_rotation" != 0 ]]; then
  user_rotation_changed=1
  run_checked 'Lock natural rotation' adb_bounded shell settings put system user_rotation 0
fi

verify_stage() {
  python3 - "$1" "$2" "$3" "$SELECTORS" <<'PY'
import json, pathlib, struct, sys, xml.etree.ElementTree as ET
base, width, font, selectors = sys.argv[1:]
base = pathlib.Path(base)
cases = []
for path in sorted((base / 'results').rglob('TEST-*.xml')):
    root = ET.parse(path).getroot()
    for suite in root.iter('testsuite'):
        if any(int(suite.get(key, '0')) for key in ('failures', 'errors', 'skipped')):
            raise SystemExit(f'Failed/skipped focused suite: {path}')
    for case in root.iter('testcase'):
        if any(case.find(key) is not None for key in ('failure', 'error', 'skipped')):
            raise SystemExit(f'Failed/skipped focused case: {path}')
        cases.append((case.get('classname'), case.get('name')))
expected = [tuple(selector.split('#', 1)) for selector in selectors.split(',')]
if sorted(cases) != sorted(expected):
    raise SystemExit(f'Expected exactly {expected}; found {cases}')
dimensions = {}
expected_display = {'narrow': (640, 1280), 'wide': (1680, 1440)}[base.name]
for kind in ('review', 'templates'):
    stem = f'batch_{kind}_{base.name}'
    configuration = json.loads((base / f'{stem}_configuration.json').read_text())
    if (configuration['stage'] != base.name or configuration['screenWidthDp'] != int(width)
            or abs(configuration['fontScale'] - float(font)) > 0.01 or configuration['densityDpi'] != 320):
        raise SystemExit(f'Actual Activity configuration disagrees: {configuration}')
    bounds = configuration['dialogBoundsInWindowPx']
    if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
        raise SystemExit(f'Empty dialog bounds: {bounds}')
    if (configuration['displayWidthPx'], configuration['displayHeightPx']) != expected_display:
        raise SystemExit(f'Actual screenshot display dimensions disagree: {configuration}')
    for suffix in ('.png', '_device.png'):
        path = base / (stem + suffix)
        header = path.read_bytes()[:24]
        if len(header) != 24 or header[:8] != b'\x89PNG\r\n\x1a\n' or header[12:16] != b'IHDR':
            raise SystemExit(f'Missing/invalid fresh PNG: {path}')
        dimensions[path.name] = struct.unpack('>II', header[16:24])
        if min(dimensions[path.name]) <= 0: raise SystemExit(f'Empty PNG: {path}')
        if suffix == '_device.png' and dimensions[path.name] != expected_display:
            raise SystemExit(f'Device PNG dimensions disagree with stage: {path}')
        if suffix == '.png' and any(abs(actual - expected) > 1 for actual, expected in
                zip(dimensions[path.name], (bounds[2] - bounds[0], bounds[3] - bounds[1]))):
            raise SystemExit(f'Tagged dialog PNG dimensions disagree with actual bounds: {path}')
(base / 'screenshot-dimensions.json').write_text(json.dumps(dimensions, indent=2) + '\n')
print(f'{base.name}: exactly three passing cases and all required real-window captures')
PY
}

for stage in narrow wide; do
  if [[ "$stage" == narrow ]]; then size=640x1280; width=320; font=1.6;
  else size=1680x1440; width=840; font=1.0; fi
  directory="$EVIDENCE/$stage"
  mkdir -p "$directory/results" "$directory/reports"
  # Clearing exactly these remote names prevents old captures from satisfying this run.
  remote_files=()
  for kind in review templates; do
    for suffix in .png _device.png _configuration.json; do remote_files+=("$REMOTE/batch_${kind}_${stage}${suffix}"); done
  done
  run_checked 'Clear stage captures' adb_bounded shell rm -f -- "${remote_files[@]}"
  size_changed=1
  run_checked "$stage size" adb_bounded shell wm size "$size"
  density_changed=1
  run_checked "$stage density" adb_bounded shell wm density 320
  font_changed=1
  run_checked "$stage font" adb_bounded shell settings put system font_scale "$font"
  run_checked "$stage host configuration" host_configuration > "$directory/host-configuration.txt"
  # No XML from the full suite or previous stage can satisfy the focused gate.
  run_checked 'Clear canonical focused results' rm -rf "$RESULTS" "$REPORTS"
  # TERM at 175s, KILL at 180s: the entire focused Gradle command is bounded by 3m.
  run_checked "$stage fresh instrumentation" timeout --kill-after=5s 175s gradle --no-daemon :app:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$SELECTORS" \
    "-Pandroid.testInstrumentationRunnerArguments.quickcutGeometryStage=$stage" \
    "-Pandroid.testInstrumentationRunnerArguments.quickcutExpectedWidthDp=$width" \
    "-Pandroid.testInstrumentationRunnerArguments.quickcutExpectedFontScale=$font"
  gradle_status=$last_status
  run_checked "$stage preserve XML" cp -a "$RESULTS/." "$directory/results/"
  if [[ -d "$REPORTS" ]]; then run_checked "$stage preserve reports" cp -a "$REPORTS/." "$directory/reports/"; fi
  for remote_file in "${remote_files[@]}"; do
    run_checked "$stage required capture ${remote_file##*/}" adb_bounded pull "$remote_file" "$directory/${remote_file##*/}"
  done
  run_checked "$stage focused XML and capture gate" verify_stage "$directory" "$width" "$font"
  printf 'gradle_exit=%s\nphase_exit=%s\n' "$gradle_status" "$first_failure" > "$directory/status.txt"
done
exit "$first_failure"
