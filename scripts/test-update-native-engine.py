#!/usr/bin/env python3
"""Exercise the GitHub boundary with real local bundles and the real installer."""
import copy
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).with_name('update-native-engine.py')
HELPER = Path(__file__).with_name('native-engine-artifact.py')
REF = 'dev/native-engine/windows'
REPOSITORY = 'hebi10/quickcut'
WORKFLOW = '.github/workflows/native-engine.yml'
LIBRARY_PATH = 'app/build/generated/nativeLibs/arm64-v8a/libquickcut_smartcut.so'


def elf(machine):
    header = struct.pack('<16sHHIQQQIHHHHHH', b'\x7fELF\x02\x01\x01' + b'\0' * 9,
                         3, machine, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
    return header + struct.pack('<IIQQQQQQ', 1, 5, 0, 0, 0, 120, 120, 16384)


class FakeGitHub:
    """Only the external network/gh boundary is replaced."""
    def __init__(self, commit, outer):
        self.run = {'id': 101, 'head_sha': commit, 'head_branch': REF,
                    'path': WORKFLOW, 'workflow_id': 7, 'event': 'push',
                    'status': 'completed', 'conclusion': 'success', 'run_attempt': 1,
                    'repository': {'id': 2, 'full_name': REPOSITORY},
                    'head_repository': {'id': 2, 'full_name': REPOSITORY}}
        self.artifact = {'id': 55, 'name': 'quickcut-native-engine', 'expired': False,
                         'size_in_bytes': len(outer),
                         'digest': 'sha256:' + hashlib.sha256(outer).hexdigest(),
                         'workflow_run': {'id': 101, 'head_sha': commit,
                                          'head_branch': REF, 'repository_id': 2,
                                          'head_repository_id': 2}}
        self.outer = outer
        self.artifacts = [self.artifact]
        self.after_download = None
        self.available_runs = None
        self.dispatched = False

    def request_json(self, path, method='GET', body=None):
        if path.endswith('/actions/workflows/native-engine.yml/dispatches') and method == 'POST':
            if body != {'ref': REF}:
                raise AssertionError(f'Unexpected workflow dispatch: {body}')
            self.dispatched = True
            self.run.update(id=102, event='workflow_dispatch')
            self.artifact['workflow_run']['id'] = 102
            return None
        if '/actions/workflows/native-engine.yml/runs?' in path:
            runs = self.available_runs if self.available_runs is not None else [self.run]
            return {'workflow_runs': copy.deepcopy(runs)}
        if path.endswith(f'/actions/runs/{self.run["id"]}/artifacts?per_page=100'):
            return {'total_count': len(self.artifacts), 'artifacts': copy.deepcopy(self.artifacts)}
        if path.endswith(f'/actions/runs/{self.run["id"]}'):
            return copy.deepcopy(self.run)
        if path.endswith('/actions/workflows/7'):
            return {'id': 7, 'path': WORKFLOW, 'name': 'QuickCut native engine'}
        if '/commits/' in path:
            return {'sha': self.run['head_sha']}
        raise AssertionError(f'Unexpected network request: {method} {path}')

    def request_bytes(self, path):
        if not path.endswith('/actions/artifacts/55/zip'):
            raise AssertionError(f'Unexpected binary request: {path}')
        if self.after_download:
            self.after_download(self)
        return self.outer


class UpdateNativeEngineTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='quickcut-client-test-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        self.git('config', 'core.autocrlf', 'false')
        self.git('remote', 'add', 'origin', 'https://github.com/hebi10/quickcut.git')
        for relative, contents in {
            '.gitignore': 'build/\n**/build/\n.gradle/\n',
            'native/engine.rs': 'pub fn example() {}\n',
            'scripts/build-native-smartcut.sh': '#!/bin/sh\ntrue\n',
            'scripts/native-smartcut-sources.json': '{"toolchains":{}}\n',
            'scripts/collect-native-licenses.py': 'print("licenses")\n',
        }.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding='utf-8')
        self.git('add', '.')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                 'commit', '-qm', 'Fixture native sources')
        self.commit = self.git('rev-parse', 'HEAD').strip()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], text=True)

    def client(self):
        self.assertTrue(SCRIPT.is_file(), 'GitHub engine downloader has not been implemented')
        spec = importlib.util.spec_from_file_location('update_engine', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def bundle(self):
        self.assertTrue(HELPER.is_file(), 'Artifact helper must be available for integration tests')
        scripts = self.root / 'scripts'
        (scripts / HELPER.name).write_bytes(HELPER.read_bytes())
        for abi, machine in [('arm64-v8a', 183), ('x86_64', 62)]:
            path = self.root / f'app/build/generated/nativeLibs/{abi}/libquickcut_smartcut.so'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(elf(machine))
        notice = self.root / 'build/generated/smartcutAssets/licenses/NATIVE-DEPENDENCIES.txt'
        notice.parent.mkdir(parents=True, exist_ok=True)
        notice.write_text('Fixture notices\n', encoding='utf-8')
        destination = self.root / '.gradle/fixture-engine.zip'
        result = subprocess.run([sys.executable, str(HELPER), 'pack', '--repo', str(self.root),
                                 '--output', str(destination), '--commit', self.commit,
                                 '--producer-kind', 'github-actions'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            archive.writestr('quickcut-native-engine.zip', destination.read_bytes())
        return data.getvalue()

    def setup_download(self):
        client = self.client()
        gh = FakeGitHub(self.commit, self.bundle())
        # Existing output is deliberately different: a successful real install must replace it.
        (self.root / LIBRARY_PATH).write_bytes(b'previous engine preserved until validation')
        return client, gh

    def update(self, client, gh, **overrides):
        options = dict(repo=self.root, reference=REF, run_id=101, dispatch=False,
                       wait=True, timeout=30, gh=gh)
        options.update(overrides)
        return client.update_engine(**options)

    def test_success_downloads_verified_bundle_and_real_installer_restores_engine(self):
        client, gh = self.setup_download()
        result = self.update(client, gh)
        self.assertEqual(result['run_id'], 101)
        self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), elf(183))
        receipt = self.root / '.gradle/native-engine'
        self.assertTrue(any(receipt.rglob('*.json')))

    def test_wrong_workflow_run_cannot_replace_existing_engine(self):
        client, gh = self.setup_download()
        gh.run['path'] = '.github/workflows/release.yml'
        before = (self.root / LIBRARY_PATH).read_bytes()
        with self.assertRaisesRegex(client.ClientError, 'workflow|워크플로'):
            self.update(client, gh)
        self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), before)

    def test_fork_or_pull_request_run_is_not_trusted(self):
        for change in ('fork', 'pull_request'):
            with self.subTest(change=change):
                client, gh = self.setup_download()
                if change == 'fork':
                    gh.run['head_repository']['full_name'] = 'someone/quickcut'
                else:
                    gh.run['event'] = 'pull_request'
                before = (self.root / LIBRARY_PATH).read_bytes()
                with self.assertRaises(client.ClientError):
                    self.update(client, gh)
                self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), before)

    def test_wrong_branch_and_failed_run_preserve_engine(self):
        for change in ('branch', 'failed'):
            with self.subTest(change=change):
                client, gh = self.setup_download()
                if change == 'branch':
                    gh.run['head_branch'] = 'main'
                else:
                    gh.run['conclusion'] = 'failure'
                before = (self.root / LIBRARY_PATH).read_bytes()
                with self.assertRaises(client.ClientError):
                    self.update(client, gh)
                self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), before)

    def test_changed_local_native_source_rejects_old_bundle(self):
        client, gh = self.setup_download()
        (self.root / 'native/engine.rs').write_text('pub fn changed() {}\n', encoding='utf-8')
        before = (self.root / LIBRARY_PATH).read_bytes()
        with self.assertRaisesRegex(client.ClientError, 'fingerprint|입력|source|소스'):
            self.update(client, gh)
        self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), before)

    def test_corrupt_download_digest_preserves_existing_engine(self):
        client, gh = self.setup_download()
        gh.outer += b'corrupt download'
        before = (self.root / LIBRARY_PATH).read_bytes()
        with self.assertRaisesRegex(client.ClientError, 'digest|해시|SHA'):
            self.update(client, gh)
        self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), before)

    def test_artifact_cannot_claim_another_run(self):
        client, gh = self.setup_download()
        gh.artifact['workflow_run']['id'] = 999
        with self.assertRaises(client.ClientError):
            self.update(client, gh)
        self.assertNotEqual((self.root / LIBRARY_PATH).read_bytes(), elf(183))

    def test_run_restarted_during_download_cannot_install_previous_success(self):
        client, gh = self.setup_download()
        gh.after_download = lambda api: api.run.update(run_attempt=2, status='in_progress', conclusion=None)
        before = (self.root / LIBRARY_PATH).read_bytes()
        with self.assertRaisesRegex(client.ClientError, 'changed|변경|completed|완료|다시'):
            self.update(client, gh)
        self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), before)

    def test_unexpected_outer_archive_paths_are_not_extracted(self):
        client, gh = self.setup_download()
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            archive.writestr('../outside', b'untrusted')
        gh.outer = data.getvalue()
        gh.artifact['digest'] = 'sha256:' + hashlib.sha256(gh.outer).hexdigest()
        with self.assertRaises(client.ClientError):
            self.update(client, gh)
        self.assertFalse((self.root.parent / 'outside').exists())

    def test_origin_validation_refuses_other_repositories(self):
        client = self.client()
        self.git('remote', 'set-url', 'origin', 'https://github.com/someone/quickcut.git')
        with self.assertRaises(client.ClientError):
            client.repository_from_origin(self.root)

    def test_dispatch_builds_remote_matching_native_inputs_then_installs(self):
        client, gh = self.setup_download()
        result = self.update(client, gh, run_id=None, dispatch=True)
        self.assertEqual(result['run_id'], 102)
        self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), elf(183))

    def test_dispatch_refuses_native_changes_that_are_not_on_remote(self):
        client, gh = self.setup_download()
        (self.root / 'native/engine.rs').write_text('pub fn not_pushed() {}\n', encoding='utf-8')
        before = (self.root / LIBRARY_PATH).read_bytes()
        with self.assertRaisesRegex(client.ClientError, 'commit|push|입력|소스'):
            self.update(client, gh, run_id=None, dispatch=True)
        self.assertFalse(gh.dispatched)
        self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), before)

    def test_latest_run_is_downloaded_without_dispatch(self):
        client, gh = self.setup_download()
        result = self.update(client, gh, run_id=None)
        self.assertEqual(result['run_id'], 101)
        self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), elf(183))

    def test_missing_run_explains_initial_branch_push(self):
        client, gh = self.setup_download()
        gh.available_runs = []
        before = (self.root / LIBRARY_PATH).read_bytes()
        with self.assertRaisesRegex(client.ClientError, 'push'):
            self.update(client, gh, run_id=None)
        self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), before)

    def test_ui_only_commit_does_not_require_rebuilding_engine(self):
        client, gh = self.setup_download()
        (self.root / 'Ui.kt').write_text('class UpdatedScreen\n', encoding='utf-8')
        self.git('add', 'Ui.kt')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                 'commit', '-qm', 'UI only change')
        self.assertNotEqual(self.git('rev-parse', 'HEAD').strip(), self.commit)
        self.update(client, gh)
        self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), elf(183))

    def test_matching_working_inputs_are_allowed_even_after_different_native_commit(self):
        client, gh = self.setup_download()
        (self.root / 'native/engine.rs').write_text('pub fn newer() {}\n', encoding='utf-8')
        self.git('add', 'native/engine.rs')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                 'commit', '-qm', 'Different engine source')
        (self.root / 'native/engine.rs').write_text('pub fn example() {}\n', encoding='utf-8')
        self.update(client, gh)
        self.assertEqual((self.root / LIBRARY_PATH).read_bytes(), elf(183))

    def test_git_credential_reuse_stays_in_child_environment_and_not_console(self):
        client = self.client()
        secret = 'fixture-not-a-real-credential'

        def external_process(command, **options):
            if command[1:3] == ['auth', 'status']:
                return subprocess.CompletedProcess(command, 1, b'', b'not logged in')
            if 'credential' in command:
                self.assertEqual(options['env']['GIT_TERMINAL_PROMPT'], '0')
                self.assertEqual(options['env']['GCM_INTERACTIVE'], 'Never')
                return subprocess.CompletedProcess(command, 0, f'password={secret}\n'.encode(), b'')
            self.assertEqual(options['env']['GH_TOKEN'], secret)
            self.assertEqual(options['env']['GH_HOST'], 'github.com')
            self.assertEqual(command[command.index('--hostname') + 1], 'github.com')
            return subprocess.CompletedProcess(command, 0, b'{"id":123}', b'')

        output = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), patch.object(client.subprocess, 'run', external_process):
            with redirect_stdout(output), redirect_stderr(output):
                api = client.GhClient(self.root, sys.executable)
                self.assertEqual(api.request_json('repos/hebi10/quickcut/actions/runs/123')['id'], 123)
        self.assertNotIn(secret, output.getvalue())

    def test_authentication_timeout_is_actionable_without_exposing_process_output(self):
        client = self.client()
        with patch.dict(os.environ, {}, clear=True), patch.object(client.subprocess, 'run',
                side_effect=subprocess.TimeoutExpired('gh', 30, output=b'fixture-secret')):
            with self.assertRaisesRegex(client.ClientError, '인증|로그인|연결') as failure:
                client.GhClient(self.root, sys.executable)
        self.assertNotIn('fixture-secret', str(failure.exception))

    def test_api_error_does_not_expose_external_stderr(self):
        client = self.client()
        with patch.dict(os.environ, {'GH_TOKEN': 'fixture-secret'}, clear=True):
            api = client.GhClient(self.root, sys.executable)
        with patch.object(client.subprocess, 'run', return_value=subprocess.CompletedProcess(
                ['gh'], 1, b'', b'Authorization: Bearer fixture-secret')):
            with self.assertRaises(client.ClientError) as failure:
                api.request_json('repos/hebi10/quickcut/actions/runs/123')
        self.assertNotIn('fixture-secret', str(failure.exception))


if __name__ == '__main__':
    unittest.main()
