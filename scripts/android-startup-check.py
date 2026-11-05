#!/usr/bin/env python3
"""Acquire QuickCut foreground without mistaking an acknowledged launch for UI."""
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APP = 'com.hebi.quickcut/.MainActivity'
APP_COMPONENTS = {APP, 'com.hebi.quickcut/com.hebi.quickcut.MainActivity'}
HOME_COMPONENTS = {'com.google.android.apps.nexuslauncher/.NexusLauncherActivity',
                   'com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity'}


class StartupError(RuntimeError):
    pass


def resumed_component(report):
    components = set(re.findall(
        r'(?:mResumedActivity\s*:|topResumedActivity\s*=)\s*ActivityRecord\{[^\n}]*?\bu[0-9]+\s+([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)', report))
    if len(components) != 1:
        raise StartupError('Missing or ambiguous actual resumed activity; refusing startup recovery')
    return components.pop()


def app_failed(report):
    package = r'com\.hebi\.quickcut(?=$|[^A-Za-z0-9_.])'
    return any(
        re.search(r'Process:\s*' + package, line)
        or re.search(r'>>>\s*' + package, line)
        or re.search(r'\bam_(?:crash|anr)\b[^\n]*?\[\s*[0-9]+\s*,\s*[0-9]+\s*,\s*' + package, line)
        for line in report.splitlines())


def check_startup(directory):
    directory.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + 25

    def adb(args, filename=None):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise StartupError('Startup foreground acquisition exceeded its 25-second bound')
        try:
            result = subprocess.run(['adb', *args], capture_output=True, text=True,
                                    timeout=min(10, remaining))
        except (subprocess.TimeoutExpired, OSError):
            raise StartupError('Bounded adb startup command failed or timed out') from None
        if filename:
            (directory / filename).write_text(result.stdout + result.stderr, encoding='utf-8')
        if result.returncode:
            raise StartupError('adb startup command failed; no relaunch will hide the failure')
        return result.stdout

    def guard():
        evidence = adb(['logcat', '-d', '-b', 'crash', '-b', 'events', '-v', 'brief',
                        'AndroidRuntime:E', 'DEBUG:F', 'libc:F', 'am_crash:I', 'am_anr:I', '*:S'],
                       'crash-anr.txt')
        if app_failed(evidence):
            raise StartupError('QuickCut crash/native-crash/ANR evidence prevents startup recovery')

    def launch(number):
        report = adb(['shell', 'am', 'start', '-W', '-n', APP], f'launch-{number}.txt')
        if not re.search(r'^Status:\s*ok\s*$', report, re.M):
            raise StartupError('Activity start was not acknowledged successfully')

    guard()
    launch(1)
    relaunched = False
    for attempt in range(5):
        guard()
        component = resumed_component(adb(['shell', 'dumpsys', 'activity', 'activities'], 'activity.txt'))
        print('QuickCut startup actual resumed activity: ' + component)
        if component in APP_COMPONENTS:
            adb(['shell', 'uiautomator', 'dump', '/sdcard/quickcut-ui.xml'])
            xml = adb(['shell', 'cat', '/sdcard/quickcut-ui.xml'], 'ui.xml')
            try:
                tree = ET.fromstring(xml)
            except ET.ParseError:
                raise StartupError('Malformed startup UI hierarchy') from None
            valid_start_labels = ('빠른 자르기', '영상 편집을 시작하세요',
                                  '빠르고 간편한', '필요한 도구만', '안전하게 저장')
            if any(node.get('package') == 'com.hebi.quickcut' and
                   any(label in node.get('text', '') for label in valid_start_labels)
                   for node in tree.iter('node')):
                guard()
                final = resumed_component(adb(['shell', 'dumpsys', 'activity', 'activities'], 'activity.txt'))
                if final not in APP_COMPONENTS:
                    raise StartupError('QuickCut lost foreground during UI verification')
                print('QuickCut start screen verified')
                return
        elif component in HOME_COMPONENTS:
            if not relaunched:
                guard()
                launch(2)
                relaunched = True
        else:
            raise StartupError('Unexpected resumed activity; refusing relaunch: ' + component)
        if attempt < 4:
            time.sleep(min(2, max(0, deadline - time.monotonic())))
    raise StartupError('QuickCut foreground and recognized startup UI were not verified within five probes')


def main():
    failed = False
    try:
        check_startup(Path('quickcut-startup-diagnostics'))
    except StartupError as error:
        print('::error::' + str(error), file=sys.stderr)
        failed = True
    finally:
        try:
            result = subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True, timeout=3)
            if result.returncode == 0:
                Path('quickcut-start.png').write_bytes(result.stdout)
        except (subprocess.TimeoutExpired, OSError):
            pass
    return int(failed)


if __name__ == '__main__':
    sys.exit(main())
