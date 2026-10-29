#!/usr/bin/env python3
"""Publication cache integrity and network-failure regression tests; no network access."""
import hashlib
import ast
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

SCRIPT = Path(__file__).with_name('runtime-publication-metadata.py')
COMPONENT = 'org.example:demo:1.0'
SOURCE_SHA = 'a' * 64
POM_URL = 'https://repo.maven.apache.org/maven2/org/example/demo/1.0/demo-1.0.pom'
MODULE_URL = 'https://repo.maven.apache.org/maven2/org/example/demo/1.0/demo-1.0.module'
POM = b'<project><name>demo</name></project>'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fixture(root, mutate=None):
    index = {'schema_version': 1, 'components': [{
        'component': COMPONENT, 'source_sha256': SOURCE_SHA,
        'metadata': {
            'pom': {'file': 'demo.pom', 'url': POM_URL, 'sha256': digest(POM)},
            'module': {'url': MODULE_URL, 'status': 404},
        },
    }]}
    files = {'demo.pom': POM}
    if mutate:
        mutate(index, files)
    archive = root / 'metadata.zip'
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
        bundle.writestr('index.json', json.dumps(index))
        for name, data in files.items():
            bundle.writestr(name, data)
    manifest = root / 'manifest.json'
    manifest.write_text(json.dumps({'schema_version': 1, 'archive': {
        'file': archive.name, 'sha256': digest(archive.read_bytes()),
    }}))
    return manifest


class PublicationMetadataTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), 'Publication cache helper is required')
        spec = importlib.util.spec_from_file_location('publication_metadata', SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.network = patch.object(self.module.urllib.request, 'urlopen',
                                    side_effect=AssertionError('Unexpected network request')).start()
        self.addCleanup(patch.stopall)

    def test_verified_cache_hit_returns_original_bytes_without_network(self):
        cache = self.module.PublicationMetadataCache(fixture(self.root))
        self.assertEqual(cache.fetch(COMPONENT, 'pom', source_sha256=SOURCE_SHA), POM)

    def test_verified_module_404_is_reused_without_network(self):
        cache = self.module.PublicationMetadataCache(fixture(self.root))
        self.assertIsNone(cache.fetch(COMPONENT, 'module', source_sha256=SOURCE_SHA))

    def test_current_source_mismatch_cannot_use_cache_or_network(self):
        cache = self.module.PublicationMetadataCache(fixture(self.root))
        for extension in ('pom', 'module'):
            with self.subTest(extension=extension), self.assertRaisesRegex(ValueError, 'source.*SHA256'):
                cache.fetch(COMPONENT, extension, source_sha256='b' * 64)

    def test_archive_digest_mismatch_fails_before_network(self):
        manifest = fixture(self.root)
        with (self.root / 'metadata.zip').open('ab') as output:
            output.write(b'tampered')
        with self.assertRaisesRegex(ValueError, 'archive.*SHA256'):
            self.module.PublicationMetadataCache(manifest)

    def test_publication_byte_mismatch_fails_before_network(self):
        manifest = fixture(self.root, lambda index, files: files.update({'demo.pom': b'tampered'}))
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            self.module.PublicationMetadataCache(manifest)

    def test_wrong_publication_url_fails_before_network(self):
        def mutate(index, files):
            index['components'][0]['metadata']['pom']['url'] = POM_URL.replace('/1.0/', '/2.0/')
        with self.assertRaisesRegex(ValueError, 'URL'):
            self.module.PublicationMetadataCache(fixture(self.root, mutate))

    def test_missing_member_is_corruption_not_a_network_miss(self):
        with self.assertRaisesRegex(ValueError, 'member'):
            self.module.PublicationMetadataCache(fixture(self.root, lambda index, files: files.clear()))

    def test_incomplete_component_cannot_infer_module_absence(self):
        def mutate(index, files):
            del index['components'][0]['metadata']['module']
        with self.assertRaisesRegex(ValueError, 'pom.*module'):
            self.module.PublicationMetadataCache(fixture(self.root, mutate))

    def test_duplicate_component_cannot_override_a_pin(self):
        def mutate(index, files):
            index['components'].append(index['components'][0])
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.module.PublicationMetadataCache(fixture(self.root, mutate))

    def test_pom_cannot_be_marked_absent(self):
        def mutate(index, files):
            index['components'][0]['metadata']['pom'] = {'url': POM_URL, 'status': 404}
        with self.assertRaisesRegex(ValueError, 'absence'):
            self.module.PublicationMetadataCache(fixture(self.root, mutate))

    def test_genuine_miss_requests_exact_new_coordinate_once(self):
        cache = self.module.PublicationMetadataCache(fixture(self.root))
        self.network.side_effect = None
        self.network.return_value = io.BytesIO(b'new publication')
        self.assertEqual(cache.fetch('org.example:demo:2.0', 'pom'), b'new publication')
        self.network.assert_called_once_with(
            'https://repo.maven.apache.org/maven2/org/example/demo/2.0/demo-2.0.pom', timeout=60)

    def test_miss_403_reports_component_extension_url_status_without_retry(self):
        cache = self.module.PublicationMetadataCache(fixture(self.root))
        url = 'https://repo.maven.apache.org/maven2/org/example/demo/2.0/demo-2.0.pom'
        self.network.side_effect = urllib.error.HTTPError(url, 403, 'Forbidden', {}, None)
        with self.assertRaises(RuntimeError) as failure:
            cache.fetch('org.example:demo:2.0', 'pom')
        for detail in ('org.example:demo:2.0', 'pom', url, '403'):
            self.assertIn(detail, str(failure.exception))
        self.network.assert_called_once_with(url, timeout=60)

    def test_only_module_404_can_be_absent_on_network_miss(self):
        cache = self.module.PublicationMetadataCache(fixture(self.root))
        self.network.side_effect = urllib.error.HTTPError('https://example.invalid', 404, 'Not Found', {}, None)
        self.assertIsNone(cache.fetch('org.example:demo:2.0', 'module'))
        with self.assertRaisesRegex(RuntimeError, 'HTTP 404'):
            cache.fetch('org.example:demo:2.0', 'pom')

    def test_network_timeout_reports_exact_component_and_url(self):
        cache = self.module.PublicationMetadataCache(fixture(self.root))
        self.network.side_effect = TimeoutError('timed out')
        with self.assertRaises(Exception) as failure:
            cache.fetch('org.example:demo:2.0', 'module')
        self.assertIsInstance(failure.exception, RuntimeError)
        for detail in ('org.example:demo:2.0', 'module',
                       'https://repo.maven.apache.org/maven2/org/example/demo/2.0/demo-2.0.module', 'timed out'):
            self.assertIn(detail, str(failure.exception))
        self.assertEqual(self.network.call_count, 1)

    def test_google_coordinate_uses_original_official_endpoint(self):
        cache = self.module.PublicationMetadataCache(fixture(self.root))
        self.network.side_effect = None
        self.network.return_value = io.BytesIO(b'androidx publication')
        self.assertEqual(cache.fetch('androidx.test:example:3.0', 'pom'), b'androidx publication')
        self.network.assert_called_once_with(
            'https://dl.google.com/dl/android/maven2/androidx/test/example/3.0/example-3.0.pom', timeout=60)

    def test_committed_cache_serves_every_record_without_network(self):
        manifest = SCRIPT.parent.parent / 'third_party/runtime-publication-metadata/manifest.json'
        self.assertTrue(manifest.is_file(), 'Pinned publication cache is required')
        cache = self.module.PublicationMetadataCache(manifest)
        with zipfile.ZipFile(manifest.parent / 'publication-metadata.zip') as bundle:
            index = json.loads(bundle.read('index.json'))
        positives = absent = 0
        for row in index['components']:
            for extension, metadata in row['metadata'].items():
                result = cache.fetch(row['component'], extension, source_sha256=row['source_sha256'])
                if metadata.get('status') == 404:
                    self.assertIsNone(result)
                    absent += 1
                else:
                    self.assertEqual(digest(result), metadata['sha256'])
                    positives += 1
        self.assertEqual((len(index['components']), positives, absent), (96, 176, 16))

    def test_packager_collects_verified_bytes_and_keeps_publication_identity(self):
        # Execute the actual packager's collection function against a tiny source
        # tree; building native/Android binaries is unrelated to this boundary.
        script = SCRIPT.with_name('package-smartcut-source.sh').read_text()
        embedded = script.split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]
        tree = ast.parse(embedded)
        collect = next(node for node in ast.walk(tree)
                       if isinstance(node, ast.FunctionDef) and node.name == 'collect_metadata')
        source = self.root / 'demo-sources.jar'
        source.write_bytes(b'current source jar')
        def mutate(index, files):
            index['components'][0]['source_sha256'] = digest(source.read_bytes())
        cache = self.module.PublicationMetadataCache(fixture(self.root, mutate))
        metadata_dir = self.root / 'maven-metadata'
        metadata_dir.mkdir()
        import xml.etree.ElementTree as ET
        namespace = {'publication_metadata': self.module, 'metadata_cache': cache,
                     'dependency_dir': self.root, 'metadata_dir': metadata_dir,
                     'sha256': lambda path: digest(path.read_bytes()), 'ET': ET, 'json': json}
        exec(compile(ast.Module(body=[collect], type_ignores=[]), str(SCRIPT), 'exec'), namespace)
        row = namespace['collect_metadata']({'component': COMPONENT, 'source': source.name,
                                            'sourceArtifact': source.name})
        self.assertEqual(row['component'], COMPONENT)
        self.assertEqual(row['sourceArtifactUrl'],
                         'https://repo.maven.apache.org/maven2/org/example/demo/1.0/demo-sources.jar')
        self.assertEqual(row['metadata'], {'pom': {
            'file': 'maven-metadata/org.example__demo-1.0.pom', 'url': POM_URL, 'sha256': digest(POM)}})
        self.assertEqual((metadata_dir / 'org.example__demo-1.0.pom').read_bytes(), POM)


if __name__ == '__main__':
    unittest.main()
