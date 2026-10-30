#!/usr/bin/env python3
"""Exercise the real smoke script's failure trap with an unavailable device."""

import os
from pathlib import Path
import json
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("android-emulator-check.sh").resolve()


class EmulatorFailureDiagnosticsTest(unittest.TestCase):
    def run_failure(self, state: str, deadline: int):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gradle = root / "gradle"
            gradle.write_text("#!/bin/sh\nexit 23\n", encoding="utf-8")
            gradle.chmod(0o755)
            adb = root / "adb"
            adb.write_text(
                "#!/bin/sh\n"
                'if [ "$1" = get-state ]; then\n'
                '  [ "$DIAGNOSTIC_DEVICE_STATE" != offline ] || exit 1\n'
                "  echo device\n  exit 0\nfi\n"
                'if [ "$DIAGNOSTIC_DEVICE_STATE" = online ]; then\n'
                "  echo diagnostic-output \"$*\"\n  exit 0\nfi\n"
                "exec sleep 60\n",
                encoding="utf-8",
            )
            adb.chmod(0o755)
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}",
                       DIAGNOSTIC_DEVICE_STATE=state)
            process = subprocess.Popen(
                ["bash", str(SCRIPT)], cwd=root, env=env,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, start_new_session=True,
            )
            try:
                output, _ = process.communicate(timeout=deadline)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
                self.fail("Device diagnostics blocked reporting the original failure")
            self.assertEqual(process.returncode, 23, output)
            return output

    def test_offline_device_does_not_delay_reporting_the_original_failure(self):
        self.run_failure("offline", 3)

    def test_available_diagnostics_keep_the_original_failure_status(self):
        output = self.run_failure("online", 3)
        self.assertIn("diagnostic-output", output)
        self.assertIn("/data/local/tmp/quickcut-native-diagnostics", output)

    def test_device_lost_after_probe_has_bounded_diagnostics(self):
        self.run_failure("disconnected", 25)


