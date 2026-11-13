#!/usr/bin/env python3
"""UI-08-A: require aligned full Android media, recovery and visual QA evidence.

Reads only generated artifacts. A passing manifest is a necessary CI gate, not
proof of human visual approval, physical-device coverage, or Play publication.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

REQUIRED_MEDIA_CLASSES = {
    "com.hebi.quickcut.media.ExportIntegrityInstrumentedTest",
    "com.hebi.quickcut.media.OriginalMediaActionsInstrumentedTest",
    "com.hebi.quickcut.media.Stage17AudioExportInstrumentedTest",
    "com.hebi.quickcut.media.Stage19CombinedExportInstrumentedTest",
    "com.hebi.quickcut.media.Stage20RecoveryInstrumentedTest",
    "com.hebi.quickcut.batch.ExactBatchExportInstrumentedTest",
    "com.hebi.quickcut.editor.SingleExportRecoveryInstrumentedTest",
    "com.hebi.quickcut.export.BackgroundExportInstrumentedTest",
}
SAFETY_SCREENSHOTS = (
    "ui05b_export_progress.png",
    "ui05b_export_finalizing.png",
    "ui05b_export_completed.png",
    "dialog_original_delete.png",
    "dialog_export_cancel.png",
    "dialog_export_finalizing.png",
    "dialog_edit_recovery.png",
    "dialog_task_error.png",
    "ui06a_preview_recovery.png",
)


def json_object(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"Manifest is not an object: {path}")
    return data


def png_validator(script: Path):
    spec = importlib.util.spec_from_file_location("quickcut_png_validator", script)
    if spec is None or spec.loader is None:
        raise ValueError("Cannot load Stage20 canonical screenshot validator")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.verify_png


def validate_xml(directory: Path, api: str) -> tuple[int, int, set[str]]:
    reports = sorted(directory.rglob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"Missing full-suite Android XML: {directory}")
    tests: set[tuple[str, str]] = set()
    for path in reports:
        root = ET.parse(path).getroot()
        for suite in root.iter("testsuite"):
            for field in ("errors", "failures", "skipped"):
                if int(suite.attrib.get(field, "0")):
                    raise ValueError(f"Nonpassing Android suite: {path} {field}")
        for case in root.iter("testcase"):
            classname, name = case.attrib.get("classname"), case.attrib.get("name")
            if not classname or not name:
                raise ValueError(f"Android case without stable identity: {path}")
            if any(case.find(k) is not None for k in ("failure", "error", "skipped")):
                raise ValueError(f"Nonpassing Android test: {classname}.{name}")
            tests.add((classname, name))
    if api == "29" and len(tests) != 292:
        raise ValueError(f"API29 must execute exactly 292 tests, found {len(tests)}")
    if api in ("35", "36") and len(tests) < 152:
        raise ValueError(f"API{api} full-suite too small: {len(tests)} tests")
    classes = {cls for cls, _ in tests}
    missing = REQUIRED_MEDIA_CLASSES - classes
    if missing:
        raise ValueError("Required media/original/recovery tests not executed: " +
                         ", ".join(sorted(missing)))
    return len(tests), len(classes), classes


def validate_manifest(stage20: dict, matrix: dict, directory: Path,
                      api: str, verify_png) -> tuple[int, int]:
    if stage20.get("schemaVersion") != 2 or stage20.get("androidApi") != api:
        raise ValueError("Mismatched Stage20 API or schema version")
    head = stage20.get("gitSha", "")
    tested = stage20.get("testedGitSha", "")
    if os.environ.get("GITHUB_ACTIONS") == "true":
        if any(re.fullmatch(r"[0-9a-f]{40}", x) is None for x in (head, tested)):
            raise ValueError("Invalid exact source/tested Git SHA in Stage20")
        expected_source = os.environ.get("QUICKCUT_SOURCE_SHA")
        expected_tested = os.environ.get("GITHUB_SHA")
        if expected_source and head != expected_source:
            raise ValueError("Stage20 source SHA does not match PR head")
        if expected_tested and tested != expected_tested:
            raise ValueError("Stage20 tested SHA does not match checked-out commit")

    stage_images = stage20.get("screenshots")
    if not isinstance(stage_images, list) or len(stage_images) != 18:
        raise ValueError("Stage20 must include 18 original media UI screenshots")
    for item in stage_images:
        name = item["filename"]
        path = directory / name
        size = verify_png(path)
        if list(size) != [item.get("width"), item.get("height")]:
            raise ValueError(f"Stage20 PNG geometry mismatch: {name}")
        if path.stat().st_size != item.get("bytes"):
            raise ValueError(f"Stage20 PNG byte count mismatch: {name}")
        if hashlib.sha256(path.read_bytes()).hexdigest() != item.get("sha256"):
            raise ValueError(f"Stage20 PNG hash mismatch: {name}")

    if matrix.get("contract") != "ui07a-visual-matrix-v1":
        raise ValueError("Missing UI07A paired visual matrix contract")
    if matrix.get("pairs") != 20 or matrix.get("images") != 40:
        raise ValueError("Missing required 20 light/dark screen pairs")
    pairs = matrix.get("screenshots")
    if not isinstance(pairs, list) or len(pairs) != 20:
        raise ValueError("Visual matrix does not contain 20 actual pairs")
    names: set[str] = set()
    for item in pairs:
        for mode in ("light", "dark"):
            capture = item.get(mode)
            if not isinstance(capture, dict):
                raise ValueError(f"Missing paired {mode} capture: {item.get('screen')}")
            name = capture["name"]
            if name in names:
                raise ValueError(f"Repeated screenshot in visual matrix: {name}")
            names.add(name)
            path = directory / name
            width, height = verify_png(path)
            if (width, height) != (capture.get("width"), capture.get("height")):
                raise ValueError(f"Paired screenshot dimension mismatch: {name}")
            if hashlib.sha256(path.read_bytes()).hexdigest() != capture.get("sha256"):
                raise ValueError(f"Paired screenshot hash mismatch: {name}")
        if item["light"]["sha256"] == item["dark"]["sha256"]:
            raise ValueError(f"Identical light/dark screenshot: {item.get('screen')}")

    for name in SAFETY_SCREENSHOTS:
        verify_png(directory / name)
    return len(stage_images), len(names)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", choices=("29", "35", "36"), required=True)
    parser.add_argument("--xml", type=Path, required=True)
    parser.add_argument("--screenshots", type=Path, required=True)
    parser.add_argument("--stage20", type=Path, required=True)
    parser.add_argument("--ui07a", type=Path, required=True)
    parser.add_argument("--png-script", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    total, classes, _ = validate_xml(args.xml, args.api)
    stage20 = json_object(args.stage20)
    matrix = json_object(args.ui07a)
    media_images, paired_images = validate_manifest(
        stage20, matrix, args.screenshots, args.api,
        png_validator(args.png_script),
    )
    report = {
        "contract": "quickcut-ui08a-qa-v1",
        "status": "automated evidence passed; human visual and production QA pending",
        "androidApi": args.api,
        "sourceSha": stage20.get("gitSha"),
        "testedSha": stage20.get("testedGitSha"),
        "verifiedTestCases": total,
        "verifiedTestClasses": classes,
        "requiredMediaClassCount": len(REQUIRED_MEDIA_CLASSES),
        "mediaScreenshots": media_images,
        "pairedThemeScreenshots": paired_images,
        "safetyScreenshots": len(SAFETY_SCREENSHOTS),
        "playDeployed": False,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
    print(f"UI08A QA passed for API{args.api}: {total} tests, {classes} classes, "
          f"{media_images} media PNGs, {paired_images} theme PNGs, "
          f"{len(SAFETY_SCREENSHOTS)} safety PNGs.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        print(f"UI08A release evidence FAILED: {error}", file=sys.stderr)
        sys.exit(1)
