#!/usr/bin/env python3
"""Exercise the real native builder with disposable offline tool/source fixtures."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

BUILDER = Path(__file__).with_name('build-native-smartcut.sh')
PACKAGES = ('x264', 'x265', 'libavutil', 'libavcodec', 'libavformat', 'libavdevice',
            'libavfilter', 'libswresample', 'libswscale')
HEADERS = ('x264.h', 'x265.h', 'x265_config.h', 'libavutil/avutil.h', 'libavcodec/avcodec.h',
           'libavformat/avformat.h', 'libavdevice/avdevice.h', 'libavfilter/avfilter.h',
           'libswresample/swresample.h', 'libswscale/swscale.h')


def executable(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('#!/usr/bin/env bash\nset -eu\n' + text)
    path.chmod(0o755)


class NativeBuildCacheTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='quickcut-native-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'scripts').mkdir()
        self.script = self.root / 'scripts/build-native-smartcut.sh'
        self.script.write_bytes(BUILDER.read_bytes())
        crate = self.root / 'native/quickcut-smartcut'
        crate.mkdir(parents=True)
        (crate / 'Cargo.toml').write_text('fixture')
        (crate / 'Cargo.lock').write_text('fixture')
        self.cache = self.root / 'cache'
        downloads = self.cache / 'downloads'
        downloads.mkdir(parents=True)
        self.prefix = self.cache / 'prefix/host'
        ndk = self.root / 'ndk'
        ndk.mkdir()
        (ndk / 'source.properties').write_text('Pkg.Revision = test-ndk\n')
        executable(ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/clang', 'exit 0\n')
        manifest = {'toolchains': {'ndk': 'test-ndk', 'rust': 'test-rust', 'androidApi': 29}}
        configure = ('printf "configure\\n" >> "$FIXTURE_EVENTS"\n'
                     'if [[ "$FIXTURE_FAILURE" == configure ]]; then echo "fixture configure failure" >&2; exit 37; fi\n'
                     'for item in "$@"; do case "$item" in --prefix=*) printf "%s" "${item#--prefix=}" > .fixture-prefix;; esac; done\n')
        for package, directory, archive in (
            ('x264', 'x264-b35605ace3ddf7c1a5d67a2eb553f034aef41d55', 'x264.tar.gz'),
            ('x265', 'x265_4.1', 'x265.tar.gz'),
            ('ffmpeg', 'ffmpeg-7.1.5', 'ffmpeg.tar.gz'),
        ):
            path = downloads / archive
            with tarfile.open(path, 'w:gz') as tar:
                for relative, contents in [('configure', '#!/usr/bin/env bash\nset -eu\n' + configure),
                                           ('source/fixture', 'fixture')]:
                    blob = contents.encode()
                    member = tarfile.TarInfo(directory + '/' + relative)
                    member.size = len(blob)
                    member.mode = 0o755 if relative == 'configure' else 0o644
                    tar.addfile(member, io.BytesIO(blob))
            manifest[package] = {'archive': archive, 'directory': directory,
                                 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                                 'url': 'https://invalid.example/never-requested'}
        self.manifest = self.root / 'scripts/native-smartcut-sources.json'
        self.manifest.write_text(json.dumps(manifest))
        self.events = self.root / 'events'
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        for name in ('rustup', 'nasm'):
            executable(self.bin / name, 'exit 0\n')
        executable(self.bin / 'pkg-config', '[[ "${FIXTURE_PKG_FAIL:-false}" != true ]] || exit 44\nfor item in "$@"; do case "$item" in --*) continue;; esac; [[ -s "$PKG_CONFIG_LIBDIR/$item.pc" ]] || exit 44; done\n')
        executable(self.bin / 'cargo', 'printf "cargo\\n" >> "$FIXTURE_EVENTS"; exit 73\n')
        executable(self.bin / 'make', 'printf "make\\n" >> "$FIXTURE_EVENTS"\n'
                   'if [[ "$FIXTURE_FAILURE" == make ]]; then echo "fixture make failure" >&2; exit 38; fi\n')
        executable(self.bin / 'cmake', 'printf "cmake\\n" >> "$FIXTURE_EVENTS"\n'
                   'if [[ "${1:-}" == -S ]]; then while [[ $# -gt 0 ]]; do if [[ "$1" == -B ]]; then shift; mkdir -p "$1"; printf archive > "$1/libx265.a"; break; fi; shift; done; fi\n')
        executable(self.bin / 'ar', 'while IFS= read -r line; do case "$line" in "CREATE "*) printf archive > "${line#CREATE }";; esac; done\n')
        self.env = os.environ.copy()
        self.env.update(PATH=str(self.bin) + os.pathsep + self.env['PATH'],
                        ANDROID_NDK_HOME=str(ndk), QUICKCUT_NATIVE_CACHE=str(self.cache),
                        FIXTURE_EVENTS=str(self.events), FIXTURE_FAILURE='configure')

    def populate_cache(self):
        key = hashlib.sha256(self.script.read_bytes() + self.manifest.read_bytes()).hexdigest()
        (self.prefix / 'lib/pkgconfig').mkdir(parents=True, exist_ok=True)
        for package in PACKAGES:
            library = package if package.startswith('lib') else 'lib' + package
            (self.prefix / 'lib' / (library + '.a')).write_text('archive')
            (self.prefix / 'lib/pkgconfig' / (package + '.pc')).write_text(
                'Name: ' + package + '\nDescription: offline fixture\nVersion: 1.0\nLibs: -lfixture\n')
        for header in HEADERS:
            path = self.prefix / 'include' / header
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('header')
        (self.prefix / '.build-key').write_text(key + '\n')

    def run_builder(self):
        return subprocess.run(['bash', str(self.script), '--host-tests'], env=self.env,
                              capture_output=True, text=True, timeout=20)

    def event_names(self):
        return self.events.read_text().splitlines() if self.events.exists() else []

    def test_configure_failure_stops_first_and_never_writes_success_marker(self):
        result = self.run_builder()
        self.assertEqual(result.returncode, 37, result.stderr)
        self.assertIn('fixture configure failure', result.stderr)
        self.assertEqual(self.event_names(), ['configure'])
        self.assertFalse((self.prefix / '.build-key').exists())

    def test_android_dependency_failure_also_preserves_first_status(self):
        result = subprocess.run(['bash', str(self.script)], env=self.env,
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 37, result.stderr)
        self.assertIn('fixture configure failure', result.stderr)
        self.assertEqual(self.event_names(), ['configure'])
        self.assertFalse((self.cache / 'prefix/arm64-v8a/.build-key').exists())

    def test_make_failure_preserves_status_and_diagnostic_tail(self):
        self.env['FIXTURE_FAILURE'] = 'make'
        result = self.run_builder()
        self.assertEqual(result.returncode, 38, result.stderr)
        self.assertIn('fixture make failure', result.stderr)
        self.assertEqual(self.event_names(), ['configure', 'make'])
        self.assertFalse((self.prefix / '.build-key').exists())

    def test_matching_marker_with_missing_pkgconfig_rebuilds_instead_of_hitting(self):
        self.populate_cache()
        (self.prefix / 'lib/pkgconfig/libavutil.pc').unlink()
        result = self.run_builder()
        self.assertEqual(result.returncode, 37, result.stderr)
        self.assertEqual(self.event_names(), ['configure'])
        self.assertFalse((self.prefix / '.build-key').exists())

    def test_matching_marker_with_missing_library_or_header_rebuilds(self):
        for relative in ('lib/libavutil.a', 'include/libavutil/avutil.h'):
            with self.subTest(relative=relative):
                self.populate_cache()
                self.events.unlink(missing_ok=True)
                (self.prefix / relative).unlink()
                result = self.run_builder()
                self.assertEqual(result.returncode, 37, result.stderr)
                self.assertEqual(self.event_names(), ['configure'])
                self.assertFalse((self.prefix / '.build-key').exists())

    def test_matching_marker_with_unresolvable_pkgconfig_rebuilds(self):
        self.populate_cache()
        self.env['FIXTURE_PKG_FAIL'] = 'true'
        result = self.run_builder()
        self.assertEqual(result.returncode, 37, result.stderr)
        self.assertEqual(self.event_names(), ['configure'])
        self.assertFalse((self.prefix / '.build-key').exists())

    def test_complete_matching_cache_skips_build_and_reaches_cargo(self):
        self.populate_cache()
        result = self.run_builder()
        self.assertEqual(result.returncode, 73, result.stderr)
        self.assertEqual(self.event_names(), ['cargo'])
        self.assertTrue((self.prefix / '.build-key').exists())

    def test_successful_commands_with_incomplete_install_never_write_marker(self):
        self.env['FIXTURE_FAILURE'] = 'none'
        result = self.run_builder()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Incomplete native dependency prefix', result.stderr)
        self.assertNotIn('cargo', self.event_names())
        self.assertFalse((self.prefix / '.build-key').exists())


if __name__ == '__main__':
    unittest.main()
