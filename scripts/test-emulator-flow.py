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
    if any("NativeSmartCutInstrumentedTest" in a for a in args):
        phase = "preflight"
    elif any("android.testInstrumentationRunnerArguments.notClass=" in a for a in args):
        phase = "remaining"
    elif any("android.testInstrumentationRunnerArguments.class=com.hebi.quickcut.media.BatchDateExportInstrumentedTest" in a for a in args):
        phase = "date"
    elif any("android.testInstrumentationRunnerArguments.class=com.hebi.quickcut.export.BackgroundExportInstrumentedTest#actualExportFinishesOffscreenAndItsResultSurvivesActivityRecreation" in a for a in args):
        phase = "offscreen_single"
    elif any("android.testInstrumentationRunnerArguments.class=com.hebi.quickcut.export.BackgroundExportInstrumentedTest#batchContinuesToTheSecondFileAndPublishesBothWhileScreenIsOff" in a for a in args):
        phase = "offscreen_batch"
    elif any("android.testInstrumentationRunnerArguments.class=com.hebi.quickcut.export.BackgroundMedia3ExportInstrumentedTest" in a for a in args):
        phase = "media3"
    else:
        phase = "full"
    status = int(os.environ.get(phase.upper() + "_STATUS", os.environ.get("FULL_STATUS", "0")))
    count = {"preflight": 1, "date": 2, "remaining": 286, "offscreen_single": 1,
             "offscreen_batch": 1, "media3": 2}.get(
        phase, int(os.environ.get("FULL_COUNT", "152")))
    failed = status != 0 or (phase in ("full", "remaining") and os.environ.get("FULL_XML_FAILED") == "1")
    results = root / "app/build/outputs/androidTest-results/connected"
    results.mkdir(parents=True, exist_ok=True)
    # Detect stale preflight XML if shell forgot to clear it before full tests.
    if phase == "full" and (results / "TEST-preflight.xml").exists():
        (root / "stale-preflight-observed").write_text("stale")
    document = Element("testsuites", tests=str(count),
                       failures=str(int(failed)), errors="0", skipped="0")
    half = count // 2 if phase == "remaining" else count
    suite = SubElement(document, "testsuite", tests=str(half),
                       failures=str(int(failed)), errors="0", skipped="0")
    other_suite = (SubElement(document, "testsuite", tests=str(count - half),
                              failures="0", errors="0", skipped="0")
                   if phase == "remaining" else None)
    required = (
        "com.hebi.quickcut.segments.Stage14MultiSegmentExportInstrumentedTest",
        "com.hebi.quickcut.ui.Stage14ExtractionScreensInstrumentedTest",
        "com.hebi.quickcut.media.Stage15OutputQualityInstrumentedTest",
        "com.hebi.quickcut.ui.Stage15ExportQualityScreensInstrumentedTest",
        "com.hebi.quickcut.media.Stage16CropExportInstrumentedTest",
        "com.hebi.quickcut.ui.Stage16CropScreensInstrumentedTest",
        "com.hebi.quickcut.media.Stage17AudioExportInstrumentedTest",
        "com.hebi.quickcut.ui.Stage17AudioScreensInstrumentedTest",
        "com.hebi.quickcut.media.Stage18TextOverlayInstrumentedTest",
        "com.hebi.quickcut.ui.Stage18TextEditorScreensInstrumentedTest",
        "com.hebi.quickcut.media.Stage19CombinedExportInstrumentedTest",
        "com.hebi.quickcut.media.Stage20RecoveryInstrumentedTest",
        "com.hebi.quickcut.ui.ThemeModeInstrumentedTest",
    )
    for i in range(count):
        classname = required[i] if phase in ("full", "remaining") and i < len(required) else phase + ".Suite"
        testname = "case" + str(i)
        if phase == "date":
            classname = "com.hebi.quickcut.media.BatchDateExportInstrumentedTest"
            testname = ("originalDatePreservesVideoCreationDate", "currentDateSavesAsNewVideo")[i]
        elif phase in ("offscreen_single", "offscreen_batch"):
            classname = "com.hebi.quickcut.export.BackgroundExportInstrumentedTest"
            testname = ("actualExportFinishesOffscreenAndItsResultSurvivesActivityRecreation"
                        if phase == "offscreen_single" else
                        "batchContinuesToTheSecondFileAndPublishesBothWhileScreenIsOff")
        elif phase == "media3":
            classname = "com.hebi.quickcut.export.BackgroundMedia3ExportInstrumentedTest"
            testname = ("media3ProcessingPublishesAValidOutputAfterAppSwitchAndScreenOff",
                        "cancellingOffscreenMedia3ProcessingLeavesNoGalleryOutputOrStagedWriter")[i]
        if classname.endswith("Stage20RecoveryInstrumentedTest") and os.environ.get("STAGE20_MISSING_SUITE") == "1":
            classname = phase + ".Suite"
        owner = suite if i < half else other_suite
        case = SubElement(owner, "testcase", classname=classname, name=testname)
        if failed and i == 0: SubElement(case, "failure").text = phase + " original failure"
    ElementTree(document).write(results / ("TEST-" + phase + ".xml"))
    reports = root / "app/build/reports/androidTests/connected"
    reports.mkdir(parents=True, exist_ok=True)
    (reports / "index.html").write_text(phase + " report")
    sys.exit(status)
