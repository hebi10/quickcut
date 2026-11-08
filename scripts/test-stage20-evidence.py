#!/usr/bin/env python3
"""Self-tests for Stage 20 release evidence enforcement (stdlib only)."""
from __future__ import annotations

import importlib.util
import json
import os
import sys
from unittest.mock import patch
from pathlib import Path
import struct
import tempfile
import unittest
from xml.etree.ElementTree import Element, ElementTree, SubElement
import zlib

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("stage20_release_evidence", HERE / "stage20-evidence-verify.py")
assert spec and spec.loader
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def make_png() -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    width, height = 360, 640
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    pixels = (b"\x00" + bytes(width * 3)) * height
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) +
            chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))


class Stage20EvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        self.images = root / "screenshots"
        self.reports = root / "results"
        self.images.mkdir()
        self.reports.mkdir()
        png = make_png()
        for names in gate.SCREENSHOTS.values():
            for name in names:
                (self.images / name).write_bytes(png)
        self.write_xml()

    def write_xml(self, remove: str | None = None) -> None:
        suite = Element("testsuite", tests=str(len(gate.REQUIRED_TEST_CLASSES)))
        for i, classname in enumerate(gate.REQUIRED_TEST_CLASSES):
            if classname != remove:
                SubElement(suite, "testcase", classname=classname, name=f"test{i}")
        ElementTree(suite).write(self.reports / "TEST-stage20.xml", encoding="utf-8")

    def test_complete_evidence_passes(self) -> None:
        self.assertEqual(gate.validate_instrumentation(self.reports), len(gate.REQUIRED_TEST_CLASSES))
        self.assertEqual(gate.validate_screenshots(self.images),
                         sum(map(len, gate.SCREENSHOTS.values())))

    def test_manifest_is_created_only_after_complete_evidence(self) -> None:
        manifest = self.reports.parent / "release" / "proof.json"
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}):
            with patch.object(sys, "argv", [
                "stage20-evidence-verify.py", "--xml", str(self.reports),
                "--screenshots", str(self.images), "--manifest", str(manifest),
            ]):
                self.assertEqual(gate.main(), 0)
        verified = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(verified["schemaVersion"], 2)
        self.assertEqual(len(verified["screenshots"]), 18)
        self.assertEqual(len(verified["androidTestClasses"]), len(gate.REQUIRED_TEST_CLASSES))
        self.assertEqual(len(verified["screenshots"][0]["sha256"]), 64)

    def test_ci_manifest_distinguishes_pr_head_from_tested_merge(self) -> None:
        manifest = self.reports.parent / "release" / "proof.json"
        source = "a" * 40
        tested = "b" * 40
        with patch.dict(os.environ, {
            "GITHUB_ACTIONS": "true",
            "QUICKCUT_SOURCE_SHA": source,
            "GITHUB_SHA": tested,
            "QUICKCUT_EMULATOR_API": "35",
        }):
            with patch.object(sys, "argv", [
                "stage20-evidence-verify.py", "--xml", str(self.reports),
                "--screenshots", str(self.images), "--manifest", str(manifest),
            ]):
                self.assertEqual(gate.main(), 0)
        verified = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(verified["gitSha"], source)
        self.assertEqual(verified["testedGitSha"], tested)
        self.assertEqual(verified["androidApi"], "35")

    def test_ci_refuses_unverifiable_commit_identity(self) -> None:
        manifest = self.reports.parent / "release" / "proof.json"
        with patch.dict(os.environ, {
            "GITHUB_ACTIONS": "true",
            "QUICKCUT_SOURCE_SHA": "unknown",
            "GITHUB_SHA": "a" * 40,
            "QUICKCUT_EMULATOR_API": "36",
        }):
            with patch.object(sys, "argv", [
                "stage20-evidence-verify.py", "--xml", str(self.reports),
                "--screenshots", str(self.images), "--manifest", str(manifest),
            ]):
                with self.assertRaisesRegex(ValueError, "source or tested checkout SHA"):
                    gate.main()
        self.assertFalse(manifest.exists())

    def test_failed_evidence_does_not_leave_a_success_manifest(self) -> None:
        manifest = self.reports.parent / "release" / "proof.json"
        (self.images / gate.SCREENSHOTS[14][0]).unlink()
        with patch.object(sys, "argv", [
            "stage20-evidence-verify.py", "--xml", str(self.reports),
            "--screenshots", str(self.images), "--manifest", str(manifest),
        ]):
            with self.assertRaisesRegex(ValueError, "Missing/corrupt PNG"):
                gate.main()
        self.assertFalse(manifest.exists())

    def test_legacy_suite_count_does_not_replace_required_class_identity(self) -> None:
        self.write_xml(remove="com.hebi.quickcut.media.Stage20RecoveryInstrumentedTest")
        with self.assertRaisesRegex(ValueError, "Stage20RecoveryInstrumentedTest"):
            gate.validate_instrumentation(self.reports)

    def test_theme_mode_class_is_a_required_release_gate(self) -> None:
        self.write_xml(remove="com.hebi.quickcut.ui.ThemeModeInstrumentedTest")
        with self.assertRaisesRegex(ValueError, "ThemeModeInstrumentedTest"):
            gate.validate_instrumentation(self.reports)

    def test_missing_png_fails(self) -> None:
        (self.images / gate.SCREENSHOTS[14][0]).unlink()
        with self.assertRaisesRegex(ValueError, "Missing/corrupt PNG"):
            gate.validate_screenshots(self.images)

    def test_adb_error_text_cannot_count_as_screenshot(self) -> None:
        (self.images / gate.SCREENSHOTS[17][0]).write_bytes(b"adb: device offline")
        with self.assertRaisesRegex(ValueError, "Missing/corrupt PNG"):
            gate.validate_screenshots(self.images)

    def test_corrupt_idat_crc_fails_even_if_ihdr_is_valid(self) -> None:
        path = self.images / gate.SCREENSHOTS[18][0]
        image = bytearray(path.read_bytes())
        image[-14] ^= 1
        path.write_bytes(image)
        with self.assertRaisesRegex(ValueError, "CRC|trailer"):
            gate.validate_screenshots(self.images)

    def test_missing_xml_results_cannot_pass(self) -> None:
        (self.reports / "TEST-stage20.xml").unlink()
        with self.assertRaisesRegex(ValueError, "No Android full-suite XML"):
            gate.validate_instrumentation(self.reports)

    def test_failed_suite_fails_even_if_every_class_is_present(self) -> None:
        self.write_xml()
        xml_path = self.reports / "TEST-stage20.xml"
        tree = __import__("xml.etree.ElementTree", fromlist=["parse"]).parse(xml_path)
        tree.getroot().set("failures", "1")
        tree.write(xml_path)
        with self.assertRaisesRegex(ValueError, "failures"):
            gate.validate_instrumentation(self.reports)


if __name__ == "__main__":
    unittest.main()
