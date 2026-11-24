#!/usr/bin/env python3
"""Offline ADB health probe tests, including a stalled device."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("ci-android-health.py")


class DeviceHealthTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.adb = self.root / "adb"
        self.adb.write_text("#!" + sys.executable + "\n" +
            "import os,sys,time\n"
            "if os.getenv('ADB_FIXTURE')=='stall': time.sleep(1)\n"
            "args=sys.argv[1:]\n"
            "if os.getenv('ADB_FIXTURE')=='offline': sys.exit(1)\n"
            "print('device' if args==['get-state'] else os.getenv('ADB_FAKE_API','29'))\n")
        self.adb.chmod(0o755)

    def check(self, *, mode="online", expected=29, api="29"):
        env = dict(os.environ, ADB_FIXTURE=mode, ADB_FAKE_API=api)
        return subprocess.run([sys.executable, str(SCRIPT), "--expected-api", str(expected),
                               "--adb", str(self.adb), "--attempts", "2",
                               "--timeout", "0.05", "--delay", "0"],
                              capture_output=True, text=True, env=env, timeout=3)

    def test_online_and_matching_api(self):
        self.assertEqual(self.check().returncode, 0)

    def test_offline_or_wrong_android_api_fails_closed(self):
        self.assertEqual(self.check(mode="offline").returncode, 86)
        self.assertEqual(self.check(expected=29, api="35").returncode, 86)

    def test_stalled_adb_has_bounded_failure(self):
        result = self.check(mode="stall")
        self.assertEqual(result.returncode, 86)
        self.assertIn("unavailable", result.stdout)


if __name__ == "__main__":
    unittest.main()
