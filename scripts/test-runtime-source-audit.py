#!/usr/bin/env python3
"""Hermetic regression cases for non-authoritative runtime source audits."""

import importlib.util
from pathlib import Path
import unittest


module_path = Path(__file__).with_name("validate-runtime-source-audit.py")
spec = importlib.util.spec_from_file_location("runtime_source_audit_validator", module_path)
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def fixture(missing=None, source_count=1, complete=None):
    missing = [] if missing is None else missing
    if complete is None:
        complete = not missing and source_count > 0
    return {
        "schemaVersion": 1,
        "resolvedComponents": ["com.example:core:1.0", "com.example:network:1.0"],
        "missingSourceJars": missing,
        "sourceJarCount": source_count,
        "sourceJarResolutionComplete": complete,
    }


class SourceAuditContractTest(unittest.TestCase):
    def test_missing_source_is_a_valid_blocked_diagnostic_not_a_success(self):
        count, missing, complete = audit.validate_report(
            fixture(["com.example:network:1.0"]))
        self.assertEqual((count, missing, complete), (2, 1, False))

    def test_complete_source_jar_inventory_is_not_a_license_decision(self):
        self.assertEqual(audit.validate_report(fixture()), (2, 0, True))

    def test_missing_source_cannot_be_marked_complete(self):
        with self.assertRaises(ValueError):
            audit.validate_report(fixture(["com.example:network:1.0"], complete=True))

    def test_zero_resolved_sources_cannot_be_marked_complete(self):
        with self.assertRaises(ValueError):
            audit.validate_report(fixture(source_count=0, complete=True))

    def test_unknown_component_cannot_be_reported_missing(self):
        with self.assertRaises(ValueError):
            audit.validate_report(fixture(["com.example:unknown:1.0"]))

    def test_duplicate_or_unsorted_components_are_rejected(self):
        report = fixture()
        report["resolvedComponents"] = ["com.example:network:1.0", "com.example:core:1.0"]
        with self.assertRaises(ValueError):
            audit.validate_report(report)
        report["resolvedComponents"] = ["com.example:core:1.0"] * 2
        with self.assertRaises(ValueError):
            audit.validate_report(report)

    def test_extra_fields_and_paths_are_rejected(self):
        report = fixture()
        report["signingSecret"] = "not allowed"
        with self.assertRaises(ValueError):
            audit.validate_report(report)
        report = fixture()
        report["resolvedComponents"] = ["com.example:core:1.0", "/private/path"]
        with self.assertRaises(ValueError):
            audit.validate_report(report)

    def test_wrong_source_count_type_is_rejected(self):
        report = fixture()
        report["sourceJarCount"] = True
        with self.assertRaises(ValueError):
            audit.validate_report(report)


if __name__ == "__main__":
    unittest.main()
