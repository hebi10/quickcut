#!/usr/bin/env python3
"""Stage 20 release gate: exact Android suite coverage and authentic new-feature screenshots.

Read-only validation. Never mutates a user's videos or project data.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import struct
import sys
import xml.etree.ElementTree as ET
import zlib

REQUIRED_TEST_CLASSES = (
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
)

SCREENSHOTS = {
    14: (
        "stage14_extraction_one.png",
        "stage14_extraction_three.png",
        "stage14_extraction_tablet_largefont.png",
    ),
    15: (
        "stage15_export_auto.png",
        "stage15_export_quality.png",
        "stage15_export_unsupported.png",
    ),
    16: (
        "stage16_crop_vertical.png",
        "stage16_crop_square.png",
        "stage16_crop_free.png",
        "stage16_crop_largefont.png",
    ),
    17: (
        "stage17_audio_default.png",
        "stage17_audio_fades.png",
        "stage17_audio_muted.png",
        "stage17_audio_largefont.png",
    ),
    18: (
        "stage18_text_empty.png",
        "stage18_text_selected.png",
        "stage18_text_styled.png",
        "stage18_text_largefont.png",
    ),
}


def validate_instrumentation(results_dir: Path) -> int:
    reports = sorted(results_dir.rglob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"No Android full-suite XML found under {results_dir}")

    executed = set()
    for report in reports:
        root = ET.parse(report).getroot()
        for suite in root.iter("testsuite"):
            for field in ("failures", "errors", "skipped"):
                if int(suite.attrib.get(field, "0")):
                    raise ValueError(f"{field} in {report}")
        for test in root.iter("testcase"):
            classname = test.attrib.get("classname")
            name = test.attrib.get("name")
            if not classname or not name:
                raise ValueError(f"Missing test identity in {report}")
            if any(test.find(field) is not None for field in ("failure", "error", "skipped")):
                raise ValueError(f"Non-passing test {classname}.{name}")
            executed.add(classname)

    missing = sorted(set(REQUIRED_TEST_CLASSES) - executed)
    if missing:
        raise ValueError("Missing required stage 14-20 Android test classes: " + ", ".join(missing))
    return len(executed)


def verify_png(path: Path) -> tuple[int, int]:
    data = path.read_bytes() if path.is_file() else b""
    signature = b"\x89PNG\r\n\x1a\n"
    if len(data) < 57 or not data.startswith(signature):
        raise ValueError(f"Missing/corrupt PNG evidence: {path}")

    offset = len(signature)
    seen_idat = False
    seen_iend = False
    dimensions = None
    while offset + 12 <= len(data):
        size = struct.unpack_from(">I", data, offset)[0]
        if size > 64 * 1024 * 1024:
            raise ValueError(f"Oversized PNG chunk: {path}")
        end = offset + 12 + size
        if end > len(data):
            raise ValueError(f"Truncated PNG chunk: {path}")
        tag = data[offset + 4 : offset + 8]
        payload = data[offset + 8 : offset + 8 + size]
        recorded = struct.unpack_from(">I", data, offset + 8 + size)[0]
        if zlib.crc32(tag + payload) & 0xFFFFFFFF != recorded:
            raise ValueError(f"PNG chunk CRC failed: {path}")
        if offset == 8:
            if tag != b"IHDR" or size != 13:
                raise ValueError(f"PNG IHDR missing: {path}")
            width, height = struct.unpack_from(">II", data, offset + 8)
            if not (200 <= width <= 10000 and 200 <= height <= 10000):
                raise ValueError(f"Implausible screenshot dimensions: {path}")
            dimensions = (width, height)
        elif tag == b"IDAT":
            seen_idat = True
        if tag == b"IEND":
            if size != 0 or end != len(data):
                raise ValueError(f"Invalid PNG trailer: {path}")
            seen_iend = True
            break
        offset = end

    if not seen_idat or not seen_iend or dimensions is None:
        raise ValueError(f"Missing actual PNG image data or trailer: {path}")
    return dimensions


def validate_screenshots(directory: Path) -> int:
    checked = 0
    for stage, names in SCREENSHOTS.items():
        for name in names:
            dimensions = verify_png(directory / name)
            print(f"Stage {stage}: {name} {dimensions[0]}x{dimensions[1]} verified")
            checked += 1
    return checked


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--xml", type=Path, required=True)
    parser.add_argument("--screenshots", type=Path, required=True)
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    tests = validate_instrumentation(args.xml)
    images = validate_screenshots(args.screenshots)
    if args.manifest is not None:
        # pull_request runs test the synthetic merge ref; retain both identities.
        source_sha = os.environ.get("QUICKCUT_SOURCE_SHA") or os.environ.get("GITHUB_SHA", "unknown")
        tested_sha = os.environ.get("GITHUB_SHA", "unknown")
        android_api = os.environ.get("QUICKCUT_EMULATOR_API", "unknown")
        if os.environ.get("GITHUB_ACTIONS") == "true":
            if not all(re.fullmatch(r"[0-9a-f]{40}", sha) for sha in (source_sha, tested_sha)):
                raise ValueError("Missing/invalid GitHub PR source or tested checkout SHA")
            if android_api not in {"29", "35", "36"}:
                raise ValueError("Missing/invalid Android API in release evidence")
        snapshots = []
        for stage, filenames in SCREENSHOTS.items():
            for name in filenames:
                path = args.screenshots / name
                width, height = verify_png(path)
                snapshots.append({
                    "stage": stage,
                    "filename": name,
                    "width": width,
                    "height": height,
                    "bytes": path.stat().st_size,
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                })
        manifest = {
            "schemaVersion": 2,
            "gitSha": source_sha,
            "testedGitSha": tested_sha,
            "androidApi": android_api,
            "androidTestClasses": list(REQUIRED_TEST_CLASSES),
            "verifiedAndroidTestClassCount": tests,
            "screenshots": snapshots,
        }
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.manifest.with_name(args.manifest.name + ".tmp")
        temporary.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        temporary.replace(args.manifest)
        print(f"Stage 20 integrity manifest saved: {args.manifest}")
    print(f"Stage 20 release gate: {tests} named test classes / {images} PNGs verified")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Stage 20 release gate FAILED: {error}", file=sys.stderr)
        sys.exit(1)
