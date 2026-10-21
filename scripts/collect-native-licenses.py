#!/usr/bin/env python3
"""Collect the exact native dependency notices before the APK is assembled."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

repo = Path(__file__).resolve().parent.parent
cache = Path(os.environ.get("QUICKCUT_NATIVE_CACHE", repo / "build/native-smartcut"))
manifest = repo / "native/quickcut-smartcut/Cargo.toml"
pins = json.loads((repo / "scripts/native-smartcut-sources.json").read_text())
metadata = json.loads(subprocess.check_output(
    ["cargo", "+" + pins["toolchains"]["rust"], "metadata", "--locked", "--format-version", "1", "--manifest-path", str(manifest)],
    cwd=repo, text=True))
sections = ["QuickCut native dependency copyright and license notices\n"
            "The complete corresponding source is supplied with this APK.\n"
            "This file preserves component notices; the combined distribution is GPL version 3.\n"]
missing = []

def notice_files(directory):
    files = [p for p in directory.iterdir() if p.is_file()
             and re.match(r"^(license|copying|notice|copyright)([._-]|$)", p.name, re.I)]
    for folder in (directory / "LICENSES", directory / "licenses"):
        if folder.is_dir():
            files.extend(p for p in folder.rglob("*") if p.is_file())
    return sorted(set(files))

def append(name, declared, files):
    if not files:
        missing.append(name)
        return
    sections.append("\n" + "=" * 72 + "\n" + name + "\nDeclared license: " + (declared or "see texts") + "\n")
    seen = set()
    for path in files:
        data = path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if digest in seen:
            continue
        seen.add(digest)
        sections.append("\n--- " + path.name + " ---\n" + data.decode("utf-8", errors="replace") + "\n")

for component in ("ffmpeg", "x264", "x265"):
    source = cache / "sources" / pins[component]["directory"]
    if not source.is_dir():
        raise RuntimeError("Native source directory is missing: " + str(source))
    append(component + " " + pins[component].get("version", pins[component].get("commit", "")),
           "GPL-compatible build; see original component texts", notice_files(source))

# x265 links the NDK's static libc++/libc++abi/unwind runtime into the APK.
# Retain the notices shipped with the exact pinned NDK, not host C++ notices.
sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
ndk = Path(os.environ.get("ANDROID_NDK_HOME", sdk / "ndk" / pins["toolchains"]["ndk"]))
ndk_notices = [ndk / "NOTICE", ndk / "NOTICE.toolchain"]
if any(not path.is_file() for path in ndk_notices):
    raise RuntimeError("Pinned NDK runtime notices are missing")
append("Android NDK " + pins["toolchains"]["ndk"] + " C++ runtime", "see original runtime notices", ndk_notices)

resolved = {node["id"] for node in metadata["resolve"]["nodes"]}
for package in sorted(metadata["packages"], key=lambda p: (p["name"], p["version"])):
    if package["id"] not in resolved:
        continue
    directory = Path(package["manifest_path"]).parent
    files = notice_files(directory)
    if package.get("license_file"):
        explicit = directory / package["license_file"]
        if explicit.is_file():
            files.append(explicit)
    # The vendored workspace applies its top-level license to its core crates.
    if not files and (directory == repo / "native/vendor/smartcut" or
                      repo / "native/vendor/smartcut" in directory.parents):
        files = [repo / "native/vendor/smartcut/LICENSE"]
    # The JNI adapter and app belong to this combined GPLv3 distribution.
    if not files and directory == manifest.parent:
        files = [repo / "third_party/licenses/GPL-3.0.txt"]
    # These two published crates omit license texts. Their pinned upstream
    # declarations/files and provenance are preserved alongside the APK notices.
    if not files and (package["name"], package["version"]) == ("ffmpeg-sys-next", "7.1.3"):
        files = [repo / "third_party/licenses/ffmpeg-sys-next-7.1.3-WTFPL.txt"]
    if not files and (package["name"], package["version"]) == ("jni-sys-macros", "0.4.1"):
        files = [repo / ("third_party/licenses/" + name) for name in (
            "jni-sys-macros-0.4.1-NOTICE.txt", "jni-sys-MIT.txt", "jni-sys-Apache-2.0.txt")]
    append(package["name"] + " " + package["version"], package.get("license"), files)

if missing:
    raise RuntimeError("Native dependencies need original license texts: " + ", ".join(missing))
output = repo / "build/generated/smartcutAssets/licenses/NATIVE-DEPENDENCIES.txt"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text("".join(sections), encoding="utf-8")
print("Native notices collected: " + str(output.relative_to(repo)))