class EmulatorUpdateVerificationTest(unittest.TestCase):
    """Exercise the upgrade checks with external build/device operations stubbed.

    Hardcoded version codes break valid upgrades; accepting a wrong installed
    version or ignoring a lost sentinel must never report upgrade success.
    """

    def run_upgrade(self, current, *, older_installed=None, candidate_installed=None,
                    lose_sentinel=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'app').mkdir()
            shutil.copy2(SCRIPT, root / 'scripts' / SCRIPT.name)
            shutil.copy2(SCRIPT.with_name('verify-release-gate.py'),
                         root / 'scripts' / 'verify-release-gate.py')
            (root / 'app' / 'build.gradle.kts').write_text(
                f'versionName = "0.4.12"\nversionCode = {current}\n', encoding='utf-8')
            # Gradle produces a stand-in artifact containing the requested code.
            # adb applies that artifact, preserving app data unless a case models
            # the actual update/data-loss failure we need the shell to reject.
            gradle = root / 'gradle'
            gradle.write_text(f'#!{sys.executable}\n' + '''
import os
from pathlib import Path
import sys
code = int(os.environ['FIXTURE_CURRENT_CODE'])
for arg in sys.argv[1:]:
    if arg.startswith('-PquickcutTestVersionCode='):
        code = int(arg.split('=', 1)[1])
apk = Path('app/build/outputs/apk/debug/app-debug.apk')
apk.parent.mkdir(parents=True, exist_ok=True)
apk.write_text(str(code))
''', encoding='utf-8')
            adb = root / 'adb'
            adb.write_text(f'#!{sys.executable}\n' + '''
import json
import os
from pathlib import Path
import sys
path = Path(os.environ['FIXTURE_DEVICE_STATE'])
state = json.loads(path.read_text()) if path.exists() else {'installs': [], 'sentinel': None}
args = sys.argv[1:]
def save():
    path.write_text(json.dumps(state))
if args == ['get-state']:
    sys.exit(1)
if args[:1] == ['install']:
    requested = int(Path(args[-1]).read_text())
    override = os.environ['FIXTURE_OLDER_INSTALLED' if not state['installs'] else 'FIXTURE_CANDIDATE_INSTALLED']
    state['installed'] = int(override) if override else requested
    state['installs'].append(requested)
    if len(state['installs']) == 2 and os.environ['FIXTURE_LOSE_SENTINEL'] == '1':
        state['sentinel'] = None
    save()
    print('Success')
elif args == ['shell', 'dumpsys', 'package', 'com.hebi.quickcut']:
    print('  versionCode=' + str(state['installed']) + ' minSdk=29 targetSdk=36')
elif args == ['shell', 'run-as', 'com.hebi.quickcut', 'mkdir', '-p', 'files']:
    pass
elif args[:5] == ['shell', 'run-as', 'com.hebi.quickcut', 'sh', '-c']:
    if args[5:] != ['"cat > files/update-sentinel"']:
        sys.exit(98)
    state['sentinel'] = sys.stdin.read()
    save()
elif args == ['shell', 'run-as', 'com.hebi.quickcut', 'cat', 'files/update-sentinel']:
    if state['sentinel'] is None:
        sys.exit(1)
    print(state['sentinel'], end='')
elif args == ['shell', 'run-as', 'com.hebi.quickcut', 'rm', 'files/update-sentinel']:
    state['sentinel'] = None
    state['sentinel_removed'] = True
    save()
elif args[:4] == ['shell', 'am', 'force-stop', 'com.google.android.apps.nexuslauncher']:
    state['reached_ui_boundary'] = True
    save()
    sys.exit(31)
else:
    print('Unexpected adb call: ' + repr(args), file=sys.stderr)
    sys.exit(99)
''', encoding='utf-8')
            gradle.chmod(0o755)
            adb.chmod(0o755)
            state_path = root / 'device.json'
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}",
                       PYTHONDONTWRITEBYTECODE='1',
                       FIXTURE_CURRENT_CODE=str(current), FIXTURE_DEVICE_STATE=str(state_path),
                       FIXTURE_OLDER_INSTALLED='' if older_installed is None else str(older_installed),
                       FIXTURE_CANDIDATE_INSTALLED='' if candidate_installed is None else str(candidate_installed),
                       FIXTURE_LOSE_SENTINEL='1' if lose_sentinel else '0')
            process = subprocess.run(['bash', str(root / 'scripts' / SCRIPT.name)],
                                     cwd=root, env=env, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, timeout=10)
            state = json.loads(state_path.read_text()) if state_path.exists() else {}
            return process, state

    def test_current_and_future_versions_upgrade_from_immediate_predecessor(self):
        for current, expected_installs in ((17, [16, 17]), (42, [41, 42])):
            with self.subTest(current=current):
                process, state = self.run_upgrade(current)
                self.assertEqual(process.returncode, 31, process.stdout)
                self.assertEqual(state['installs'], expected_installs)
                self.assertTrue(state.get('sentinel_removed'), process.stdout)
                self.assertTrue(state.get('reached_ui_boundary'), process.stdout)
                self.assertIn(f'{current - 1} to {current} update retained application data',
                              process.stdout)

    def assert_upgrade_rejected(self, process, state, reason):
        self.assertEqual(process.returncode, 1, process.stdout)
        self.assertIn(reason, process.stdout)
        self.assertNotIn('update retained application data', process.stdout)
        self.assertFalse(state.get('reached_ui_boundary'), process.stdout)

    def test_wrong_older_installation_is_rejected_before_candidate_install(self):
        process, state = self.run_upgrade(17, older_installed=15)
        self.assert_upgrade_rejected(process, state, 'Older-version update fixture was not installed')
        self.assertEqual(len(state['installs']), 1)

    def test_stale_or_prefix_matching_candidate_is_rejected(self):
        for candidate in (16, 170):
            with self.subTest(candidate=candidate):
                process, state = self.run_upgrade(17, candidate_installed=candidate)
                self.assert_upgrade_rejected(process, state, 'Update candidate was not installed')

    def test_lost_application_data_is_rejected(self):
        process, state = self.run_upgrade(17, lose_sentinel=True)
        self.assert_upgrade_rejected(process, state, 'Same-signature update did not retain application data')


if __name__ == "__main__":
    unittest.main()
