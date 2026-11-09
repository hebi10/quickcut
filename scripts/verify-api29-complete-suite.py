#!/usr/bin/env python3
"""Require all 297 Android 10 tests across isolated instrumentation processes."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

DATE = "com.hebi.quickcut.media.BatchDateExportInstrumentedTest"
MEDIA3 = "com.hebi.quickcut.export.BackgroundMedia3ExportInstrumentedTest"
ISOLATED = {
    "date": {
        (DATE, "originalDatePreservesVideoCreationDate"),
        (DATE, "currentDateSavesAsNewVideo"),
    },
    "media3": {
        (MEDIA3, "media3ProcessingPublishesAValidOutputAfterAppSwitchAndScreenOff"),
        (MEDIA3, "cancellingOffscreenMedia3ProcessingLeavesNoGalleryOutputOrStagedWriter"),
    },
}
TOTAL_COUNT = 297
REST_COUNT = 293


def verify(directory: Path):
    phases = ("date", "remaining", "media3")
    files = list(directory.rglob("TEST-*.xml"))
    if len(files) != 3 or {p.name for p in files} != {
        f"TEST-api29-{phase}.xml" for phase in phases
    }:
        raise ValueError("Missing, extra or stale API29 suite XML files")
    covered = set()
    for phase in phases:
        file = directory / f"TEST-api29-{phase}.xml"
        document = ET.parse(file).getroot()
        if document.tag not in ("testsuites", "testsuite"):
            raise ValueError(f"{phase}: unexpected JUnit document root")
        suites = list(document.iter("testsuite"))
        if not suites:
            raise ValueError(f"{phase}: no JUnit testsuite elements")
        cases = list(document.iter("testcase"))
        expected = 2 if phase in ISOLATED else REST_COUNT
        if len(cases) != expected or int(document.get("tests", "-1")) != expected:
            raise ValueError(f"{phase}: expected {expected} cases, got {len(cases)}")
        if any(int(document.get(key, "-1")) for key in ("errors", "failures", "skipped")):
            raise ValueError(f"{phase}: failing or skipped tests in JUnit root")
        aggregate = 0
        for suite in suites:
            suite_cases = list(suite.iter("testcase"))
            suite_count = int(suite.get("tests", "-1"))
            if suite_count != len(suite_cases):
                raise ValueError(f"{phase}: testsuite count mismatch")
            if any(int(suite.get(key, "-1")) for key in ("errors", "failures", "skipped")):
                raise ValueError(f"{phase}: failed or skipped tests")
            aggregate += suite_count
        if aggregate != expected:
            raise ValueError(f"{phase}: JUnit suites do not cover all {expected} tests")
        found = set()
        for case in cases:
            ident = (case.get("classname"), case.get("name"))
            if not all(ident) or any(case.find(key) is not None for key in ("failure", "error", "skipped")):
                raise ValueError(f"{phase}: a case failed, skipped or lacks an identity")
            if ident in found or ident in covered:
                raise ValueError(f"{phase}: duplicate test identity: {ident}")
            found.add(ident)
            covered.add(ident)
        if phase in ISOLATED and found != ISOLATED[phase]:
            raise ValueError(f"{phase}: required original tests were not executed")
        if phase == "remaining" and any(cls in (DATE, MEDIA3) for cls, _ in found):
            raise ValueError("remaining: an isolated test was executed twice")
        print(f"API29 {phase}: verified {len(found)} passing tests")
    if len(covered) != TOTAL_COUNT:
        raise ValueError(f"API29 coverage missing: {len(covered)} of {TOTAL_COUNT}")
    print(f"All {TOTAL_COUNT} distinct API29 tests passed with no failures, skips or duplicates")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]))
    except (ValueError, ET.ParseError, IndexError, FileNotFoundError) as error:
        print(f"::error::API29 full coverage verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
