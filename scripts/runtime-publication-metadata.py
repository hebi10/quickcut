#!/usr/bin/env python3
"""Read pinned public Maven publication metadata; fetch only uncached coordinates."""
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.error
import urllib.request
import zipfile


def publication_base(component):
    parts = component.split(':')
    if len(parts) != 3 or any(
            not re.fullmatch(r'[A-Za-z0-9_.+\-]+', part) or part in {'.', '..'}
            for part in parts):
        raise ValueError('Invalid resolved Maven component: ' + component)
    group, artifact, version = parts
    repository = ('https://dl.google.com/dl/android/maven2/' if group.startswith('androidx.')
                  else 'https://repo.maven.apache.org/maven2/')
    return repository + group.replace('.', '/') + '/' + artifact + '/' + version + '/'


def publication_url(component, extension):
    if extension not in {'pom', 'module'}:
        raise ValueError('Unsupported publication extension: ' + extension)
    base = publication_base(component)
    _, artifact, version = component.split(':')
    return base + artifact + '-' + version + '.' + extension


def _digest(data):
    return hashlib.sha256(data).hexdigest()


def _filename(name):
    if not isinstance(name, str) or not name or Path(name).name != name or '\\' in name:
        raise ValueError('Invalid cache member filename: ' + str(name))
    return name


class PublicationMetadataCache:
    """Verify the complete committed archive before allowing any cache/network use.

    The manifest pins the archive, which pins its index and publication bytes.
    Explicit module 404 records come from the successful source-delivery run;
    missing/corrupt cache entries are never treated as evidence of absence.
    """

    def __init__(self, manifest_path):
        manifest_path = Path(manifest_path)
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        if manifest.get('schema_version') != 1:
            raise ValueError('Unsupported publication cache manifest schema')
        archive_record = manifest['archive']
        archive_path = manifest_path.parent / _filename(archive_record['file'])
        archive_bytes = archive_path.read_bytes()
        if _digest(archive_bytes) != archive_record['sha256']:
            raise ValueError('Publication cache archive SHA256 mismatch: ' + str(archive_path))
        self._sources = {}
        self._metadata = {}
        try:
            with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
                names = archive.namelist()
                if len(names) != len(set(names)):
                    raise ValueError('Duplicate publication cache archive member')
                index = json.loads(archive.read('index.json'))
                if index.get('schema_version') != 1:
                    raise ValueError('Unsupported publication cache index schema')
                expected_members = {'index.json'}
                for row in index['components']:
                    component = row['component']
                    publication_base(component)
                    if component in self._sources:
                        raise ValueError('Duplicate cached component: ' + component)
                    source_sha = row['source_sha256']
                    if not re.fullmatch(r'[0-9a-f]{64}', source_sha):
                        raise ValueError('Invalid cached source SHA256: ' + component)
                    self._sources[component] = source_sha
                    if set(row['metadata']) != {'pom', 'module'}:
                        raise ValueError('Cached component requires explicit pom and module records: ' + component)
                    for extension, record in row['metadata'].items():
                        url = publication_url(component, extension)
                        if record['url'] != url:
                            raise ValueError('Cached publication URL mismatch: ' + component + ' ' + extension)
                        key = (component, extension)
                        if record.get('status') == 404:
                            if extension != 'module' or set(record) != {'url', 'status'}:
                                raise ValueError('Invalid cached publication absence: ' + component)
                            self._metadata[key] = None
                            continue
                        if set(record) != {'url', 'file', 'sha256'}:
                            raise ValueError('Invalid cached publication record: ' + component)
                        filename = _filename(record['file'])
                        if filename in expected_members:
                            raise ValueError('Duplicate publication cache member: ' + filename)
                        expected_members.add(filename)
                        data = archive.read(filename)
                        if _digest(data) != record['sha256']:
                            raise ValueError('Cached publication SHA256 mismatch: ' + component + ' ' + extension)
                        self._metadata[key] = data
                if set(names) != expected_members:
                    raise ValueError('Unexpected publication cache archive members')
        except KeyError as error:
            raise ValueError('Missing publication cache record or member: ' + str(error)) from error
        except zipfile.BadZipFile as error:
            raise ValueError('Invalid publication cache archive: ' + str(archive_path)) from error

    def fetch(self, component, extension, *, source_sha256=None):
        url = publication_url(component, extension)
        key = (component, extension)
        if component in self._sources:
            if source_sha256 is not None and source_sha256 != self._sources[component]:
                raise ValueError('Current source JAR SHA256 differs from cached publication: ' + component)
            return self._metadata[key]
        try:
            # One request to the original official endpoint: no retries or alternate hosts.
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if extension == 'module' and error.code == 404:
                return None
            raise RuntimeError(
                f'Publication metadata fetch failed: {component} {extension} {url}: '
                f'HTTP {error.code} {error.reason} (response URL: {error.geturl()})') from error
        except (urllib.error.URLError, OSError) as error:
            reason = getattr(error, 'reason', str(error))
            raise RuntimeError(
                f'Publication metadata fetch failed: {component} {extension} {url}: {reason}') from error
