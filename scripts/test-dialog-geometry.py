#!/usr/bin/env python3
"""Execute the geometry helper against disposable, stateful adb/Gradle commands."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HELPER = Path(__file__).with_name('android-dialog-geometry-check.sh').resolve()
CLASS = 'com.hebi.quickcut.batch.BatchFastAdjustmentInstrumentedTest'
METHODS = ['reviewKeepsBothSaveActionsInFixedFooter', 'templateManagerRemainsUsable']
RESULTS = 'app/build/outputs/androidTest-results/connected'
REPORTS = 'app/build/reports/androidTests/connected'

FAKE = r'''#!/usr/bin/env python3
import json, os, pathlib, signal, struct, subprocess, sys
p = pathlib.Path(os.environ['FAKE_STATE'])
state = json.loads(p.read_text())
args = sys.argv[1:]
tool = pathlib.Path(sys.argv[0]).name
with pathlib.Path(os.environ['FAKE_LOG']).open('a') as out:
    out.write(json.dumps([tool] + args) + '\n')
def save(): p.write_text(json.dumps(state))
if tool == 'timeout':
    assert args[0].startswith('--kill-after=')
    assert args[1] in ('10s', '175s')
    if args[2] == 'gradle' and os.environ['FAKE_MODE'] in ('signal_term', 'signal_int'):
        sig = signal.SIGTERM if os.environ['FAKE_MODE'] == 'signal_term' else signal.SIGINT
        os.kill(os.getppid(), sig)
        sys.exit(128 + sig)
    sys.exit(subprocess.call(args[2:]))
if tool == 'adb':
    if args == ['get-serialno']: print(state['serial'])
    elif args[:2] == ['shell', 'getprop']:
        print('1' if args[2] == 'ro.kernel.qemu' else '36')
    elif args[:2] == ['shell', 'wm']:
        key = args[2]
        if len(args) == 3:
            print('Physical %s: %s' % (key, '1080x2400' if key == 'size' else '420'))
            if state[key] is not None: print('Override %s: %s' % (key, state[key]))
        else:
            state[key] = None if args[3] == 'reset' else args[3]
            save()
            if os.environ['FAKE_MODE'] in ('restore_failure', 'gradle_and_restore_failure') and key == 'size' and args[3] == '1200x2000': sys.exit(42)
    elif args[:2] == ['shell', 'settings']:
        action, namespace, key = args[2:5]
        assert namespace == 'system'
        if action == 'get': print(state['settings'].get(key, 'null'))
        elif action == 'put': state['settings'][key] = args[5]; save()
        elif action == 'delete': state['settings'].pop(key, None); save()
        else: raise AssertionError(args)
    elif args[:4] == ['shell', 'rm', '-f', '--']:
        for name in args[4:]: state['remote'].pop(name, None)
        save()
    elif args[0] == 'pull':
        remote, dest = args[1:]
        if remote not in state['remote']: sys.exit(1)
        data = state['remote'][remote]
        pathlib.Path(dest).write_bytes(bytes.fromhex(data))
    else: raise AssertionError(args)
elif tool == 'gradle':
    prefix = '-Pandroid.testInstrumentationRunnerArguments.'
    flags = dict(arg[len(prefix):].split('=', 1) for arg in args if arg.startswith(prefix))
    stage = flags['quickcutGeometryStage']
    width = {'narrow': '320', 'wide': '840'}[stage]
    font = {'narrow': '1.6', 'wide': '1.0'}[stage]
    assert flags['quickcutExpectedWidthDp'] == width
    assert flags['quickcutExpectedFontScale'] == font
    klass = 'com.hebi.quickcut.batch.BatchFastAdjustmentInstrumentedTest'
    methods = ['reviewKeepsBothSaveActionsInFixedFooter', 'templateManagerRemainsUsable']
    assert flags['class'] == ','.join(klass + '#' + method for method in methods)
    assert state['size'] == {'narrow': '640x1280', 'wide': '1680x1440'}[stage]
    assert state['density'] == '320' and state['settings']['font_scale'] == font
    assert state['settings']['accelerometer_rotation'] == '0'
    assert state['settings']['user_rotation'] == '0'
    results = pathlib.Path('app/build/outputs/androidTest-results/connected')
    assert not list(results.rglob('TEST-*.xml')), 'stale full/focused XML was not cleared'
    results.mkdir(parents=True, exist_ok=True)
    cases = ''.join('<testcase classname="%s" name="%s"/>' % (klass, m) for m in methods)
    if os.environ['FAKE_MODE'] == 'extra_case': cases += '<testcase classname="foreign" name="unexpected"/>'
    if os.environ['FAKE_MODE'] == 'duplicate_case': cases += '<testcase classname="%s" name="%s"/>' % (klass, methods[0])
    if os.environ['FAKE_MODE'] == 'skipped': cases = cases.replace('/>', '><skipped/></testcase>', 1)
    if os.environ['FAKE_MODE'] != 'missing_xml':
        (results / 'TEST-stage.xml').write_text('<testsuite tests="2" failures="0" errors="0" skipped="0">' + cases + '</testsuite>')
    reports = pathlib.Path('app/build/reports/androidTests/connected')
    reports.mkdir(parents=True, exist_ok=True)
    (reports / 'index.html').write_text(stage)
    for kind in ('review', 'templates'):
        stem = 'batch_%s_%s' % (kind, stage)
        assert not any('/' + stem in name for name in state['remote']), 'stale evidence was not cleared'
        for suffix in ('.png', '_device.png', '_configuration.json'):
            name = '/data/local/tmp/quickcut-design-review/' + stem + suffix
            if os.environ['FAKE_MODE'] == 'missing_screenshot' and kind == 'review' and suffix == '_device.png': continue
            if suffix.endswith('.png'):
                dimensions = (600, 1000) if suffix == '.png' else {'narrow': (640, 1280), 'wide': (1680, 1440)}[stage]
                if os.environ['FAKE_MODE'] == 'wrong_screenshot_size': dimensions = (10, 20)
                data = b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR' + struct.pack('>II', *dimensions) + b'fixture'
            else:
                data = json.dumps({'stage': stage, 'screenWidthDp': int(width), 'fontScale': float(font), 'densityDpi': 320, 'displayWidthPx': int(state['size'].split('x')[0]), 'displayHeightPx': int(state['size'].split('x')[1]), 'dialogBoundsInWindowPx': [0, 0, 600, 1000]}).encode()
            state['remote'][name] = data.hex()
    save()
    if os.environ['FAKE_MODE'] in ('gradle_failure', 'gradle_and_restore_failure'): sys.exit(17 if stage == 'narrow' else 19)
else: raise AssertionError(tool)
'''


class GeometryHelperTest(unittest.TestCase):
    def run_helper(self, mode='success', overrides=True, font=True, serial='emulator-5554', locked=False, reports=True):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            initial = {'serial': serial, 'size': '1200x2000' if overrides else None,
                       'density': '400' if overrides else None,
                       'settings': {'accelerometer_rotation': '1', 'user_rotation': '3'},
                       'remote': {'/data/local/tmp/quickcut-design-review/unrelated.png': 'ff'}}
            if font: initial['settings']['font_scale'] = '1.25'
            if locked: initial['settings'].update(accelerometer_rotation='0', user_rotation='0')
            # Stale stage captures must be removed rather than accepted on failed pull.
            for stage in ('narrow', 'wide'):
                for kind in ('review', 'templates'):
                    for suffix in ('.png', '_device.png', '_configuration.json'):
                        initial['remote'][f'/data/local/tmp/quickcut-design-review/batch_{kind}_{stage}{suffix}'] = 'ff'
            (root / 'state.json').write_text(json.dumps(initial))
            binaries = root / 'bin'
            binaries.mkdir()
            for command in ('adb', 'gradle', 'timeout'):
                path = binaries / command
                path.write_text(FAKE)
                path.chmod(0o755)
            for directory, name in ((RESULTS, 'TEST-full.xml'), (REPORTS, 'index.html')):
                if directory == REPORTS and not reports: continue
                path = root / directory / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'original full suite\x00\n')
            environment = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ['PATH'],
                               FAKE_STATE=str(root / 'state.json'), FAKE_LOG=str(root / 'commands.jsonl'), FAKE_MODE=mode,
                               CI='true', GITHUB_ACTIONS='true')
            result = subprocess.run(['bash', str(HELPER)], cwd=root, env=environment, capture_output=True, text=True)
            state = json.loads((root / 'state.json').read_text())
            for key in ('size', 'density', 'settings'):
                self.assertEqual(initial[key], state[key], result.stdout + result.stderr)
            self.assertEqual(state['remote']['/data/local/tmp/quickcut-design-review/unrelated.png'], 'ff')
            for directory, name in ((RESULTS, 'TEST-full.xml'), (REPORTS, 'index.html')):
                if directory == REPORTS and not reports:
                    self.assertFalse((root / REPORTS).exists())
                    continue
                self.assertEqual((root / directory / name).read_bytes(), b'original full suite\x00\n')
                self.assertEqual(sorted(p.name for p in (root / directory).iterdir()), [name])
            artifacts = root / 'quickcut-real-dialog-results'
            if serial.startswith('emulator-'):
                self.assertTrue((artifacts / 'restoration.json').is_file(), result.stdout + result.stderr)
                restoration = json.loads((artifacts / 'restoration.json').read_text())
                self.assertEqual(restoration['before'], restoration['after'])
                self.assertTrue(restoration['restored'])
                if not mode.startswith('signal_'):
                    for stage in ('narrow', 'wide'):
                        if mode != 'missing_xml': self.assertTrue((artifacts / stage / 'results/TEST-stage.xml').exists())
                        self.assertTrue((artifacts / stage / 'host-configuration.txt').exists())
                if mode == 'success':
                    for stage in ('narrow', 'wide'):
                        for kind in ('review', 'templates'):
                            for suffix in ('.png', '_device.png', '_configuration.json'):
                                self.assertTrue((artifacts / stage / f'batch_{kind}_{stage}{suffix}').stat().st_size)
            log_path = root / 'commands.jsonl'
            commands = [json.loads(line) for line in log_path.read_text().splitlines()] if log_path.exists() else []
            return result, commands

    def test_success_restores_original_overrides_font_rotation_and_full_results(self):
        result, commands = self.run_helper()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(sum(command[:3] == ['timeout', '--kill-after=5s', '175s'] for command in commands), 2)

    def test_absent_overrides_and_font_are_restored_as_absent(self):
        result, _ = self.run_helper(overrides=False, font=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_already_locked_rotation_is_not_changed_and_absent_reports_stay_absent(self):
        result, commands = self.run_helper(locked=True, reports=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for key in ('accelerometer_rotation', 'user_rotation'):
            self.assertFalse(any(command[:5] in (['adb', 'shell', 'settings', 'put', 'system'],
                                                ['adb', 'shell', 'settings', 'delete', 'system'])
                                 and len(command) > 5 and command[5] == key for command in commands))

    def test_first_gradle_failure_survives_evidence_collection_and_cleanup(self):
        for mode in ('gradle_failure', 'gradle_and_restore_failure'):
            with self.subTest(mode=mode):
                result, _ = self.run_helper(mode)
                self.assertEqual(result.returncode, 17, result.stdout + result.stderr)

    def test_missing_fresh_screenshot_fails_and_restores_full_results(self):
        result, _ = self.run_helper('missing_screenshot')
        self.assertNotEqual(result.returncode, 0)

    def test_extra_duplicate_or_skipped_test_cannot_satisfy_focused_gate(self):
        for mode in ('extra_case', 'duplicate_case', 'skipped'):
            with self.subTest(mode=mode):
                result, _ = self.run_helper(mode)
                self.assertNotEqual(result.returncode, 0)

    def test_wrong_device_screenshot_dimensions_fail(self):
        result, _ = self.run_helper('wrong_screenshot_size')
        self.assertNotEqual(result.returncode, 0)

    def test_missing_xml_cannot_reuse_full_or_previous_stage_success(self):
        result, _ = self.run_helper('missing_xml')
        self.assertNotEqual(result.returncode, 0)

    def test_cleanup_command_failure_fails_even_when_files_restore(self):
        result, _ = self.run_helper('restore_failure')
        self.assertEqual(result.returncode, 42, result.stdout + result.stderr)

    def test_interrupt_and_termination_restore_settings_and_full_results(self):
        for mode, expected in (('signal_int', 130), ('signal_term', 143)):
            with self.subTest(mode=mode):
                result, _ = self.run_helper(mode)
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)

    def test_physical_device_is_rejected_without_configuration_mutations(self):
        result, commands = self.run_helper(serial='physical-device')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(command[:3] == ['adb', 'shell', 'wm'] for command in commands))


if __name__ == '__main__':
    unittest.main(verbosity=2)
