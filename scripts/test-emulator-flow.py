#!/usr/bin/env python3
"""Exercise the real preflight/full-suite shell flow using bounded tool stubs."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("android-instrumentation-check.sh").resolve()

# The shell under test owns control flow, evidence preservation and real XML gates.
# Only external Android/Gradle/Main10 decoding commands are replaced.
STUB = r'''
import json, os
from pathlib import Path
import sys
from xml.etree.ElementTree import Element, SubElement, ElementTree
root = Path(os.environ["FLOW_ROOT"])
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with (root / "events.jsonl").open("a") as out:
    out.write(json.dumps([name, *args]) + "\n")
if name == "timeout":
    os.execvp(args[2], args[2:])
if name == "gradle":
    phase = "preflight" if any("NativeSmartCutInstrumentedTest" in a for a in args) else "full"
    status = int(os.environ[phase.upper() + "_STATUS"])
    count = 1 if phase == "preflight" else int(os.environ.get("FULL_COUNT", "152"))
    failed = status != 0 or (phase == "full" and os.environ.get("FULL_XML_FAILED") == "1")
    results = root / "app/build/outputs/androidTest-results/connected"
    results.mkdir(parents=True, exist_ok=True)
    # Detect stale preflight XML if shell forgot to clear it before full tests.
    if phase == "full" and (results / "TEST-preflight.xml").exists():
        (root / "stale-preflight-observed").write_text("stale")
    suite = Element("testsuite", tests=str(count), failures=str(int(failed)), errors="0", skipped="0")
    for i in range(count):
        case = SubElement(suite, "testcase", classname=phase + ".Suite", name="case" + str(i))
        if failed and i == 0: SubElement(case, "failure").text = phase + " original failure"
    ElementTree(suite).write(results / ("TEST-" + phase + ".xml"))
    reports = root / "app/build/reports/androidTests/connected"
    reports.mkdir(parents=True, exist_ok=True)
    (reports / "index.html").write_text(phase + " report")
    sys.exit(status)
if name == "adb":
    if args and args[0] == "pull":
        destination = root / args[-1]
        destination.mkdir(parents=True, exist_ok=True)
        (destination / "evidence.txt").write_text(args[1])
    sys.exit(0)
if name == "python3":
    if args and args[0].endswith("verify-android-main10.py"):
        phase = "preflight" if args[-1].endswith("preflight") else "full"
        sys.exit(int(os.environ.get(phase.upper() + "_VERIFY_STATUS", "0")))
    os.execv(os.environ["REAL_PYTHON"], [os.environ["REAL_PYTHON"], *args])
raise SystemExit("Unexpected stub invocation")
'''


class EmulatorFlowTest(unittest.TestCase):
    def run_flow(self, preflight=0, full=0, **settings):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        for name in ("gradle", "adb", "python3", "timeout"):
            executable = bin_dir / name
            executable.write_text(f"#!{sys.executable}\n" + STUB)
            executable.chmod(0o755)
        env = dict(os.environ, PATH=f"{bin_dir}:{os.environ['PATH']}", FLOW_ROOT=str(root),
                   REAL_PYTHON=sys.executable, PREFLIGHT_STATUS=str(preflight), FULL_STATUS=str(full),
                   **{key: str(value) for key, value in settings.items()})
        result = subprocess.run(["bash", str(SCRIPT)], cwd=root, env=env,
                                capture_output=True, text=True, timeout=10)
        events = [json.loads(line) for line in (root / "events.jsonl").read_text().splitlines()]
        phases = ["preflight" if any("NativeSmartCutInstrumentedTest" in a for a in event) else "full"
                  for event in events if event[0] == "gradle"]
        return result, root, events, phases

    def test_preflight_failure_still_runs_full_and_keeps_original_status_and_xml(self):
        result, root, _, phases = self.run_flow(preflight=23)
        self.assertEqual(phases, ["preflight", "full"], result.stdout + result.stderr)
        self.assertEqual(result.returncode, 23, result.stdout + result.stderr)
        saved = root / "quickcut-native-preflight-results/results/TEST-preflight.xml"
        self.assertIn("preflight original failure", saved.read_text())
        self.assertEqual((root / "quickcut-native-preflight-results/reports/index.html").read_text(), "preflight report")
        self.assertIn("full.Suite", (root / "app/build/outputs/androidTest-results/connected/TEST-full.xml").read_text())
        self.assertFalse((root / "stale-preflight-observed").exists())

    def test_preflight_timeout_still_runs_full_and_keeps_timeout_status(self):
        result, _, _, phases = self.run_flow(preflight=124)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertEqual(result.returncode, 124, result.stdout + result.stderr)

    def test_later_full_failure_does_not_replace_preflight_failure_status(self):
        result, _, _, phases = self.run_flow(preflight=23, full=47)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertEqual(result.returncode, 23, result.stdout + result.stderr)

    def test_successful_preflight_does_not_hide_full_command_failure(self):
        result, _, _, phases = self.run_flow(full=47)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertEqual(result.returncode, 47, result.stdout + result.stderr)

    def test_preflight_main10_gate_failure_still_runs_full_and_stays_failed(self):
        result, _, events, phases = self.run_flow(PREFLIGHT_VERIFY_STATUS=31)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertEqual(result.returncode, 31, result.stdout + result.stderr)
        self.assertTrue(any(e[0] == "python3" and e[-1] == "quickcut-main10-full" for e in events))

    def test_full_main10_gate_failure_is_not_hidden(self):
        result, _, _, _ = self.run_flow(FULL_VERIFY_STATUS=32)
        self.assertEqual(result.returncode, 32, result.stdout + result.stderr)

    def test_full_count_gate_stays_mandatory(self):
        result, _, _, _ = self.run_flow(FULL_COUNT=151)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Expected at least 152", result.stdout + result.stderr)

    def test_full_xml_failure_cannot_pass_with_successful_gradle(self):
        result, _, _, _ = self.run_flow(FULL_XML_FAILED=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("failed or skipped", result.stdout + result.stderr)

    def test_both_successful_phases_run_all_gates_and_return_success(self):
        result, root, events, phases = self.run_flow()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertFalse((root / "stale-preflight-observed").exists())
        self.assertTrue((root / "quickcut-native-preflight-results/results/TEST-preflight.xml").is_file())
        self.assertEqual(sum(e[0] == "python3" and any("verify-android-main10.py" in a for a in e) for e in events), 2)
        self.assertIn("152 distinct tests", result.stdout)
        timed = [e[1:3] for e in events if e[0] == "timeout" and len(e) > 3 and e[3] == "gradle"]
        self.assertEqual(timed, [["--kill-after=30s", "10m"], ["--kill-after=30s", "15m"]])
        self.assertIn("/data/local/tmp/quickcut-native-diagnostics", (root / "quickcut-native-preflight-results/diagnostics/evidence.txt").read_text())


if __name__ == "__main__":
    unittest.main()
