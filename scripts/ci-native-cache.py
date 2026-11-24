#!/usr/bin/env python3
"""Fail-closed, content-bound reuse of Android native CI binaries; no Play release authorization."""
import hashlib
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parent.parent
CONTRACT = "quickcut-native-android-cache-v1"
MANIFEST = ROOT / "build/native-smartcut/.ci-native-outputs.json"
SOURCE_FILES = ("scripts/build-native-smartcut.sh",
                "scripts/native-smartcut-sources.json",
                "scripts/ci-native-cache.py")
OUTPUTS = (
    "app/build/generated/nativeLibs/arm64-v8a/libquickcut_smartcut.so",
    "app/build/generated/nativeLibs/x86_64/libquickcut_smartcut.so",
    "build/generated/smartcutAssets/licenses/NATIVE-DEPENDENCIES.txt",
)
MACHINES = {"arm64-v8a": 183, "x86_64": 62}


def digest(path):
    hasher = hashlib.sha256()
    with path.open("rb") as data:
        for chunk in iter(lambda: data.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def source_fingerprint():
    files = [ROOT / name for name in SOURCE_FILES]
    files += sorted(path for path in (ROOT / "native").rglob("*") if path.is_file())
    hasher = hashlib.sha256()
    for path in files:
        if not path.is_file() or path.is_symlink():
            raise ValueError("Missing or nonregular native input: " + str(path))
        relative = path.relative_to(ROOT).as_posix().encode("utf-8")
        hasher.update(len(relative).to_bytes(4, "big"))
        hasher.update(relative)
        hasher.update(bytes.fromhex(digest(path)))
    return hasher.hexdigest()


def valid_shared_library(path, abi):
    data = path.read_bytes()
    if len(data) < 120 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Invalid ELF64 native library: " + abi)
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != MACHINES[abi]:
        raise ValueError("Wrong native architecture: " + abi)
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or count == 0 or offset + count * entry_size > len(data):
        raise ValueError("Invalid native program header: " + abi)
    alignments = []
    for index in range(count):
        kind, _, _, _, _, _, _, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, offset + index * entry_size)
        if kind == 1:
            alignments.append(alignment)
    if not alignments or min(alignments) < 16_384:
        raise ValueError("Native ELF lacks 16 KiB page alignment: " + abi)


def evidence():
    result = {}
    for relative in OUTPUTS:
        path = ROOT / relative
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            raise ValueError("Missing or empty native artifact: " + relative)
        for abi in MACHINES:
            if "/nativeLibs/" + abi + "/" in relative:
                valid_shared_library(path, abi)
        result[relative] = {"size": path.stat().st_size, "sha256": digest(path)}
    return result


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("write", "verify"):
        raise SystemExit("Usage: ci-native-cache.py write|verify")
    fingerprint = source_fingerprint()
    outputs = evidence()
    if sys.argv[1] == "verify":
        if not MANIFEST.is_file():
            raise ValueError("Missing independently generated native cache manifest")
        meta = json.loads(MANIFEST.read_text(encoding="utf-8"))
        if meta.get("contract") != CONTRACT or meta.get("inputs") != fingerprint:
            raise ValueError("Native cache is not bound to current sources")
        if meta.get("outputs") != outputs:
            raise ValueError("Native artifact hash or size mismatch")
        print("Verified cached native binaries, license notices and pinned inputs.")
    else:
        MANIFEST.parent.mkdir(parents=True, exist_ok=True)
        record = {"contract": CONTRACT, "inputs": fingerprint, "outputs": outputs}
        temporary = MANIFEST.with_suffix(".partial")
        temporary.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        temporary.replace(MANIFEST)
        print("Wrote complete native CI cache evidence.")
        # Validate the just-written manifest without weakening the subsequent APK/source gates.
        main_verify(record, fingerprint, outputs)


def main_verify(record, fingerprint, outputs):
    if record["inputs"] != fingerprint or record["outputs"] != outputs:
        raise ValueError("Native cache verification mismatch")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print("Native CI cache rejected: " + str(error), file=sys.stderr)
        raise SystemExit(1)
