#!/usr/bin/env python3
"""PR-independent observations around the unchanged, pinned Android checks."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = 'com.hebi.quickcut'
CLASS = 'com.hebi.quickcut.export.BackgroundMedia3ExportInstrumentedTest'
METHODS = {'media3ProcessingPublishesAValidOutputAfterAppSwitchAndScreenOff',
           'cancellingOffscreenMedia3ProcessingLeavesNoGalleryOutputOrStagedWriter'}
MARKER = 'bash "$(dirname -- "${BASH_SOURCE[0]}")/android-instrumentation-check.sh"'


def clock():
    return {'host_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'host_monotonic_ns': time.monotonic_ns()}


def write_json(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, indent=2) + '\n')


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def command(args, seconds=3):
    start = clock()
    try:
        result = subprocess.run(args, capture_output=True, text=True, errors='replace', timeout=seconds)
        return {'argv': args, 'start': start, 'end': clock(), 'exit': result.returncode,
                'stdout': result.stdout[:1024 * 1024], 'stderr': result.stderr[:16384],
                'stdout_truncated': len(result.stdout) > 1024 * 1024, 'stderr_truncated': len(result.stderr) > 16384}
    except subprocess.TimeoutExpired as error:
        def partial(value, limit):
            if isinstance(value, bytes):
                value = value.decode(errors='replace')
            return (value or '')[:limit]
        return {'argv': args, 'start': start, 'end': clock(), 'exit': 124, 'timeout_seconds': seconds,
                'partial_stdout': partial(error.stdout, 1024 * 1024), 'partial_stderr': partial(error.stderr, 16384)}
    except OSError as error:
        return {'argv': args, 'start': start, 'end': clock(), 'exit': 127, 'error': str(error)}


def snapshot(root, output):
    output = Path(output)
    values = {'capture': clock(), 'asset_sha256': {}, 'build_config': []}
    assets = Path(root) / 'app/src/androidTest/assets'
    for path in sorted(assets.rglob('*')):
        if path.is_file() and not any(p.startswith('.') for p in path.relative_to(assets).parts):
            values['asset_sha256'][path.relative_to(assets).as_posix()] = digest(path)
    for path in Path(root).glob('app/build/generated/**/BuildConfig.*'):
        text = path.read_text(errors='replace')
        values['build_config'].append({'path': str(path.relative_to(root)), 'sha256': digest(path),
            'standard_fields': [line.strip() for line in text.splitlines() if re.search(
                r'\b(?:DEBUG|APPLICATION_ID|BUILD_TYPE|VERSION_CODE|VERSION_NAME)\s*=', line)]})
    apk = Path(root) / 'app/build/outputs/apk/debug/app-debug.apk'
    if apk.is_file():
        values['debug_apk_sha256'] = digest(apk)
        sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '/nonexistent')))
        aapt = sdk / 'build-tools/36.0.0/aapt'
        if aapt.is_file():
            badging = command([str(aapt), 'dump', 'badging', str(apk)])
            values['debug_apk_badging'] = {k: v for k, v in badging.items() if k != 'stdout'}
            values['debug_apk_badging']['standard_fields'] = [line for line in badging.get('stdout', '').splitlines()
                if line.startswith(('package:', 'sdkVersion:', 'targetSdkVersion:', 'native-code:'))]
    values['device'] = {}
    for label, args in {
        'clock': ['date', '-u', '+%Y-%m-%dT%H:%M:%S.%N%z'],
        'local_clock': ['date', '+%Y-%m-%dT%H:%M:%S.%N%z'],
        'timezone': ['getprop', 'persist.sys.timezone'],
        'uptime': ['cat', '/proc/uptime'],
        'fingerprint': ['getprop', 'ro.build.fingerprint'],
        'sdk': ['getprop', 'ro.build.version.sdk'],
        'abis': ['getprop', 'ro.product.cpu.abilist'],
        'hardware_decoder': ['getprop', 'ro.boot.qemu.hwcodec.avcdec'],
        'display_size': ['wm', 'size'], 'display_density': ['wm', 'density'],
    }.items():
        values['device'][label] = command(['adb', 'shell', *args], 2)
    cpu = command(['adb', 'shell', 'cat', '/proc/cpuinfo'], 2)
    cpu['stdout'] = '\n'.join(line for line in cpu.get('stdout', '').splitlines()
        if re.search(r'vendor_id|model name|Hardware|Processor', line))[:4096]
    values['device']['cpu'] = cpu
    write_json(output / 'before-instrumentation.json', values)
    return 0



def host_snapshot(deadline):
    values = {'capture': clock(), 'load_average': os.getloadavg()}
    memory = Path('/proc/meminfo').read_text()
    values['memory'] = [line for line in memory.splitlines() if line.startswith(
        ('MemTotal:', 'MemAvailable:', 'SwapTotal:', 'SwapFree:'))]
    filesystem = os.statvfs('.')
    values['disk_available_bytes'] = filesystem.f_bavail * filesystem.f_frsize
    listed = command(['ps', '-eo', 'pid,comm'], min(2, max(.1, deadline - time.monotonic())))
    values['qemu'] = []
    for line in listed.get('stdout', '').splitlines():
        parts = line.split()
        if len(parts) != 2 or not parts[0].isdigit() or not parts[1].startswith(('qemu', 'emulator')):
            continue
        pid = parts[0]
        process = {'pid': pid, 'comm': parts[1]}
        for field in ('stat', 'status', 'wchan'):
            try:
                process[field] = Path(f'/proc/{pid}/{field}').read_text()[:16384]
            except OSError as error:
                process[field] = str(error)
        if time.monotonic() < deadline:
            process['threads'] = command(['ps', '-L', '-p', pid, '-o', 'pid,tid,comm,stat,wchan:32'],
                                         min(2, max(.1, deadline - time.monotonic())))
        values['qemu'].append(process)
        if len(values['qemu']) >= 2:
            break
    return values


def validate_results(root, profile):
    cases = []
    for path in (Path(root) / 'app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'):
        for case in ET.parse(path).iter('testcase'):
            assert not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')), 'Failed/incomplete test result'
            assert float(case.get('time', '0')) >= 0, 'Unfinished negative-duration testcase'
            cases.append((case.get('classname'), case.get('name')))
    assert len(cases) == len(set(cases)), 'Duplicate testcase identity'
    if profile == 'full':
        assert len(cases) == 235, f'Expected original 235 distinct tests, found {len(cases)}'
    else:
        assert set(cases) == {(CLASS, method) for method in METHODS}, 'The exact two original methods must complete'
    return {'profile': profile, 'completed_distinct_cases': len(cases), 'cases': cases}


def prepared_shell(root, output, profile):
    original = Path(root) / 'scripts/android-emulator-check.sh'
    text = original.read_text()
    assert text.count(MARKER) == 1 and text.rstrip().endswith(MARKER), 'Pinned preparation boundary changed'
    capture = shlex.join([sys.executable, str(Path(__file__).resolve()), 'snapshot', '--root', str(root), '--output', str(output)])
    if profile == 'full':
        tail = capture + '\n' + shlex.join(['bash', str(Path(root) / 'scripts/android-instrumentation-check.sh')])
    else:
        validate = shlex.join([sys.executable, str(Path(__file__).resolve()), 'validate', '--root', str(root), '--profile', profile,
                             '--output', str(Path(output) / 'target-results.json')])
        tail = capture + '\n' + '\n'.join([
            'rm -rf app/build/outputs/androidTest-results/connected app/build/reports/androidTests/connected',
            'original_status=0',
            shlex.join(['timeout', '--kill-after=30s', '20m', 'gradle', '--no-daemon',
                '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true',
                '-Pandroid.testInstrumentationRunnerArguments.class=' + CLASS, ':app:connectedDebugAndroidTest']) + ' || original_status=$?',
            'validation_status=0', validate + ' || validation_status=$?',
            'if [[ "$original_status" -ne 0 ]]; then exit "$original_status"; fi', 'exit "$validation_status"'])
    return ['bash', '-c', text.replace(MARKER, tail), str(original)], digest(original)



def own_java_trace(text, pid):
    for match in re.finditer(r'^----- pid (\d+) at [^\n]*\n.*?(?=^----- pid \d+ at |\Z)', text, re.M | re.S):
        section = match.group(0)
        if match.group(1) != pid or not re.search(r'^Cmd line: ' + re.escape(PACKAGE) + r'\s*$', section, re.M):
            continue
        end = re.search(r'^----- end ' + re.escape(pid) + r' -----$', section, re.M)
        return section[:end.end()] if end else section
    return None


class Observer:
    def __init__(self, output, stall_seconds=120, profile="full"):
        self.output = Path(output)
        self.stall_seconds = stall_seconds
        self.profile = profile
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.armed = False
        self.last_progress = time.monotonic()
        self.generation = 0
        self.current_case = None
        self.current_device_wall = None
        self.snapshots = 0
        self.signal_sent = set()
        self.last_snapshot = 0.0
        self.collector = None
        self.workers = []

    def event(self, kind, **details):
        with self.lock:
            with (self.output / 'observer-events.jsonl').open('a') as stream:
                stream.write(json.dumps({'kind': kind, **clock(), **details}) + '\n')

    def progress(self, kind, line):
        self.armed = True
        self.last_progress = time.monotonic()
        self.generation += 1
        self.event(kind, line=line[:2048], current_case=self.current_case)

    def host_line(self, line, profile):
        if re.search(r'\bStarting (235|2) tests\b', line) or re.search(r'Tests \d+/(235|2) completed', line):
            self.progress('host_progress', line)

    def device_line(self, line):
        match = re.search(r'TestRunner:\s+(started|finished):\s+(.+)', line)
        if match:
            self.current_case = match.group(2).strip()
            self.current_device_wall = line[:18]
            if self.armed or (self.profile == 'background_class' and CLASS in self.current_case):
                self.progress('device_case_' + match.group(1), line)
            else:
                self.event('unarmed_device_case_' + match.group(1), line=line[:2048])

    def collect_logcat(self):
        for attempt in range(4):
            if self.stop.is_set():
                break
            self.event('logcat_start', collector_attempt=attempt)
            with (self.output / f'logcat-{attempt}.txt').open('w') as stream:
                self.collector = subprocess.Popen(['adb', 'logcat', '-b', 'all', '-v', 'threadtime', '-T', '1'],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace', bufsize=1)
                if self.stop.is_set():
                    self.collector.terminate()
                for line in self.collector.stdout:
                    stream.write(line)
                    stream.flush()
                    self.device_line(line)
                    if self.stop.is_set():
                        break
                if self.stop.is_set() and self.collector.poll() is None:
                    self.collector.terminate()
                try:
                    code = self.collector.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    self.collector.kill()
                    code = self.collector.wait(timeout=2)
            self.event('logcat_eof', collector_attempt=attempt, exit=code)
            if self.stop.wait(2):
                break
            self.event('logcat_restart_state', result=command(['adb', 'get-state'], 2))
            self.event('logcat_restart_clock', result=command(['adb', 'shell', 'date', '-u', '+%Y-%m-%dT%H:%M:%S.%N%z'], 2))

    def still_stalled(self, generation):
        return not self.stop.is_set() and self.generation == generation and time.monotonic() - self.last_progress >= self.stall_seconds

    def observe_stall(self):
        while not self.stop.wait(1):
            if (not self.armed or time.monotonic() - self.last_progress < self.stall_seconds or
                    time.monotonic() - self.last_snapshot < self.stall_seconds or self.snapshots >= 2):
                continue
            self.snapshots += 1
            self.last_snapshot = time.monotonic()
            generation = self.generation
            destination = self.output / f'stall-{self.snapshots}'
            destination.mkdir()
            identity = {'capture': clock(), 'progress_generation': generation, 'case': self.current_case,
                        'last_device_wall': self.current_device_wall,
                        'host_observation_gap_seconds': time.monotonic() - self.last_progress}
            write_json(destination / 'identity.json', identity)
            deadline = time.monotonic() + 45
            write_json(destination / 'host.json', host_snapshot(deadline))

            def capture(label, args, seconds=3):
                if time.monotonic() >= deadline or not self.still_stalled(generation):
                    return {'exit': None, 'not_run': 'progress resumed, observer stopped, or snapshot budget exhausted'}
                result = command(args, min(seconds, max(.1, deadline - time.monotonic())))
                write_json(destination / (label + '.json'), result)
                return result

            capture('device-clock', ['adb', 'shell', 'date', '-u', '+%Y-%m-%dT%H:%M:%S.%N%z'], 2)
            capture('device-uptime', ['adb', 'shell', 'cat', '/proc/uptime'], 2)
            found = capture('app-pid', ['adb', 'shell', 'pidof', PACKAGE], 2)
            pids = found.get('stdout', '').split()
            pid = pids[0] if found.get('exit') == 0 and len(pids) == 1 and pids[0].isdigit() else None
            uid = capture('app-uid', ['adb', 'shell', 'run-as', PACKAGE, 'id', '-u'], 2)
            capture('power', ['adb', 'shell', 'dumpsys', 'power'])
            for label, args in [('activities', ['dumpsys', 'activity', 'activities']),
                                ('processes', ['dumpsys', 'activity', 'processes'])]:
                result = capture(label, ['adb', 'shell', *args])
                if 'stdout' in result:
                    lines = result['stdout'].splitlines()
                    keep = set()
                    for i, line in enumerate(lines):
                        if re.search(re.escape(PACKAGE) + r'|mAwake|mScreenOn|mFocused|topResumed|ResumedActivity|freez', line, re.I):
                            keep.update(range(max(0, i - 3), min(len(lines), i + 5)))
                    result['stdout'] = '\n'.join(lines[i] for i in sorted(keep))
                    write_json(destination / (label + '.json'), result)
            capture('codec', ['adb', 'shell', 'dumpsys', 'media.codec'])
            if pid is None:
                continue
            proc = capture('app-cmdline', ['adb', 'shell', 'run-as', PACKAGE, 'cat', f'/proc/{pid}/cmdline'], 2)
            status = capture('app-status', ['adb', 'shell', 'run-as', PACKAGE, 'cat', f'/proc/{pid}/status'], 2)
            status_uid = re.search(r'^Uid:\s+(\d+)', status.get('stdout', ''), re.M)
            own_uid = uid.get('stdout', '').strip()
            verified = (proc.get('exit') == 0 and uid.get('exit') == 0 and status.get('exit') == 0 and
                        proc.get('stdout', '').strip('\x00\r\n ') == PACKAGE and
                        own_uid.isdigit() and status_uid is not None and status_uid.group(1) == own_uid)
            if not verified:
                self.event('signal_skipped_unverified_pid', pid=pid, snapshot=self.snapshots)
                continue
            capture('threads', ['adb', 'shell', 'run-as', PACKAGE, 'sh', '-c',
                shlex.quote(f'cat /proc/{pid}/status; for d in /proc/{pid}/task/*; do echo THREAD=$d; cat "$d/comm" "$d/wchan" "$d/stack" 2>&1; done')], 5)
            if generation not in self.signal_sent and self.still_stalled(generation):
                capture('sigquit', ['adb', 'shell', 'run-as', PACKAGE, 'kill', '-3', pid], 2)
                self.signal_sent.add(generation)
            capture('native-backtrace', ['adb', 'shell', 'debuggerd', '-b', pid], 5)
            traces = capture('java-trace-directory', ['adb', 'shell', 'ls', '-t', '/data/anr'], 2)
            for name in traces.get('stdout', '').splitlines()[:3]:
                if not re.fullmatch(r'[A-Za-z0-9_.-]+', name) or not self.still_stalled(generation) or time.monotonic() >= deadline:
                    continue
                trace = command(['adb', 'shell', 'cat', '/data/anr/' + name], min(2, max(.1, deadline - time.monotonic())))
                body = trace.get('stdout', '')
                matching = own_java_trace(body, pid)
                if matching:
                    trace['stdout'] = matching
                    trace['only_matching_pid_section_retained'] = True
                    trace['matching_pid_section_complete'] = re.search(
                        r'^----- end ' + re.escape(pid) + r' -----$', matching, re.M) is not None
                    write_json(destination / ('java-trace-' + name + '.json'), trace)
                else:
                    write_json(destination / ('java-trace-' + name + '-status.json'), {
                        'exit': trace.get('exit'), 'matching_app_pid': False, 'stderr': trace.get('stderr', '')[:1024]})
            self.event('snapshot_finished', snapshot=self.snapshots, progress_generation=generation)

    def start(self):
        for target in (self.collect_logcat, self.observe_stall):
            worker = threading.Thread(target=target, daemon=True)
            worker.start()
            self.workers.append(worker)

    def finish(self):
        self.stop.set()
        if self.collector is not None and self.collector.poll() is None:
            self.collector.terminate()
            try:
                self.collector.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.collector.kill()
        for worker in self.workers:
            worker.join(timeout=6)


def run(args):
    root = Path.cwd().resolve()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    actual_sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    actual_tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], text=True).strip()
    assert actual_sha == args.source_sha and actual_tree == args.source_tree, 'App checkout does not match the approved source'
    subprocess.run(['git', 'diff', '--exit-code', 'HEAD', '--', 'app', 'scripts', '.github/workflows'], check=True, capture_output=True)
    prepared, original_hash = prepared_shell(root, output, args.profile)
    write_json(output / 'source-provenance.json', {'source_sha': actual_sha, 'source_tree': actual_tree,
        'diagnostic_workflow_sha': os.environ.get('GITHUB_SHA'),
        'observer_sha256': digest(__file__), 'original_driver_sha256': original_hash,
        'executed_shell_sha256': hashlib.sha256(prepared[2].encode()).hexdigest(),
        'profile': args.profile, 'original_full_suite_timeout_seconds': 1200,
        'observer_stall_seconds': args.stall_seconds, 'observer_max_snapshots': 2,
        'workflow_run': os.environ.get('GITHUB_RUN_ID'), 'run_attempt': os.environ.get('GITHUB_RUN_ATTEMPT'), 'start': clock()})
    observer = Observer(output, args.stall_seconds, args.profile)
    observer.start()
    original_status = 1
    try:
        child = subprocess.Popen(prepared, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace', bufsize=1)
        with (output / 'original-check.log').open('w') as log:
            for line in child.stdout:
                log.write(line)
                log.flush()
                sys.stdout.write(line)
                sys.stdout.flush()
                observer.host_line(line, args.profile)
        original_status = child.wait()
    finally:
        try:
            observer.finish()
        except Exception as error:
            write_json(output / 'observer-stop-error.json', {'error': repr(error)})
    validation_status = 0
    try:
        results = validate_results(root, args.profile)
        write_json(output / 'completed-results.json', results)
    except Exception as error:
        validation_status = 1
        write_json(output / 'completed-results.json', {'validation_error': str(error)})
    packaged = {}
    try:
        for apk in root.glob('app/build/outputs/apk/androidTest/debug/*.apk'):
            with zipfile.ZipFile(apk) as archive:
                packaged[str(apk.relative_to(root))] = {name[7:]: hashlib.sha256(archive.read(name)).hexdigest()
                    for name in archive.namelist() if name.startswith('assets/') and not name.endswith('/')}
        write_json(output / 'actual-test-apk-assets.json', packaged)
    except Exception as error:
        write_json(output / 'actual-test-apk-assets-error.json', {'error': repr(error)})
    write_json(output / 'status.json', {'original_exit': original_status, 'observer_validation_exit': validation_status,
                                       'end': clock()})
    return original_status if original_status else validation_status


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='action', required=True)
    runner = commands.add_parser('run')
    runner.add_argument('--profile', choices=['full', 'background_class'], required=True)
    runner.add_argument('--source-sha', required=True)
    runner.add_argument('--source-tree', required=True)
    runner.add_argument('--output', required=True)
    runner.add_argument('--stall-seconds', type=float, default=120)
    snap = commands.add_parser('snapshot')
    snap.add_argument('--root', required=True)
    snap.add_argument('--output', required=True)
    validate = commands.add_parser('validate')
    validate.add_argument('--root', required=True)
    validate.add_argument('--profile', required=True)
    validate.add_argument('--output', required=True)
    args = parser.parse_args()
    if args.action == 'run':
        return run(args)
    if args.action == 'snapshot':
        return snapshot(Path(args.root), args.output)
    write_json(args.output, validate_results(args.root, args.profile))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
