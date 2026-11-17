#!/usr/bin/env python3
"""Validate source JAR diagnostics, not GPL license or Play release permission."""

import json
from pathlib import Path
import sys


REQUIRED_KEYS = {
    "schemaVersion",
    "resolvedComponents",
    "missingSourceJars",
    "sourceJarCount",
    "sourceJarResolutionComplete",
}


def _component_list(value, name):
    if not isinstance(value, list) or any(
        not isinstance(item, str)
        or not item
        or any(character in item for character in "\r\n\\/")
        for item in value
    ):
        raise ValueError(f"{name} must contain only dependency identifiers")
    if value != sorted(set(value)):
        raise ValueError(f"{name} must be sorted and unique")
    return value


def validate_report(report):
    if not isinstance(report, dict) or set(report) != REQUIRED_KEYS:
        raise ValueError("Unexpected runtime source audit schema")
    if report["schemaVersion"] != 1 or type(report["schemaVersion"]) is not int:
        raise ValueError("Unsupported audit schema version")
    resolved = _component_list(report["resolvedComponents"], "resolvedComponents")
    missing = _component_list(report["missingSourceJars"], "missingSourceJars")
    if not resolved:
        raise ValueError("Empty release dependency graph")
    if not set(missing).issubset(resolved):
        raise ValueError("Missing source entry not in resolved release dependencies")
    source_count = report["sourceJarCount"]
    if type(source_count) is not int or source_count < 0:
        raise ValueError("Invalid source JAR count")
    complete = report["sourceJarResolutionComplete"]
    if type(complete) is not bool or complete != (not missing and source_count > 0):
        raise ValueError("Inconsistent source JAR resolution status")
    return len(resolved), len(missing), complete


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: validate-runtime-source-audit.py PATH")
    report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    count, missing_count, complete = validate_report(report)
    status = "complete" if complete else "blocked"
    print(f"Runtime source JAR resolution: {status}; {count} dependencies, "
          f"{missing_count} missing source JARs.")
    for component in report["missingSourceJars"]:
        print(f"Missing source JAR: {component}")
    print("Diagnostic only: source JAR resolution never grants GPL/SDK license approval.")


if __name__ == "__main__":
    main()
