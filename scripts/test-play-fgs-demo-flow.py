#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('record-play-fgs-demo.sh').resolve()

ADB = r'''#!/usr/bin/env python3
import os, signal, sys, time
from pathlib import Path
a = sys.argv[1:]
if len(a) > 1 and a[0] == 'shell' and a[1] == 'screenrecord':
    Path('recorder.pid').write_text(str(os.getpid()))
    if os.environ['DEMO_CASE'] == 'early_recording_exit':
        time.sleep(0.1)
        sys.exit(17)
    signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
    while True: signal.pause()
elif len(a) > 1 and a[0] == 'shell' and a[1] == 'pkill':
    try: os.kill(int(Path('recorder.pid').read_text()), signal.SIGINT)
    except ProcessLookupError: pass
elif a[0] == 'pull':
    p = Path(a[-1])
    if p.suffix == '.mp4': p.write_bytes(b'disposable-video-placeholder')
    else:
        p.mkdir(parents=True, exist_ok=True)
        (p / 'passed.txt').write_text('disposable scenario passed')
'''

GRADLE = r'''#!/usr/bin/env python3
import os, sys
from pathlib import Path
p = Path('app/build/outputs/androidTest-results/connected')
p.mkdir(parents=True)
(p / 'TEST-demo.xml').write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="demo" name="record"/></testsuite>')
sys.exit(7 if os.environ['DEMO_CASE'] == 'scenario_failure' else 0)
'''


class RecordingFlowTests(unittest.TestCase):
    def run_case(self, case):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            for name, source in [('adb', ADB), ('gradle', GRADLE),
                                 ('ffprobe', '#!/bin/sh\necho 35\n')]:
                p = bin_dir / name
                p.write_text(source)
                p.chmod(0o700)
            env = os.environ.copy()
            env.update(PATH=str(bin_dir) + os.pathsep + env['PATH'], DEMO_CASE=case)
            return subprocess.run(['bash', str(SCRIPT)], cwd=root, env=env,
                                  capture_output=True, timeout=20).returncode

    def test_live_recorder_and_successful_scenario_are_accepted(self):
        self.assertEqual(self.run_case('normal'), 0)

    def test_early_recorder_exit_cannot_be_accepted_as_complete_video(self):
        self.assertNotEqual(self.run_case('early_recording_exit'), 0)

    def test_failed_scenario_is_preserved(self):
        self.assertNotEqual(self.run_case('scenario_failure'), 0)


if __name__ == '__main__':
    unittest.main()
