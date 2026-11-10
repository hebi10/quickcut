#!/usr/bin/env python3
"""Bind Android engine bundles to their native inputs; no Android/Linux tools needed.

Fingerprint v1 hashes Git-discovered native inputs as Git blobs after normalizing
UTF-8, non-NUL text from CRLF to LF. The same normalization is applied to commit
blobs and the working tree, independently of a developer's core.autocrlf setting.
Binary/NUL data is preserved. Kotlin/UI files are outside the fingerprint scope.
"""
import argparse
from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import subprocess
import sys
import time
import uuid
import zipfile

FORMAT = 'quickcut-native-engine-v1'
FINGERPRINT_ALGORITHM = 'git-blob-lf-v1'
RECEIPT_FORMAT = 'quickcut-native-engine-install-v1'
SOURCE_SCRIPTS = (
    'scripts/build-native-smartcut.sh',
    'scripts/native-smartcut-sources.json',
    'scripts/collect-native-licenses.py',
)
SOURCE_SCOPES = ('native', 'third_party/licenses', *SOURCE_SCRIPTS)
LIBRARIES = {
    'app/build/generated/nativeLibs/arm64-v8a/libquickcut_smartcut.so': 183,
    'app/build/generated/nativeLibs/x86_64/libquickcut_smartcut.so': 62,
}
NOTICES = 'build/generated/smartcutAssets/licenses/NATIVE-DEPENDENCIES.txt'
PAYLOADS = (*LIBRARIES, NOTICES)
MAX_LIBRARY_BYTES = 128 * 1024 * 1024
MAX_NOTICES_BYTES = 4 * 1024 * 1024
MAX_MANIFEST_BYTES = 2 * 1024 * 1024
COMMIT_PATTERN = re.compile(r'(?:[0-9a-f]{40}|[0-9a-f]{64})\Z')
SHA256_PATTERN = re.compile(r'[0-9a-f]{64}\Z')


class ArtifactError(Exception):
    """A validation failure whose message is safe to show in build logs."""


def git(repo, *args, input=None):
    result = subprocess.run(['git', *args], cwd=repo, input=input, capture_output=True)
    if result.returncode:
        raise ArtifactError('Git could not inspect native inputs: ' +
                            result.stderr.decode('utf-8', errors='replace').strip())
    return result.stdout


