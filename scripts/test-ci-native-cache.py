#!/usr/bin/env python3
"""Offline verification that CI cannot reuse missing, mutated or untrusted native binaries."""
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("ci-native-cache.py")


def elf(machine, alignment=16384):
    data = bytearray(128)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 1, 1, alignment)
    return data


class NativeCacheEvidenceTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        (self.root / "scripts").mkdir()
        (self.root / "native/quickcut-smartcut/src").mkdir(parents=True)
        (self.root / "scripts/ci-native-cache.py").write_bytes(SCRIPT.read_bytes())
        (self.root / "scripts/build-native-smartcut.sh").write_text("native-v1")
        (self.root / "scripts/native-smartcut-sources.json").write_text("{}")
        (self.root / "native/quickcut-smartcut/src/lib.rs").write_text("pub fn fixture() {}")
        for abi, machine in (("arm64-v8a", 183), ("x86_64", 62)):
            path = self.root / f"app/build/generated/nativeLibs/{abi}/libquickcut_smartcut.so"
            path.parent.mkdir(parents=True)
            path.write_bytes(elf(machine))
        notice = self.root / "build/generated/smartcutAssets/licenses/NATIVE-DEPENDENCIES.txt"
        notice.parent.mkdir(parents=True)
        notice.write_text("License notices for pinned sources")

    def command(self, mode):
        return subprocess.run([sys.executable, str(self.root / "scripts/ci-native-cache.py"), mode],
                              cwd=self.root, capture_output=True, text=True, timeout=5)

    def test_complete_artifacts_can_be_verified(self):
        self.assertEqual(self.command("write").returncode, 0)
        self.assertEqual(self.command("verify").returncode, 0)

    def test_missing_or_modified_binary_fails_closed(self):
        self.assertEqual(self.command("write").returncode, 0)
        output = self.root / "app/build/generated/nativeLibs/x86_64/libquickcut_smartcut.so"
        output.write_bytes(elf(62) + b"untrusted")
        self.assertNotEqual(self.command("verify").returncode, 0)
        output.unlink()
        self.assertNotEqual(self.command("verify").returncode, 0)

    def test_native_source_change_forces_rebuild(self):
        self.assertEqual(self.command("write").returncode, 0)
        (self.root / "native/quickcut-smartcut/src/lib.rs").write_text("pub fn changed() {}")
        self.assertNotEqual(self.command("verify").returncode, 0)

    def test_wrong_architecture_or_alignment_cannot_pass(self):
        arm = self.root / "app/build/generated/nativeLibs/arm64-v8a/libquickcut_smartcut.so"
        arm.write_bytes(elf(62))
        self.assertNotEqual(self.command("write").returncode, 0)
        arm.write_bytes(elf(183, alignment=4096))
        self.assertNotEqual(self.command("write").returncode, 0)

    def test_missing_notices_and_manifest_cannot_pass(self):
        self.assertNotEqual(self.command("verify").returncode, 0)
        notice = self.root / "build/generated/smartcutAssets/licenses/NATIVE-DEPENDENCIES.txt"
        notice.unlink()
        self.assertNotEqual(self.command("write").returncode, 0)


if __name__ == "__main__":
    unittest.main()
