#!/usr/bin/env python3
"""Exercise startup decisions against external adb responses, not app UI mocks."""
import importlib.util
from pathlib import Path
import subprocess
import os
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('startup', Path(__file__).with_name('android-startup-check.py'))
s = None
APP = 'com.hebi.quickcut/.MainActivity'
HOME = 'com.google.android.apps.nexuslauncher/.NexusLauncherActivity'


class Device:
    def __init__(self, components, *, text='빠른 자르기', package='com.hebi.quickcut', events='', failure=None, malformed_ui=False, launcher_anr=False, system_anr=False):
        self.components = components
        self.text = text
        self.package = package
        self.events = events
        self.failure = failure
        self.malformed_ui = malformed_ui
        self.launcher_anr = launcher_anr
        self.system_anr = system_anr
        self.acknowledged = False
        self.calls = []
        self.probes = 0

    def run(self, command, **kwargs):
        self.calls.append(command[1:])
        args = command[1:]
        output = ''
        if self.failure and self.failure in args:
            return subprocess.CompletedProcess(command, 1, '', 'device offline')
        if args[:3] == ['shell','am','start']:
            output = ('Status: ok\nActivity: ' + HOME + '\n' if '-W' in args else
                      'Starting: Intent { cmp=com.hebi.quickcut/.MainActivity }\n')
        elif args[:3] == ['shell','dumpsys','activity']:
            component = self.components[min(self.probes, len(self.components)-1)]
            self.probes += 1
            output = component if '{' in component else 'mResumedActivity: ActivityRecord{cafe u0 ' + component + ' t5}'
        elif args[0] == 'logcat':
            output = self.events.pop(0) if isinstance(self.events,list) else self.events
        elif args[:3] == ['shell','uiautomator','dump']:
            output = 'UI hierarchy dumped'
        elif args[:2] == ['shell','cat']:
            if (self.launcher_anr or self.system_anr) and not self.acknowledged:
                title = "System UI isn&#39;t responding" if self.system_anr else "Pixel Launcher isn&#39;t responding"
                output = ('<hierarchy><node package="android" resource-id="android:id/alertTitle" '
                          'text="' + title + '"/>'
                          '<node package="android" resource-id="android:id/aerr_wait" '
                          'text="Wait" enabled="true" bounds="[70,1296][1010,1422]"/></hierarchy>')
            else:
                output = '<invalid' if self.malformed_ui else f'<hierarchy><node package="{self.package}" text="{self.text}"/></hierarchy>'
        elif args[:3] == ['shell','input','tap']:
            if not (self.launcher_anr or self.system_anr) or self.acknowledged: raise AssertionError('Unexpected system tap')
            self.acknowledged = True
        else:
            raise AssertionError('Unexpected adb command: ' + repr(args))
        return subprocess.CompletedProcess(command, 0, output, '')

    def launches(self):
        return [call for call in self.calls if call[:3] == ['shell','am','start']]


class StartupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        global s
        s = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(s)

    def check(self, device):
        with tempfile.TemporaryDirectory() as temporary, patch.object(s.subprocess,'run',side_effect=device.run), patch.object(s.time,'sleep'):
            return s.check_startup(Path(temporary))

    def test_status_ok_home_focus_gets_one_bounded_relaunch_then_real_app_ui(self):
        device = Device([HOME, APP])
        self.check(device)
        self.assertEqual(len(device.launches()),2)
        self.assertFalse(any(call[:3] == ['shell','am','force-stop'] for call in device.calls))

    def test_api29_dispatch_returns_without_wait_and_still_requires_foreground_ui(self):
        device = Device([HOME, APP])
        with patch.dict(os.environ, {'QUICKCUT_EMULATOR_API': '29'}):
            self.check(device)
        self.assertEqual(len(device.launches()), 2)
        self.assertTrue(all('-W' not in call for call in device.launches()))
        self.assertGreaterEqual(device.probes, 2)

    def test_app_foreground_with_correct_owned_ui_needs_no_relaunch(self):
        device=Device([APP])
        self.check(device)
        self.assertEqual(len(device.launches()),1)

    def test_new_reference_onboarding_and_home_are_recognized(self):
        for title in ('빠르고 간편한 영상 편집', '영상 편집을 시작하세요'):
            with self.subTest(title=title):
                self.check(Device([APP], text=title))

    def test_pixel_launcher_anr_overlay_acknowledged_once_and_app_ui_reverified(self):
        events='I/am_anr(42): [0,1166,com.google.android.apps.nexuslauncher,1,Input dispatching timed out]'
        device = Device([APP], launcher_anr=True, events=events)
        self.check(device)
        self.assertTrue(device.acknowledged)
        self.assertEqual(len([c for c in device.calls if c[:3] == ['shell','input','tap']]), 1)
        self.assertEqual(len(device.launches()), 1)
        self.assertGreaterEqual(device.probes, 2)

    def test_system_ui_anr_overlay_acknowledged_once_with_app_reverification(self):
        device = Device([APP], system_anr=True,
                        events='I/am_anr(42): [0,1122,com.android.systemui,1,Input dispatching timed out]')
        self.check(device)
        self.assertTrue(device.acknowledged)
        self.assertEqual(len([c for c in device.calls if c[:3] == ['shell','input','tap']]), 1)
        self.assertEqual(len(device.launches()), 1)

    def test_launcher_anr_recovery_never_hides_quickcut_app_anr(self):
        device = Device([APP], launcher_anr=True,
                        events='I/am_anr(42): [0,123,com.hebi.quickcut,12345,reason]')
        with self.assertRaises(s.StartupError): self.check(device)
        self.assertFalse(device.acknowledged)

    def test_unknown_system_dialog_remains_a_hard_failure(self):
        device = Device([APP], text='Close app', package='android')
        with self.assertRaises(s.StartupError): self.check(device)
        self.assertFalse(device.acknowledged)

    def test_home_never_yields_foreground_fails_after_at_most_one_relaunch(self):
        device=Device([HOME])
        with self.assertRaises(s.StartupError): self.check(device)
        self.assertEqual(len(device.launches()),2)
        self.assertLessEqual(device.probes,5)

    def test_app_foreground_wrong_ui_fails_without_relaunch(self):
        device=Device([APP],text='다른 화면')
        with self.assertRaises(s.StartupError): self.check(device)
        self.assertEqual(len(device.launches()),1)

    def test_correct_label_owned_by_another_package_cannot_pass(self):
        with self.assertRaises(s.StartupError): self.check(Device([APP],package='foreign.app'))

    def test_foreign_or_missing_activity_is_not_relaunched(self):
        for component in ('foreign.app/.Activity','malformed { no component }'):
            device=Device([component])
            with self.subTest(component=component),self.assertRaises(s.StartupError): self.check(device)
            self.assertEqual(len(device.launches()),1)

    def test_app_java_native_crash_or_anr_prevents_home_relaunch(self):
        for events in ('E/AndroidRuntime(42): Process: com.hebi.quickcut, PID: 42',
                       'F/DEBUG(42): pid: 42, tid: 42, name: main >>> com.hebi.quickcut <<<',
                       'I/am_anr(42): [0,123,com.hebi.quickcut,12345,reason]',
                       'I/am_crash(42): [0,123,com.hebi.quickcut,12345,fatal]'):
            device=Device([HOME,APP],events=events)
            with self.subTest(events=events),self.assertRaises(s.StartupError): self.check(device)
            self.assertLessEqual(len(device.launches()),1)

    def test_new_failure_between_home_probe_and_relaunch_is_not_hidden(self):
        device=Device([HOME,APP],events=['','','I/am_anr(42): [0,123,com.hebi.quickcut,12345,reason]'])
        with self.assertRaises(s.StartupError): self.check(device)
        self.assertEqual(len(device.launches()),1)

    def test_expired_startup_budget_stops_before_additional_commands(self):
        device=Device([HOME,APP])
        with patch.object(s.time,'monotonic',side_effect=[0,200]),self.assertRaises(s.StartupError): self.check(device)
        self.assertEqual(device.calls,[])

    def test_other_app_crash_noise_does_not_block_valid_start(self):
        self.check(Device([APP],events='E/AndroidRuntime(42): Process: com.hebi.quickcut.test, PID: 42\nI/am_anr(42): [0,1,foreign.app,2,waiting for com.hebi.quickcut]'))

    def test_adb_failures_fail_closed_without_relaunch(self):
        for failure in ('start','activity','logcat','uiautomator','cat'):
            device=Device([HOME,APP],failure=failure)
            # UI commands need a foreground app to be reached.
            if failure in ('uiautomator','cat'): device.components=[APP]
            with self.subTest(failure=failure),self.assertRaises(s.StartupError): self.check(device)
            self.assertLessEqual(len(device.launches()),1)

    def test_malformed_ui_is_not_success_or_home_relaunch(self):
        device=Device([APP],malformed_ui=True)
        with self.assertRaises(s.StartupError): self.check(device)
        self.assertEqual(len(device.launches()),1)

    def test_api35_top_resumed_activity_format_is_supported(self):
        self.check(Device(['topResumedActivity=ActivityRecord{cafe u0 com.hebi.quickcut/com.hebi.quickcut.MainActivity t5}']))

    def test_timed_out_adb_command_is_not_retried(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(s.subprocess,'run',side_effect=subprocess.TimeoutExpired('adb',10)), self.assertRaises(s.StartupError):
            s.check_startup(Path(temporary))



class StartupScriptTest(unittest.TestCase):
    def test_real_smoke_script_recovers_observed_status_ok_but_home_foreground(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            (root/'scripts').mkdir()
            (root/'app').mkdir()
            for name in ('android-emulator-check.sh','verify-release-gate.py','android-startup-check.py'):
                source=Path(__file__).with_name(name)
                if source.exists(): shutil.copy2(source,root/'scripts'/name)
            (root/'app/build.gradle.kts').write_text('versionName = "0.4.17"\nversionCode = 23\n')
            stub = r'''import json,sys
from pathlib import Path
args=sys.argv[1:]
name=Path(sys.argv[0]).name
p=Path('device.json')
state=json.loads(p.read_text()) if p.exists() else {'launches':0,'version':0,'sentinel':False}
if name=='gradle':
    code=23
    for arg in args:
        if arg.startswith('-PquickcutTestVersionCode='): code=int(arg.split('=')[1])
    apk=Path('app/build/outputs/apk/debug/app-debug.apk');apk.parent.mkdir(parents=True,exist_ok=True);apk.write_text(str(code))
elif name=='sleep': pass
elif name=='ffmpeg': sys.exit(37)
elif args==['get-state']: sys.exit(1)
elif args[:1]==['install']: state['version']=int(Path(args[-1]).read_text())
elif args[:3]==['shell','dumpsys','package']: print('versionCode='+str(state['version'])+' minSdk=29')
elif args[:3]==['shell','run-as','com.hebi.quickcut']:
    if args[3:5]==['sh','-c']: sys.stdin.read();state['sentinel']=True
    elif args[3:4]==['cat']: print('quickcut-update-data' if state['sentinel'] else '')
    elif args[3:4]==['rm']: state['sentinel']=False
elif args[:3]==['shell','am','force-stop']: state['stopped_home']=True
elif args[:4]==['shell','am','start','-W']:
    state['launches']+=1
    print('Status: ok\nActivity: com.google.android.apps.nexuslauncher/.NexusLauncherActivity')
elif args[:3]==['shell','dumpsys','activity']:
    component='com.hebi.quickcut/.MainActivity' if state['launches']>=2 else 'com.google.android.apps.nexuslauncher/.NexusLauncherActivity'
    print('mResumedActivity: ActivityRecord{cafe u0 '+component+' t1}')
elif args[:1]==['logcat']: pass
elif args[:3]==['shell','uiautomator','dump']: pass
elif args[:2]==['shell','cat']:
    print('<hierarchy><node package="com.hebi.quickcut" text="빠른 자르기"/></hierarchy>' if state['launches']>=2 else '<hierarchy><node package="com.google.android.apps.nexuslauncher" text="Home"/></hierarchy>')
elif args[:2]==['exec-out','screencap']: sys.stdout.buffer.write(b'png')
elif args[:3]==['shell','wm','size'] or args[:3]==['shell','wm','density']: pass
else: raise SystemExit('Unexpected call: '+repr(args))
p.write_text(json.dumps(state))
'''
            for name in ('adb','gradle','sleep','ffmpeg'):
                tool=root/name;tool.write_text('#!'+sys.executable+'\n'+stub);tool.chmod(0o755)
            result=subprocess.run(['bash','scripts/android-emulator-check.sh'],cwd=root,
                env=dict(os.environ,PATH=str(root)+':'+os.environ['PATH']),capture_output=True,text=True,timeout=20)
            self.assertEqual(result.returncode,37,result.stdout+result.stderr)
            import json
            state=json.loads((root/'device.json').read_text())
            self.assertEqual(state['launches'],2)
            self.assertNotIn('stopped_home',state)
            self.assertIn('QuickCut start screen verified',result.stdout)

if __name__=='__main__': unittest.main()
