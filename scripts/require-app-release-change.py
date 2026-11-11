#!/usr/bin/env python3
"""Allow automatic app version releases only for runtime/build-source changes.

The Google Play listing-image workflow has its own gate and remains eligible
for store-only commits. Manual app releases remain explicitly user-controlled.
"""
import os
from pathlib import Path
import subprocess
import sys

RUNTIME_PREFIXES = (
    "app/src/main/",
    "native/",
    "gradle/",
    "third_party/",
)
RUNTIME_FILES = frozenset({
    "app/build.gradle.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "scripts/build-native-smartcut.sh",
    "scripts/native-smartcut-sources.json",
})


def app_release_required(changed_paths):
    return any(
        path.startswith(RUNTIME_PREFIXES) or path in RUNTIME_FILES
        for path in changed_paths
    )


def main():
    try:
        parent = subprocess.check_output(
            ["git", "rev-parse", "HEAD^"], text=True, stderr=subprocess.PIPE
        ).strip()
        sha = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], text=True, stderr=subprocess.PIPE
        ).strip()
        changed = subprocess.check_output(
            ["git", "diff", "--name-only", parent, sha],
            text=True, stderr=subprocess.PIPE,
        ).splitlines()
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"::error::Could not establish first-parent runtime diff: {type(error).__name__}",
              file=sys.stderr)
        return 1
    if len(sha) != 40 or len(parent) != 40:
        print("::error::Invalid commit identity for automatic release gate", file=sys.stderr)
        return 1
    eligible = app_release_required(changed)
    output = os.environ.get("GITHUB_OUTPUT")
    if not output:
        print("::error::Missing GitHub Actions output for automatic release gate", file=sys.stderr)
        return 1
    with Path(output).open("a", encoding="utf-8") as stream:
        stream.write(f"eligible={str(eligible).lower()}\n")
    if eligible:
        print("Automatic app release eligible: runtime or build source changed")
    else:
        print("Automatic app release skipped: no runtime/build source changes; "
              "store listing images use independent publishing workflow")
    return 0


if __name__ == "__main__":
    sys.exit(main())
