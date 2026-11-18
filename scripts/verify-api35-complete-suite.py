#!/usr/bin/env python3
"""Fail closed unless all 292 API35 tests passed across isolated Media3 processes."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

MEDIA3 = "com.hebi.quickcut.export.BackgroundMedia3ExportInstrumentedTest"
PUBLISH = "media3ProcessingPublishesAValidOutputAfterAppSwitchAndScreenOff"
CANCEL = "cancellingOffscreenMedia3ProcessingLeavesNoGalleryOutputOrStagedWriter"
EXPECTED = {
    "regular": 290,
    "media3_publish": 1,
    "media3_cancel": 1,
}
ISOLATED = {
    "media3_publish": (MEDIA3, PUBLISH),
    "media3_cancel": (MEDIA3, CANCEL),
}


def verify(folder):
    files = sorted(folder.rglob("TEST-*.xml"))
    required_names = {f"TEST-api35-{phase}.xml" for phase in EXPECTED}
    if len(files) != len(EXPECTED) or {x.name for x in files} != required_names:
        raise ValueError("Missing, duplicate, extra or stale API35 test XML files")
    seen = set()
    for phase, count in EXPECTED.items():
        document = ET.parse(folder / f"TEST-api35-{phase}.xml").getroot()
        if document.tag not in ("testsuites", "testsuite"):
            raise ValueError(f"{phase}: invalid JUnit root")
        suites = list(document.iter("testsuite"))
        cases = list(document.iter("testcase"))
        if not suites or len(cases) != count or int(document.get("tests", "-1")) != count:
            raise ValueError(f"{phase}: expected {count} original tests, found {len(cases)}")
        if any(int(document.get(k, "-1")) != 0 for k in ("failures", "errors", "skipped")):
            raise ValueError(f"{phase}: JUnit root has failed/skipped tests")
        total = 0
        for suite in suites:
            children = list(suite.iter("testcase"))
            suite_count = int(suite.get("tests", "-1"))
            if suite_count != len(children):
                raise ValueError(f"{phase}: JUnit test count mismatch")
            if any(int(suite.get(k, "-1")) for k in ("failures", "errors", "skipped")):
                raise ValueError(f"{phase}: a test suite failed or skipped tests")
            total += suite_count
        if total != count:
            raise ValueError(f"{phase}: missing test suite records")
        for case in cases:
            identity = (case.get("classname"), case.get("name"))
            if not all(identity) or identity in seen:
                raise ValueError(f"{phase}: duplicate or invalid test identity: {identity}")
            if any(case.find(k) is not None for k in ("failure", "error", "skipped")):
                raise ValueError(f"{phase}: a test failed or skipped: {identity}")
            if phase == "regular" and identity[0] == MEDIA3:
                raise ValueError("Media3 stress test leaked into regular process")
            if phase in ISOLATED and identity != ISOLATED[phase]:
                raise ValueError(f"{phase}: unexpected Media3 test identity: {identity}")
            seen.add(identity)
        print(f"API35 {phase}: {count} distinct passing test(s)")
    if len(seen) != 292:
        raise ValueError(f"API35 suite has {len(seen)} tests instead of 292")
    print("All 292 distinct API35 tests passed with no failures, skips or duplicates")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]))
    except (ValueError, ET.ParseError, FileNotFoundError, IndexError) as error:
        print(f"::error::API35 full coverage verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