def repository(path):
    root = Path(path).resolve()
    top = Path(git(root, 'rev-parse', '--show-toplevel').decode().strip()).resolve()
    if top != root:
        raise ArtifactError('--repo must name the repository root')
    return root


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def canonical_json(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('utf-8')


def canonical_blob(data, object_format):
    if b'\0' not in data:
        try:
            data.decode('utf-8')
        except UnicodeDecodeError:
            pass
        else:
            data = data.replace(b'\r\n', b'\n')
    return hashlib.new(object_format, b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()


def source_path(name):
    parts = PurePosixPath(name).parts
    if (not name or name.startswith('/') or '\\' in name or ':' in name or
            any(ord(char) < 32 for char in name) or any(part in ('', '.', '..') for part in parts)):
        raise ArtifactError('Unsafe native input path')
    if name not in SOURCE_SCRIPTS and not name.startswith(('native/', 'third_party/licenses/')):
        raise ArtifactError('Input is outside the native source scope')
    return name


def safe_repo_path(repo, relative):
    target = repo / relative
    try:
        target.resolve().relative_to(repo)
    except ValueError as error:
        raise ArtifactError('Generated path leaves the repository') from error
    cursor = target
    while cursor != repo:
        if cursor.is_symlink() or (hasattr(cursor, 'is_junction') and cursor.is_junction()):
            raise ArtifactError('Symbolic links are not allowed in engine input/output paths')
        cursor = cursor.parent
    return target


def resolve_commit(repo, value):
    if not COMMIT_PATTERN.fullmatch(value):
        raise ArtifactError('A full hexadecimal source commit SHA is required')
    resolved = git(repo, 'rev-parse', '--verify', value + '^{commit}').decode().strip()
    if not COMMIT_PATTERN.fullmatch(resolved):
        raise ArtifactError('Invalid resolved source commit')
    return resolved


def fingerprint(repo, commit=None):
    object_format = git(repo, 'rev-parse', '--show-object-format').decode().strip()
    if object_format not in ('sha1', 'sha256'):
        raise ArtifactError('Unsupported Git object format')
    records = []
    if commit:
        commit = resolve_commit(repo, commit)
        tree = git(repo, 'ls-tree', '-r', '-z', '--full-tree', commit, '--', *SOURCE_SCOPES)
        entries = []
        for record in tree.split(b'\0'):
            if not record:
                continue
            metadata, raw_name = record.split(b'\t', 1)
            mode, kind, oid = metadata.split()
            name = source_path(raw_name.decode('utf-8'))
            if kind != b'blob' or mode not in (b'100644', b'100755'):
                raise ArtifactError('Only regular files are allowed in native source inputs')
            entries.append((name, oid))
        # One Git process reads all blobs; no per-source process cost on Windows.
        request = b''.join(oid + b'\n' for _, oid in entries)
        response = io.BytesIO(git(repo, 'cat-file', '--batch', input=request))
        for name, _ in entries:
            header = response.readline().split()
            if len(header) != 3 or header[1] != b'blob':
                raise ArtifactError('Git returned an invalid native source blob')
            size = int(header[2])
            data = response.read(size)
            if len(data) != size or response.read(1) != b'\n':
                raise ArtifactError('Truncated native source blob')
            records.append({'path': name, 'blob': canonical_blob(data, object_format)})
    else:
        commit = git(repo, 'rev-parse', 'HEAD').decode().strip()
        names = git(repo, 'ls-files', '-z', '--cached', '--others', '--exclude-standard',
                    '--', *SOURCE_SCOPES).split(b'\0')
        for name in sorted({source_path(raw.decode('utf-8')) for raw in names if raw}):
            path = safe_repo_path(repo, name)
            if not path.exists():
                continue  # A tracked deletion must differ from the commit fingerprint.
            if not path.is_file():
                raise ArtifactError('Native input is not a regular file: ' + name)
            records.append({'path': name, 'blob': canonical_blob(path.read_bytes(), object_format)})
    records.sort(key=lambda item: item['path'])
    present = {record['path'] for record in records}
    if not set(SOURCE_SCRIPTS).issubset(present):
        raise ArtifactError('Native builder, pinned sources manifest or license collector is missing')
    identity = {'algorithm': FINGERPRINT_ALGORITHM, 'git_object_format': object_format,
                'inputs': records}
    return {'fingerprint': sha256(canonical_json(identity)), 'inputs': records,
            'commit': commit, 'git_object_format': object_format,
            'fingerprint_algorithm': FINGERPRINT_ALGORITHM}


def validate_elf(data, machine):
    if len(data) < 64 or data[:7] != b'\x7fELF\x02\x01\x01':
        raise ArtifactError('Engine must be a little-endian ELF64 library')
    kind, actual_machine, version = struct.unpack_from('<HHI', data, 16)
    phoff = struct.unpack_from('<Q', data, 32)[0]
    ehsize, entsize, count = struct.unpack_from('<HHH', data, 52)
    if kind != 3 or actual_machine != machine or version != 1 or ehsize != 64:
        raise ArtifactError('Engine ELF type, version or Android ABI is incorrect')
    if phoff < 64 or entsize < 56 or count == 0 or phoff + entsize * count > len(data):
        raise ArtifactError('Engine ELF program header table is invalid')
    loads = []
    for number in range(count):
        entry = struct.unpack_from('<IIQQQQQQ', data, phoff + entsize * number)
        if entry[0] != 1:
            continue
        _, _, offset, address, _, file_size, memory_size, alignment = entry
        if (alignment < 16384 or alignment & (alignment - 1) or
                offset % alignment != address % alignment or
                offset + file_size > len(data) or memory_size < file_size):
            raise ArtifactError('Engine LOAD segments must be valid and aligned to at least 16 KiB')
        loads.append(alignment)
    if not loads:
        raise ArtifactError('Engine ELF has no LOAD segment')


def validate_payload(name, data):
    if name in LIBRARIES:
        if len(data) > MAX_LIBRARY_BYTES:
            raise ArtifactError('Engine library exceeds the bundle size limit')
        validate_elf(data, LIBRARIES[name])
    elif name == NOTICES:
        if not data.strip() or len(data) > MAX_NOTICES_BYTES:
            raise ArtifactError('Native dependency license notices are empty or oversized')
        try:
            data.decode('utf-8')
        except UnicodeDecodeError as error:
            raise ArtifactError('Native dependency license notices must be UTF-8') from error
    else:
        raise ArtifactError('Unexpected native payload path')


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name('.' + path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        with temporary.open('xb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()  # Only this function's uniquely created temporary file.


def package(repo, output, commit, producer_kind):
    committed = fingerprint(repo, commit)
    current = fingerprint(repo)
    if current['fingerprint'] != committed['fingerprint']:
        raise ArtifactError('Working native inputs differ from --commit; commit and build those inputs first')
    payloads = {}
    for name in PAYLOADS:
        path = safe_repo_path(repo, name)
        if not path.is_file():
            raise ArtifactError('Native build output missing: ' + name)
        data = path.read_bytes()
        validate_payload(name, data)
        payloads[name] = data
    manifest = {'format': FORMAT, 'source_commit': committed['commit'],
                'source_fingerprint': current['fingerprint'],
                'fingerprint_algorithm': FINGERPRINT_ALGORITHM,
                'git_object_format': current['git_object_format'], 'inputs': current['inputs'],
                'producer': {'kind': producer_kind},
                'files': [{'path': name, 'sha256': sha256(data), 'size': len(data)}
                          for name, data in payloads.items()]}
    pins = json.loads((repo / SOURCE_SCRIPTS[1]).read_text(encoding='utf-8-sig'))
    if isinstance(pins.get('toolchains'), dict):
        manifest['toolchains'] = pins['toolchains']
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
        for name, data in [('manifest.json', canonical_json(manifest)), *payloads.items()]:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            bundle.writestr(info, data)
    if fingerprint(repo)['fingerprint'] != current['fingerprint']:
        raise ArtifactError('Native inputs changed during packaging; retry after the edit is complete')
    output = Path(output).resolve()
    atomic_write(output, archive.getvalue())
    return {'ready': True, 'bundle': str(output), 'bundle_sha256': sha256(archive.getvalue()),
            'source_commit': committed['commit'], 'source_fingerprint': current['fingerprint']}


def read_bundle(repo, archive_bytes, expected_commit=None):
    try:
        with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            if len(names) != len(set(names)) or set(names) != {'manifest.json', *PAYLOADS}:
                raise ArtifactError('Bundle contains duplicate, unsafe, missing or unexpected entries')
            for info in infos:
                mode = info.external_attr >> 16
                maximum = (MAX_MANIFEST_BYTES if info.filename == 'manifest.json' else
                           MAX_NOTICES_BYTES if info.filename == NOTICES else MAX_LIBRARY_BYTES)
                if (info.is_dir() or info.flag_bits & 1 or info.file_size > maximum or
                        (mode and stat.S_IFMT(mode) not in (0, stat.S_IFREG))):
                    raise ArtifactError('Bundle entries must be ordinary, unencrypted files within size limits')
            manifest = json.loads(archive.read('manifest.json'))
            if (not isinstance(manifest, dict) or manifest.get('format') != FORMAT or
                    manifest.get('fingerprint_algorithm') != FINGERPRINT_ALGORITHM):
                raise ArtifactError('Unsupported native engine bundle format')
            if (not isinstance(manifest.get('source_commit'), str) or
                    not COMMIT_PATTERN.fullmatch(manifest['source_commit'])):
                raise ArtifactError('Bundle source commit is invalid')
            if expected_commit and manifest['source_commit'] != expected_commit:
                raise ArtifactError('Bundle source commit differs from the selected GitHub run')
            current = fingerprint(repo)
            if (manifest.get('source_fingerprint') != current['fingerprint'] or
                    manifest.get('inputs') != current['inputs'] or
                    manifest.get('git_object_format') != current['git_object_format']):
                raise ArtifactError('Bundle native inputs differ from this working tree; build/fetch the matching engine')
            producer = manifest.get('producer')
            if not isinstance(producer, dict) or producer.get('kind') not in ('github-actions', 'release-bootstrap', 'local'):
                raise ArtifactError('Bundle producer metadata is invalid')
            files = manifest.get('files')
            if not isinstance(files, list) or len(files) != len(PAYLOADS):
                raise ArtifactError('Bundle file manifest is invalid')
            records = {}
            for record in files:
                if not isinstance(record, dict) or record.get('path') not in PAYLOADS or record['path'] in records:
                    raise ArtifactError('Bundle file manifest contains an unexpected or duplicate path')
                if (type(record.get('size')) is not int or record['size'] < 1 or
                        not isinstance(record.get('sha256'), str) or not SHA256_PATTERN.fullmatch(record['sha256'])):
                    raise ArtifactError('Bundle file size or SHA256 is invalid')
                records[record['path']] = record
            payloads = {}
            for name in PAYLOADS:
                data = archive.read(name)
                if len(data) != records[name]['size'] or sha256(data) != records[name]['sha256']:
                    raise ArtifactError('Native payload checksum or size mismatch: ' + name)
                validate_payload(name, data)
                safe_repo_path(repo, name)
                payloads[name] = data
    except (zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError, KeyError,
            ValueError, struct.error, RuntimeError) as error:
        raise ArtifactError('Malformed native engine bundle: ' + str(error)) from error
    return manifest, payloads


@contextmanager
def install_lock(repo):
    state = safe_repo_path(repo, '.gradle/native-engine')
    state.mkdir(parents=True, exist_ok=True)
    lock_path = safe_repo_path(repo, '.gradle/native-engine/install.lock')
    with lock_path.open('a+b') as stream:
        if stream.tell() == 0:
            stream.write(b'\0')
            stream.flush()
        deadline = time.monotonic() + 30
        while True:
            try:
                stream.seek(0)
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise ArtifactError('Another native engine update is still running')
                time.sleep(0.1)
        try:
            yield state
        finally:
            stream.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def apply_payloads(repo, payloads, receipt_path, receipt_bytes):
    # The receipt is committed last. A failed update restores the previous bytes.
    previous = {}
    touched = []
    try:
        for relative, data in payloads.items():
            path = safe_repo_path(repo, relative)
            old = path.read_bytes() if path.is_file() else None
            if old == data:
                continue
            previous[path] = old
            atomic_write(path, data)
            touched.append(path)
        if not receipt_path.is_file() or receipt_path.read_bytes() != receipt_bytes:
            atomic_write(receipt_path, receipt_bytes)
    except (OSError, ArtifactError):
        for path in reversed(touched):
            if previous[path] is None:
                path.unlink()  # A generated file created by this failed transaction only.
            else:
                atomic_write(path, previous[path])
        raise


def install(repo, bundle, expected_commit=None):
    if expected_commit and not COMMIT_PATTERN.fullmatch(expected_commit):
        raise ArtifactError('--expected-commit must be a full hexadecimal Git SHA')
    archive_bytes = Path(bundle).read_bytes()
    manifest, payloads = read_bundle(repo, archive_bytes, expected_commit)
    digest = sha256(archive_bytes)
    relative = 'cache/' + manifest['source_fingerprint'] + '/' + digest + '.zip'
    receipt = {'format': RECEIPT_FORMAT, 'source_commit': manifest['source_commit'],
               'source_fingerprint': manifest['source_fingerprint'], 'bundle': relative,
               'bundle_sha256': digest, 'producer': manifest['producer']}
    with install_lock(repo) as state:
        if fingerprint(repo)['fingerprint'] != manifest['source_fingerprint']:
            raise ArtifactError('Native inputs changed before installation')
        cached = safe_repo_path(repo, '.gradle/native-engine/' + relative)
        if not cached.is_file() or sha256(cached.read_bytes()) != digest:
            atomic_write(cached, archive_bytes)
        apply_payloads(repo, payloads, state / 'active.json', canonical_json(receipt))
    return {'ready': True, **receipt}


def prepared_state(repo, restore=False):
    receipt_path = safe_repo_path(repo, '.gradle/native-engine/active.json')
    if not receipt_path.is_file():
        raise ArtifactError('No verified engine is installed. Run the Windows engine download tool first')
    if receipt_path.stat().st_size > MAX_MANIFEST_BYTES:
        raise ArtifactError('Invalid native engine receipt')
    try:
        receipt = json.loads(receipt_path.read_bytes())
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ArtifactError('Invalid native engine receipt') from error
    current = fingerprint(repo)
    if not isinstance(receipt, dict) or receipt.get('format') != RECEIPT_FORMAT:
        raise ArtifactError('Unsupported native engine receipt')
    if receipt.get('source_fingerprint') != current['fingerprint']:
        raise ArtifactError('Native inputs changed. Build the changed engine with GitHub Actions and download it first')
    digest = receipt.get('bundle_sha256')
    if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
        raise ArtifactError('Invalid cached engine checksum')
    relative = 'cache/' + current['fingerprint'] + '/' + digest + '.zip'
    if receipt.get('bundle') != relative:
        raise ArtifactError('Cached engine path does not match its receipt')
    cached = safe_repo_path(repo, '.gradle/native-engine/' + relative)
    if not cached.is_file() or sha256(cached.read_bytes()) != digest:
        raise ArtifactError('Cached native engine is missing or damaged; download it again')
    manifest, payloads = read_bundle(repo, cached.read_bytes(), receipt.get('source_commit'))
    outputs_ready = all(safe_repo_path(repo, name).is_file() and
                        safe_repo_path(repo, name).read_bytes() == data
                        for name, data in payloads.items())
    needed_restore = not outputs_ready
    if restore:
        with install_lock(repo) as state:
            if fingerprint(repo)['fingerprint'] != current['fingerprint']:
                raise ArtifactError('Native inputs changed during preparation')
            apply_payloads(repo, payloads, state / 'active.json', canonical_json(receipt))
        outputs_ready = True
    return {'ready': outputs_ready, 'source_fingerprint': current['fingerprint'],
            'source_commit': manifest['source_commit'], 'producer': manifest['producer'],
            'bundle_sha256': digest, 'outputs_restored': restore and needed_restore}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('fingerprint', 'pack', 'install', 'prepare', 'status'):
        command = sub.add_parser(name)
        command.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
        if name in ('fingerprint', 'status'):
            command.add_argument('--json', action='store_true')
        if name in ('fingerprint', 'pack'):
            command.add_argument('--commit', required=name == 'pack')
        if name == 'pack':
            command.add_argument('--output', type=Path, required=True)
            command.add_argument('--producer-kind', choices=('github-actions', 'release-bootstrap', 'local'), default='local')
        if name == 'install':
            command.add_argument('--bundle', type=Path, required=True)
            command.add_argument('--expected-commit')
    args = parser.parse_args(argv)
    try:
        repo = repository(args.repo)
        if args.command == 'fingerprint':
            result = fingerprint(repo, args.commit)
            print(json.dumps(result, ensure_ascii=True) if args.json else result['fingerprint'])
            return 0
        if args.command == 'pack':
            result = package(repo, args.output, args.commit, args.producer_kind)
        elif args.command == 'install':
            result = install(repo, args.bundle, args.expected_commit)
        elif args.command == 'prepare':
            result = prepared_state(repo, restore=True)
        else:
            try:
                result = prepared_state(repo)
            except ArtifactError as error:
                result = {'ready': False, 'reason': str(error), 'source_fingerprint': fingerprint(repo)['fingerprint']}
        print(json.dumps(result, ensure_ascii=True))
        return 0
    except (ArtifactError, OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        print('Native engine: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
