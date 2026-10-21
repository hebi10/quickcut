#!/usr/bin/env python3
"""Exercise the real smoke script's failure trap with an unavailable device."""

import os
from pathlib import Path
import signal
import subprocess
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
                "  echo diagnostic-output\n  exit 0\nfi\n"
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
        self.assertIn("diagnostic-output", self.run_failure("online", 3))

    def test_device_lost_after_probe_has_bounded_diagnostics(self):
        self.run_failure("disconnected", 25)


if __name__ == "__main__":
    unittest.main()
