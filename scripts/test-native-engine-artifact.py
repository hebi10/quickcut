#!/usr/bin/env python3
"""Exercise native bundle validation against disposable real Git repositories."""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

SCRIPT = Path(__file__).with_name('native-engine-artifact.py')
PAYLOADS = (
    'app/build/generated/nativeLibs/arm64-v8a/libquickcut_smartcut.so',
    'app/build/generated/nativeLibs/x86_64/libquickcut_smartcut.so',
    'build/generated/smartcutAssets/licenses/NATIVE-DEPENDENCIES.txt',
)


def elf(machine, alignment=16384):
    header = bytearray(64)
    header[:7] = b'\x7fELF\x02\x01\x01'
    struct.pack_into('<HHIQQQIHHHHHH', header, 16,
                     3, machine, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
    return bytes(header) + struct.pack('<IIQQQQQQ', 1, 5, 0, 0, 0, 120, 120, alignment)


class NativeArtifactTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='quickcut-artifact-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.write('native/engine.rs', b'pub fn answer() -> u32 { 42 }\n')
        self.write('scripts/build-native-smartcut.sh', b'#!/bin/sh\n# fixture builder\n')
        self.write('scripts/collect-native-licenses.py', b'# fixture notices collector\n')
        self.write('scripts/native-smartcut-sources.json', b'{"toolchains":{"ndk":"test","rust":"test","androidApi":29}}\n')
        self.write('app/src/main/java/Main.kt', b'// UI fixture\n')
        self.write('.gitignore', b'.gradle/\n**/build/\n**/target/\n')
        self.git('init', '--quiet')
        self.git('config', 'core.autocrlf', 'false')
        self.git('add', '.')
        self.git('-c', 'user.name=Native artifact test', '-c', 'user.email=test@example.invalid',
                 'commit', '--quiet', '-m', 'fixture')
        self.commit = self.git('rev-parse', 'HEAD').strip()
        self.original = {PAYLOADS[0]: elf(183), PAYLOADS[1]: elf(62),
                         PAYLOADS[2]: b'Native dependency license fixture\n'}
        for name, data in self.original.items():
            self.write(name, data)
        self.bundle = self.root / 'bundle.zip'

    def write(self, relative, data):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def git(self, *args):
        result = subprocess.run(['git', *args], cwd=self.root, capture_output=True, check=True)
        return result.stdout.decode('utf-8').strip()

    def run_cli(self, *args, success=True):
        result = subprocess.run([sys.executable, str(SCRIPT), *args, '--repo', str(self.root)],
                                capture_output=True, text=True, encoding='utf-8')
        if success:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result

    def pack(self):
        self.run_cli('pack', '--output', str(self.bundle), '--commit', self.commit)

    def mutate_bundle(self, mutation):
        with zipfile.ZipFile(self.bundle) as source:
            entries = [(info, source.read(info)) for info in source.infolist()]
        entries = mutation(entries)
        with zipfile.ZipFile(self.bundle, 'w', compression=zipfile.ZIP_DEFLATED) as target:
            for info, data in entries:
                with warnings.catch_warnings():
                    warnings.filterwarnings('ignore', message="Duplicate name: 'manifest.json'", category=UserWarning)
                    target.writestr(info, data)

    def replace_payload_and_digest(self, name, payload):
        def mutation(entries):
            updated = []
            for info, data in entries:
                if info.filename == name:
                    data = payload
                elif info.filename == 'manifest.json':
                    manifest = json.loads(data)
                    record = next(item for item in manifest['files'] if item['path'] == name)
                    record.update(size=len(payload), sha256=hashlib.sha256(payload).hexdigest())
                    data = json.dumps(manifest).encode()
                updated.append((info, data))
            return updated
        self.mutate_bundle(mutation)

    def assert_payloads(self, expected=None):
        for name, data in (expected or self.original).items():
            self.assertEqual((self.root / name).read_bytes(), data, name)

    def test_fingerprint_matches_commit_and_ignores_kotlin_ui_edits(self):
        before = self.run_cli('fingerprint').stdout.strip()
        self.assertRegex(before, r'^[0-9a-f]{64}$')
        self.assertEqual(before, self.run_cli('fingerprint', '--commit', self.commit).stdout.strip())
        self.write('app/src/main/java/Main.kt', b'// revised UI\n')
        self.assertEqual(before, self.run_cli('fingerprint').stdout.strip())

    def test_native_edits_additions_and_deletions_change_the_fingerprint(self):
        original = self.run_cli('fingerprint').stdout.strip()
        self.write('native/engine.rs', b'pub fn answer() -> u32 { 43 }\n')
        self.assertNotEqual(original, self.run_cli('fingerprint').stdout.strip())
        self.write('native/engine.rs', b'pub fn answer() -> u32 { 42 }\n')
        self.write('native/additional.rs', b'// a new module\n')
        self.assertNotEqual(original, self.run_cli('fingerprint').stdout.strip())
        (self.root / 'native/additional.rs').unlink()
        (self.root / 'native/engine.rs').unlink()
        self.assertNotEqual(original, self.run_cli('fingerprint').stdout.strip())

    def test_text_crlf_is_equivalent_but_binary_nul_bytes_are_preserved(self):
        original = self.run_cli('fingerprint').stdout.strip()
        self.write('native/engine.rs', b'pub fn answer() -> u32 { 42 }\r\n')
        self.assertEqual(original, self.run_cli('fingerprint').stdout.strip())
        self.write('native/blob.bin', b'\x00first\r\nsecond')
        binary = self.run_cli('fingerprint').stdout.strip()
        self.write('native/blob.bin', b'\x00first\nsecond')
        self.assertNotEqual(binary, self.run_cli('fingerprint').stdout.strip())

    def test_license_edits_additions_and_deletions_invalidate_the_cached_engine(self):
        license_path = 'third_party/licenses/engine-license.txt'
        original_license = b'Original engine license\n'
        self.write(license_path, original_license)
        self.git('add', license_path)
        self.git('-c', 'user.name=Native artifact test', '-c', 'user.email=test@example.invalid',
                 'commit', '--quiet', '-m', 'license fixture')
        self.commit = self.git('rev-parse', 'HEAD')
        original = self.run_cli('fingerprint').stdout.strip()
        self.pack()
        self.run_cli('install', '--bundle', str(self.bundle))

        self.write(license_path, b'Updated engine license\n')
        self.assertNotEqual(original, self.run_cli('fingerprint').stdout.strip())
        self.run_cli('install', '--bundle', str(self.bundle), success=False)
        self.run_cli('prepare', success=False)
        self.assertFalse(json.loads(self.run_cli('status', '--json').stdout)['ready'])
        self.assert_payloads()

        self.write(license_path, original_license)
        extra_license = self.root / 'third_party/licenses/additional-license.txt'
        self.write('third_party/licenses/additional-license.txt', b'Additional native license\n')
        self.assertNotEqual(original, self.run_cli('fingerprint').stdout.strip())
        extra_license.unlink()
        (self.root / license_path).unlink()
        self.assertNotEqual(original, self.run_cli('fingerprint').stdout.strip())
        self.run_cli('prepare', success=False)
        self.assert_payloads()

    def test_pack_install_and_prepare_restore_outputs_after_clean(self):
        self.pack()
        with zipfile.ZipFile(self.bundle) as archive:
            manifest = json.loads(archive.read('manifest.json'))
            self.assertEqual(manifest['source_commit'], self.commit)
            self.assertEqual(set(archive.namelist()), {'manifest.json', *PAYLOADS})
        for name in PAYLOADS:
            (self.root / name).unlink()
        self.run_cli('install', '--bundle', str(self.bundle), '--expected-commit', self.commit)
        self.assert_payloads()
        for name in PAYLOADS:
            (self.root / name).unlink()
        restored = json.loads(self.run_cli('prepare').stdout)
        self.assertTrue(restored['outputs_restored'])
        self.assert_payloads()
        self.assertFalse(json.loads(self.run_cli('prepare').stdout)['outputs_restored'])
        status = json.loads(self.run_cli('status', '--json').stdout)
        self.assertTrue(status['ready'])

    def test_pack_rejects_a_commit_with_different_native_sources(self):
        self.write('native/engine.rs', b'// dirty engine\n')
        self.run_cli('pack', '--output', str(self.bundle), '--commit', self.commit, success=False)
        self.assertFalse(self.bundle.exists())
        self.assert_payloads()

    def test_pack_accepts_ui_only_changes_against_older_native_equivalent_commit(self):
        self.write('app/src/main/java/Main.kt', b'// local UI development\n')
        self.pack()
        self.assertTrue(self.bundle.is_file())

    def test_install_and_prepare_reject_changed_sources_without_replacing_existing_engine(self):
        self.pack()
        self.run_cli('install', '--bundle', str(self.bundle))
        self.write('native/engine.rs', b'// changed after build\n')
        self.run_cli('install', '--bundle', str(self.bundle), success=False)
        self.run_cli('prepare', success=False)
        self.assert_payloads()
        self.assertFalse(json.loads(self.run_cli('status', '--json').stdout)['ready'])

    def test_install_rejects_different_expected_commit(self):
        self.pack()
        self.run_cli('install', '--bundle', str(self.bundle), '--expected-commit', '1' * 40,
                     success=False)
        self.assert_payloads()

    def test_install_rejects_corrupt_payload_and_preserves_previous_outputs(self):
        self.pack()
        self.mutate_bundle(lambda entries: [(info, data + b'tampered' if info.filename == PAYLOADS[0] else data)
                                            for info, data in entries])
        self.run_cli('install', '--bundle', str(self.bundle), success=False)
        self.assert_payloads()

    def test_install_rejects_wrong_abi_even_with_a_matching_digest(self):
        self.pack()
        self.replace_payload_and_digest(PAYLOADS[1], elf(183))
        self.run_cli('install', '--bundle', str(self.bundle), success=False)
        self.assert_payloads()

    def test_install_rejects_small_load_alignment_even_with_a_matching_digest(self):
        self.pack()
        self.replace_payload_and_digest(PAYLOADS[0], elf(183, 4096))
        self.run_cli('install', '--bundle', str(self.bundle), success=False)
        self.assert_payloads()

    def test_install_rejects_unsafe_unexpected_duplicate_and_symlink_entries(self):
        self.pack()
        original = self.bundle.read_bytes()
        unsafe = ('../escape.txt', '/absolute.txt', 'C:/drive.txt', 'back\\slash.txt', 'unexpected.txt')
        for name in unsafe:
            with self.subTest(name=name):
                self.bundle.write_bytes(original)
                self.mutate_bundle(lambda entries: entries + [(zipfile.ZipInfo(name), b'unsafe')])
                self.run_cli('install', '--bundle', str(self.bundle), success=False)
                self.assert_payloads()
        self.bundle.write_bytes(original)
        self.mutate_bundle(lambda entries: entries + [next(item for item in entries if item[0].filename == 'manifest.json')])
        self.run_cli('install', '--bundle', str(self.bundle), success=False)
        self.assert_payloads()
        self.bundle.write_bytes(original)
        def symlink(entries):
            info, _ = entries[1]
            info.create_system = 3
            info.external_attr = 0o120777 << 16
            return [(info, data) if item.filename == info.filename else (item, data)
                    for item, data in entries]
        self.mutate_bundle(symlink)
        self.run_cli('install', '--bundle', str(self.bundle), success=False)
        self.assert_payloads()
        self.assertFalse((self.root.parent / 'escape.txt').exists())

    def test_corrupt_cached_bundle_is_not_restored(self):
        self.pack()
        self.run_cli('install', '--bundle', str(self.bundle))
        cached = list((self.root / '.gradle/native-engine').rglob('*.zip'))
        self.assertEqual(len(cached), 1)
        cached[0].write_bytes(b'corrupt cached archive')
        self.run_cli('prepare', success=False)
        self.assert_payloads()

    def test_no_receipt_reports_not_ready_and_does_not_accept_bare_old_libraries(self):
        status = json.loads(self.run_cli('status', '--json').stdout)
        self.assertFalse(status['ready'])
        self.run_cli('prepare', success=False)
        self.assert_payloads()

    def test_partial_install_rolls_back_files_when_a_later_write_fails(self):
        spec = importlib.util.spec_from_file_location('native_engine_artifact', SCRIPT)
        artifact = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(artifact)
        receipt = self.root / '.gradle/native-engine/active.json'
        self.write('.gradle/native-engine/active.json', b'previous verified receipt')
        real_write = artifact.atomic_write
        attempts = []

        def fail_second_output(path, data):
            attempts.append(path)
            if path == self.root / PAYLOADS[1]:
                raise PermissionError('fixture write failure')
            real_write(path, data)

        updated = {name: data + b'new engine' for name, data in self.original.items()}
        with mock.patch.object(artifact, 'atomic_write', side_effect=fail_second_output):
            with self.assertRaises(PermissionError):
                artifact.apply_payloads(self.root, updated, receipt, b'new receipt')
        self.assertGreaterEqual(len(attempts), 3)  # First write, failed write, then restoration.
        self.assert_payloads()
        self.assertEqual(receipt.read_bytes(), b'previous verified receipt')

    def test_partial_install_rolls_back_if_a_destination_becomes_unsafe(self):
        spec = importlib.util.spec_from_file_location('native_engine_artifact', SCRIPT)
        artifact = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(artifact)
        receipt = self.root / '.gradle/native-engine/active.json'
        self.write('.gradle/native-engine/active.json', b'previous verified receipt')
        real_path = artifact.safe_repo_path

        def reject_second_output(repo, relative):
            if relative == PAYLOADS[1]:
                raise artifact.ArtifactError('fixture destination changed after validation')
            return real_path(repo, relative)

        updated = {name: data + b'new engine' for name, data in self.original.items()}
        with mock.patch.object(artifact, 'safe_repo_path', side_effect=reject_second_output):
            with self.assertRaises(artifact.ArtifactError):
                artifact.apply_payloads(self.root, updated, receipt, b'new receipt')
        self.assert_payloads()
        self.assertEqual(receipt.read_bytes(), b'previous verified receipt')


if __name__ == '__main__':
    unittest.main()
