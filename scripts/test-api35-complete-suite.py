#!/usr/bin/env python3
"""Offline fail-closed coverage for Android 15 codec stress test process isolation."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location(
    "api35gate", Path(__file__).with_name("verify-api35-complete-suite.py"))
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class FullApi35Coverage(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for phase, count in gate.EXPECTED.items():
            root = ET.Element("testsuites", tests=str(count), errors="0", failures="0", skipped="0")
            suite = ET.SubElement(root, "testsuite", tests=str(count), errors="0", failures="0", skipped="0")
            for index in range(count):
                if phase in gate.ISOLATED:
                    classname, name = gate.ISOLATED[phase]
                else:
                    classname = gate.NATIVE if phase == "native" else "com.hebi.quickcut.test.Regular"
                    name = f"test{index}"
                ET.SubElement(suite, "testcase", classname=classname, name=name)
            ET.ElementTree(root).write(self.root / f"TEST-api35-{phase}.xml")

    def test_complete_original_suite_passes(self):
        gate.verify(self.root)

    def test_missing_phase_fails_closed(self):
        (self.root / "TEST-api35-media3_cancel.xml").unlink()
        with self.assertRaisesRegex(ValueError, "Missing"):
            gate.verify(self.root)

    def test_missing_regular_test_fails_closed(self):
        p = self.root / "TEST-api35-regular.xml"
        root = ET.parse(p)
        root.getroot().find("testsuite").remove(root.getroot().find("testsuite/testcase"))
        root.write(p)
        with self.assertRaisesRegex(ValueError, "267"):
            gate.verify(self.root)

    def test_wrong_class_in_native_phase_is_rejected(self):
        path = self.root / "TEST-api35-native.xml"
        document = ET.parse(path)
        document.getroot().find("testsuite/testcase").set("classname", "com.hebi.quickcut.test.Regular")
        document.write(path)
        with self.assertRaisesRegex(ValueError, "unexpected native test identity"):
            gate.verify(self.root)

    def test_media3_wrong_test_name_rejected(self):
        p = self.root / "TEST-api35-media3_cancel.xml"
        document = ET.parse(p)
        document.getroot().find("testsuite/testcase").set("name", gate.PUBLISH)
        document.write(p)
        with self.assertRaisesRegex(ValueError, "identity"):
            gate.verify(self.root)

    def test_failed_test_cannot_pass(self):
        p = self.root / "TEST-api35-media3_cancel.xml"
        root = ET.parse(p)
        ET.SubElement(root.getroot().find("testsuite/testcase"), "failure")
        root.write(p)
        with self.assertRaisesRegex(ValueError, "failed"):
            gate.verify(self.root)

    def test_duplicate_identity_is_not_accepted(self):
        p = self.root / "TEST-api35-regular.xml"
        root = ET.parse(p)
        cases = root.getroot().findall("testsuite/testcase")
        cases[1].set("name", cases[0].get("name"))
        root.write(p)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            gate.verify(self.root)

    def test_extra_xml_is_rejected(self):
        (self.root / "TEST-old.xml").write_text("<testsuites/>")
        with self.assertRaisesRegex(ValueError, "extra"):
            gate.verify(self.root)


if __name__ == "__main__":
    unittest.main()