if name == "adb":
    if args[:3] == ["exec-out", "run-as", "com.hebi.quickcut"]:
        if os.environ.get("STAGE13_CAPTURE_STATUS") == "1" and any("stage13_" in a for a in args):
            sys.exit(43)
        if os.environ.get("STAGE7_CAPTURE_STATUS") == "1":
            sys.exit(42)
        if any(("stage14_" in a or "stage15_" in a or "stage16_" in a or "stage17_" in a or "stage18_" in a or "theme_" in a or "ui03a_" in a or "ui04b_" in a or "ui05b_" in a) for a in args):
            if (any(os.environ.get("STAGE"+str(i)+"_CAPTURE_STATUS") == "1" for i in (14, 15, 16, 17, 18)) or
                    (os.environ.get("STAGE1A_CAPTURE_STATUS") == "1" and any("theme_" in a for a in args)) or
                    (os.environ.get("UI03A_CAPTURE_STATUS") == "1" and any("ui03a_" in a for a in args)) or
                    (os.environ.get("UI04B_CAPTURE_STATUS") == "1" and any("ui04b_batch_templates" in a for a in args)) or
                    (os.environ.get("UI05B_CAPTURE_STATUS") == "1" and any("ui05b_export_completed" in a for a in args))):
                sys.stdout.buffer.write(b"cat: files/design-review/stage15_export_auto.png: No such file")
                sys.exit(0)
            import struct, zlib
            def png_chunk(kind, data):
                return (len(data).to_bytes(4, "big") + kind + data +
                    (zlib.crc32(kind + data) & 0xffffffff).to_bytes(4, "big"))
            width, height = 360, 640
            header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
            pixels = (b"\x00" + b"\x00\x00\x00" * width) * height
            sys.stdout.buffer.write(
                bytes.fromhex("89504e470d0a1a0a") +
                png_chunk(b"IHDR", header) +
                png_chunk(b"IDAT", zlib.compress(pixels)) +
                png_chunk(b"IEND", b"")
            )
            sys.exit(0)
        sys.stdout.buffer.write(b"stub-png-evidence")
        sys.exit(0)
    if args[:3] == ["shell", "pm", "path"]:
        sys.exit(int(os.environ.get("RETAINED_APK_STATUS", "0")))
    if args and args[0] == "pull":
        destination = root / args[-1]
        destination.mkdir(parents=True, exist_ok=True)
        (destination / "evidence.txt").write_text(args[1])
        if args[1] == "/data/local/tmp/quickcut-design-review":
            import struct, zlib
            def chunk(kind, payload):
                return (len(payload).to_bytes(4, "big") + kind + payload +
                    (zlib.crc32(kind + payload) & 0xffffffff).to_bytes(4, "big"))
            width, height = 360, 640
            header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
            pixels = (b"\x00" + b"\x00\x00\x00" * width) * height
            png = (bytes.fromhex("89504e470d0a1a0a") +
                chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(pixels)) +
                chunk(b"IEND", b""))
            for mode in ("quick_trim", "detailed"):
                for theme in ("light", "dark"):
                    name = f"editor_design_{mode}_{theme}.png"
                    data = (b"adb: missing capture" if
                        os.environ.get("UI04A_CAPTURE_STATUS") == "1" and
                        mode == "detailed" and theme == "dark" else png)
                    (destination / name).write_bytes(data)
            # Dialog fixtures model only the shell's image-integrity gate;
            # a real device generates these from DialogDesignInstrumentedTest.
            for name in (
                "dialog_save_settings.png",
                "dialog_original_delete.png",
                "dialog_export_method.png",
                "dialog_export_cancel.png",
                "dialog_export_finalizing.png",
                "dialog_edit_recovery.png",
                "dialog_diagnostics.png",
                "dialog_task_error.png",
                "ui06a_preview_recovery.png",
                "dialog_save_large_font.png",
                "dialog_export_analysis_large_font.png",
                "dialog_licenses.png",
            ):
                contents = (
                    b"adb: capture missing" if
                    os.environ.get("UI06A_CAPTURE_STATUS") == "1" and name == "dialog_task_error.png"
                    else png
                )
                (destination / name).write_bytes(contents)
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
        # The CI unit-flow stub has no emulator matrix, but release evidence requires a device API.
        # Keep explicit API 29 scenarios unchanged and preserve the real runner's API when supplied.
        env.setdefault("QUICKCUT_EMULATOR_API", "35")
        result = subprocess.run(["bash", str(SCRIPT)], cwd=root, env=env,
                                capture_output=True, text=True, timeout=10)
        events = [json.loads(line) for line in (root / "events.jsonl").read_text().splitlines()]
        def gradle_phase(args):
            if any("NativeSmartCutInstrumentedTest" in a for a in args):
                return "preflight"
            if any("android.testInstrumentationRunnerArguments.notClass=" in a for a in args):
                return "remaining"
            if any("android.testInstrumentationRunnerArguments.class=com.hebi.quickcut.media.BatchDateExportInstrumentedTest" in a for a in args):
                return "date"
            if any("android.testInstrumentationRunnerArguments.class=com.hebi.quickcut.export.BackgroundExportInstrumentedTest#actualExportFinishesOffscreenAndItsResultSurvivesActivityRecreation" in a for a in args):
                return "offscreen_single"
            if any("android.testInstrumentationRunnerArguments.class=com.hebi.quickcut.export.BackgroundExportInstrumentedTest#batchContinuesToTheSecondFileAndPublishesBothWhileScreenIsOff" in a for a in args):
                return "offscreen_batch"
            if any("android.testInstrumentationRunnerArguments.class=com.hebi.quickcut.export.BackgroundMedia3ExportInstrumentedTest" in a for a in args):
                return "media3"
            return "full"
        phases = [gradle_phase(event) for event in events if event[0] == "gradle"]
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
        self.assertFalse((root / "quickcut-stage20-evidence/verified-screens-and-tests.json").exists())

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

    def test_retention_check_failure_still_runs_full_and_stays_failed(self):
        result, _, _, phases = self.run_flow(RETAINED_APK_STATUS=33)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertEqual(result.returncode, 33, result.stdout + result.stderr)

    def test_full_count_gate_stays_mandatory(self):
        result, _, _, _ = self.run_flow(FULL_COUNT=151)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Expected at least 152", result.stdout + result.stderr)

    def test_full_xml_failure_cannot_pass_with_successful_gradle(self):
        result, _, _, _ = self.run_flow(FULL_XML_FAILED=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("failed or skipped", result.stdout + result.stderr)

    def test_theme_1a_non_png_evidence_rejected(self):
        result, _, _, _ = self.run_flow(STAGE1A_CAPTURE_STATUS=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing/corrupt PNG evidence", result.stdout + result.stderr)

    def test_ui03a_non_png_screenshots_cannot_satisfy_visual_gate(self):
        result, _, _, phases = self.run_flow(UI03A_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing/corrupt PNG evidence", result.stdout + result.stderr)

    def test_ui04a_invalid_editor_capture_blocks_release(self):
        result, _, _, phases = self.run_flow(UI04A_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing/corrupt PNG evidence", result.stdout + result.stderr)

    def test_ui04b_bad_template_dialog_capture_fails_closed(self):
        result, _, _, phases = self.run_flow(UI04B_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing/corrupt PNG evidence", result.stdout + result.stderr)

    def test_ui05b_invalid_completion_capture_blocks_release(self):
        result, _, _, phases = self.run_flow(UI05B_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing/corrupt PNG evidence", result.stdout + result.stderr)

    def test_ui06a_invalid_error_dialog_capture_still_fails_gate(self):
        result, _, _, phases = self.run_flow(UI06A_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing/corrupt PNG evidence", result.stdout + result.stderr)

    def test_stage7_capture_failure_remains_a_required_gate(self):
        result, _, _, phases = self.run_flow(STAGE7_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Stage 7 screenshot missing or empty", result.stdout + result.stderr)

    def test_api29_keeps_full_suite_and_uses_bounded_30m_budget(self):
        result, _, events, phases = self.run_flow(QUICKCUT_EMULATOR_API="29")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(phases, ["preflight", "date", "remaining", "offscreen_single", "offscreen_batch", "media3"])
        timed = [e[1:3] for e in events if e[0] == "timeout" and len(e) > 3 and e[3] == "gradle"]
        self.assertEqual(timed, [["--kill-after=30s", "10m"], ["--kill-after=30s", "10m"],
                                 ["--kill-after=30s", "25m"], ["--kill-after=30s", "10m"],
                                 ["--kill-after=30s", "10m"], ["--kill-after=30s", "15m"]])
        self.assertIn("All 292 distinct API29 tests passed", result.stdout)

    def test_api29_failed_media3_phase_stays_failed_after_coverage_check(self):
        result, root, _, phases = self.run_flow(QUICKCUT_EMULATOR_API="29", MEDIA3_STATUS=44)
        self.assertEqual(phases, ["preflight", "date", "remaining", "offscreen_single", "offscreen_batch", "media3"])
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((root / "quickcut-stage20-evidence/verified-screens-and-tests.json").exists())

    def test_api29_failed_offscreen_single_fails_closed_with_no_approved_manifest(self):
        result, root, _, phases = self.run_flow(
            QUICKCUT_EMULATOR_API="29", OFFSCREEN_SINGLE_STATUS=46)
        self.assertEqual(phases, ["preflight", "date", "remaining", "offscreen_single",
                                  "offscreen_batch", "media3"])
        self.assertEqual(result.returncode, 46)
        self.assertIn("offscreen_single: failing or skipped tests", result.stdout + result.stderr)
        self.assertFalse((root / "quickcut-stage20-evidence/verified-screens-and-tests.json").exists())

    def test_api29_failed_offscreen_batch_fails_closed_with_no_approved_manifest(self):
        result, root, _, phases = self.run_flow(
            QUICKCUT_EMULATOR_API="29", OFFSCREEN_BATCH_STATUS=47)
        self.assertEqual(phases, ["preflight", "date", "remaining", "offscreen_single",
                                  "offscreen_batch", "media3"])
        self.assertEqual(result.returncode, 47)
        self.assertIn("offscreen_batch: failing or skipped tests", result.stdout + result.stderr)
        self.assertFalse((root / "quickcut-stage20-evidence/verified-screens-and-tests.json").exists())

    def test_stage16_non_png_error_text_does_not_pass_image_integrity_gate(self):
        result, _, _, phases = self.run_flow(STAGE16_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Invalid Stage16 PNG", result.stdout + result.stderr)

    def test_stage15_non_png_error_text_does_not_pass_image_integrity_gate(self):
        result, _, _, phases = self.run_flow(STAGE15_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Not a PNG screenshot", result.stdout + result.stderr)

    def test_stage17_non_png_evidence_is_rejected_without_weakening_suite(self):
        result, _, _, phases = self.run_flow(STAGE17_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Invalid Stage 17 PNG", result.stdout + result.stderr)

    def test_stage18_wrong_png_evidence_never_satisfies_image_integrity_gate(self):
        result, _, _, phases = self.run_flow(STAGE18_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Invalid Stage 18 PNG", result.stdout + result.stderr)

    def test_stage20_missing_mandatory_suite_does_not_pass(self):
        result, _, _, phases = self.run_flow(STAGE20_MISSING_SUITE=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing required stage 14-20 Android test classes",
            result.stdout + result.stderr)

    def test_stage20_stage14_bad_png_is_not_accepted(self):
        result, _, _, phases = self.run_flow(STAGE14_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing/corrupt PNG evidence", result.stdout + result.stderr)

    def test_stage13_capture_failure_remains_a_required_gate(self):
        result, _, _, phases = self.run_flow(STAGE13_CAPTURE_STATUS=1)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Stage 13 screenshot missing or empty", result.stdout + result.stderr)

    def test_both_successful_phases_run_all_gates_and_return_success(self):
        result, root, events, phases = self.run_flow()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(phases, ["preflight", "full"])
        self.assertFalse((root / "stale-preflight-observed").exists())
        self.assertTrue((root / "quickcut-native-preflight-results/results/TEST-preflight.xml").is_file())
        self.assertEqual(sum(e[0] == "python3" and any("verify-android-main10.py" in a for a in e) for e in events), 2)
        self.assertIn("152 distinct tests", result.stdout)
        proof = root / "quickcut-stage20-evidence/verified-screens-and-tests.json"
        self.assertTrue(proof.is_file())
        self.assertEqual(json.loads(proof.read_text())["androidApi"], "35")
        for image in (
            "dialog_save_settings.png", "dialog_original_delete.png",
            "dialog_export_method.png", "dialog_export_cancel.png",
            "dialog_export_finalizing.png", "dialog_edit_recovery.png",
            "dialog_diagnostics.png", "dialog_task_error.png",
            "ui06a_preview_recovery.png", "dialog_save_large_font.png", "dialog_export_analysis_large_font.png",
            "dialog_licenses.png",
        ):
            self.assertTrue((root / "quickcut-design-review" / image).is_file())
        for image in ("ui05b_export_progress.png", "ui05b_export_finalizing.png",
                      "ui05b_export_completed.png"):
            self.assertTrue((root / "quickcut-design-review" / image).is_file())
        for name in ("editor_design_quick_trim_light.png", "editor_design_quick_trim_dark.png",
                     "editor_design_detailed_light.png", "editor_design_detailed_dark.png"):
            self.assertTrue((root / "quickcut-design-review" / name).is_file())
        for image in ("ui04b_extraction_three.png", "ui04b_extraction_largefont.png",
                      "ui04b_batch_setup.png", "ui04b_batch_templates.png"):
            self.assertTrue((root / "quickcut-design-review" / image).is_file())
        for image in ("theme_light_home.png", "theme_dark_home.png",
                       *(f"ui03a_{theme}_{tab}.png"
                         for theme in ("light", "dark")
                         for tab in ("home", "projects", "tools", "settings")),
                      "stage7_home.png", "stage7_projects_empty.png", "stage7_onboarding.png",
                      "stage13_home_populated.png", "stage13_projects_populated.png",
                      "stage13_tools.png", "stage13_settings.png", "stage13_gallery_selected.png",
                      "stage13_gallery_one_selected.png", "stage13_projects_empty_large_font.png"):
            self.assertTrue((root / "quickcut-design-review" / image).is_file())
        timed = [e[1:3] for e in events if e[0] == "timeout" and len(e) > 3 and e[3] == "gradle"]
        self.assertEqual(timed, [["--kill-after=30s", "10m"], ["--kill-after=30s", "20m"]])
        self.assertIn("/data/local/tmp/quickcut-native-diagnostics", (root / "quickcut-native-preflight-results/diagnostics/evidence.txt").read_text())


if __name__ == "__main__":
    unittest.main()
