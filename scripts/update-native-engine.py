#!/usr/bin/env python3
"""Import a verified native-only GitHub Actions build without local Linux tools."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
from urllib.parse import quote, urlencode, urlparse
import zipfile

REPOSITORY = 'hebi10/quickcut'
WORKFLOW = '.github/workflows/native-engine.yml'
WORKFLOW_NAME = 'QuickCut native engine'
ARTIFACT = 'quickcut-native-engine'
BUNDLE = 'quickcut-native-engine.zip'
DEFAULT_REF = 'dev/native-engine/windows'
API = f'repos/{REPOSITORY}'
MAX_DOWNLOAD = 512 * 1024 * 1024
SHA = re.compile(r'(?:[0-9a-f]{40}|[0-9a-f]{64})\Z')


class ClientError(Exception):
    """An expected, safe-to-display failure."""


def repository_from_origin(repo):
    result = subprocess.run(['git', '-C', str(repo), 'remote', 'get-url', 'origin'],
                            capture_output=True, text=True)
    if result.returncode:
        raise ClientError('로컬 Git 저장소의 origin을 확인할 수 없습니다.')
    origin = result.stdout.strip()
    if origin.startswith('git@github.com:'):
        name = origin[len('git@github.com:'):]
    else:
        url = urlparse(origin)
        if url.scheme not in ('https', 'ssh') or url.hostname != 'github.com' or url.port:
            raise ClientError('origin은 github.com의 hebi10/quickcut 저장소여야 합니다.')
        name = url.path.lstrip('/')
    if name.endswith('.git'):
        name = name[:-4]
    if name.lower() != REPOSITORY:
        raise ClientError('origin이 hebi10/quickcut과 다릅니다. 다른 저장소에는 접근하지 않습니다.')
    return REPOSITORY


def find_gh(repo, explicit=None):
    candidates = [explicit] if explicit else [shutil.which('gh'),
        Path(repo).parent / '.tools/gh/bin/gh.exe', Path(repo).parent / '.tools/gh/gh.exe']
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate).resolve())
    raise ClientError('GitHub CLI(gh)를 찾지 못했습니다. --gh로 실행 파일 경로를 지정해주세요.')


class GhClient:
    """The sole GitHub boundary. Credentials stay in child-process memory."""
    def __init__(self, repo, executable=None):
        self.executable = find_gh(repo, executable)
        self.env = os.environ.copy()
        for key in list(self.env):
            if key == 'GH_DEBUG' or key.startswith(('GIT_TRACE', 'GCM_TRACE')):
                self.env.pop(key)
        self.env.update(GH_HOST='github.com', GH_PROMPT_DISABLED='1',
                        GH_NO_UPDATE_NOTIFIER='1', GIT_TERMINAL_PROMPT='0',
                        GCM_INTERACTIVE='Never')
        if self.env.get('GH_TOKEN') or self.env.get('GITHUB_TOKEN'):
            return
        # auth status avoids printing or persisting an authentication token.
        try:
            authenticated = subprocess.run([self.executable, 'auth', 'status', '--hostname', 'github.com'],
                                           env=self.env, capture_output=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ClientError('GitHub 인증 연결을 확인하지 못했습니다. gh 설치와 네트워크를 확인해주세요.') from error
        if authenticated.returncode == 0:
            return
        try:
            credential = subprocess.run(['git', '-C', str(repo), '-c', 'credential.interactive=never',
                                         'credential', 'fill'],
                                        input=b'protocol=https\nhost=github.com\n\n',
                                        env=self.env, capture_output=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ClientError('기존 GitHub 인증을 읽지 못했습니다. gh auth login --hostname github.com으로 로그인해주세요.') from error
        if credential.returncode == 0:
            for line in credential.stdout.decode('utf-8', errors='replace').splitlines():
                if line.startswith('password=') and len(line) > len('password='):
                    self.env['GH_TOKEN'] = line[len('password='):]
                    return
        raise ClientError('GitHub 인증이 없습니다. gh auth login --hostname github.com으로 로그인한 뒤 다시 실행해주세요.')

    def _request(self, path, method='GET', body=None):
        if not path.startswith(API + '/') or '://' in path or '\\' in path or '..' in path:
            raise ClientError('허용되지 않은 GitHub API 경로입니다.')
        command = [self.executable, 'api', '--hostname', 'github.com', '--method', method, path]
        data = None
        if body is not None:
            command += ['--input', '-']
            data = json.dumps(body).encode('utf-8')
        try:
            result = subprocess.run(command, input=data, capture_output=True, env=self.env, timeout=120)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ClientError('GitHub API 연결에 실패했습니다. 네트워크와 gh 설치를 확인해주세요.') from error
        if result.returncode:
            # Never relay arbitrary stderr: it may contain credentials from external tools.
            raise ClientError('GitHub API 요청에 실패했습니다. 저장소 접근 권한과 gh 인증을 확인해주세요. '
                              '워크플로가 아직 없으면 최초 dev/native-engine/** 브랜치 push가 필요합니다.')
        return result.stdout

    def request_json(self, path, method='GET', body=None):
        output = self._request(path, method, body)
        if not output.strip():
            return None
        try:
            return json.loads(output)
        except (ValueError, UnicodeError) as error:
            raise ClientError('GitHub API가 올바른 JSON을 반환하지 않았습니다.') from error

    def request_bytes(self, path):
        return self._request(path)


def helper(repo, *arguments):
    script = Path(repo) / 'scripts/native-engine-artifact.py'
    if not script.is_file():
        raise ClientError('scripts/native-engine-artifact.py가 없습니다. 개발환경 파일을 먼저 받으세요.')
    result = subprocess.run([sys.executable, str(script), *arguments, '--repo', str(repo)],
                            capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        # The local helper processes repository files only and does not handle credentials.
        reason = (result.stderr or result.stdout).strip()
        raise ClientError('엔진 소스/번들 검증 실패: ' + reason[-2000:])
    return result.stdout.strip()


def validate_ref(reference):
    if (not reference or reference.startswith(('-', '/')) or reference.endswith(('/', '.'))
            or any(char in reference for char in ' ~^:?*[\\')
            or '..' in reference or '//' in reference or '@{' in reference
            or any(part.startswith('.') or part.endswith('.lock') for part in reference.split('/'))
            or any(ord(char) < 32 or ord(char) == 127 for char in reference)):
        raise ClientError('올바른 Git 브랜치 이름을 --ref로 지정해주세요.')


def validate_run(gh, run, reference):
    if not isinstance(run, dict) or not isinstance(run.get('id'), int):
        raise ClientError('워크플로 실행 정보가 올바르지 않습니다.')
    for key in ('repository', 'head_repository'):
        if (run.get(key) or {}).get('full_name', '').lower() != REPOSITORY:
            raise ClientError('워크플로 실행의 저장소가 origin과 다릅니다.')
    if run.get('path') != WORKFLOW:
        raise ClientError('엔진 전용 workflow가 아닌 실행은 가져올 수 없습니다.')
    if run.get('head_branch') != reference:
        raise ClientError('실행의 브랜치가 --ref와 다릅니다.')
    if run.get('event') not in ('push', 'workflow_dispatch'):
        raise ClientError('push 또는 workflow_dispatch 실행만 가져올 수 있습니다.')
    if not SHA.fullmatch(str(run.get('head_sha', ''))):
        raise ClientError('실행의 source commit SHA가 올바르지 않습니다.')
    workflow_id = run.get('workflow_id')
    if not isinstance(workflow_id, int) or workflow_id <= 0:
        raise ClientError('워크플로 ID가 올바르지 않습니다.')
    workflow = gh.request_json(f'{API}/actions/workflows/{workflow_id}')
    if workflow.get('path') != WORKFLOW or workflow.get('name') != WORKFLOW_NAME:
        raise ClientError('워크플로 경로 또는 이름이 엔진 전용 workflow와 다릅니다.')


def list_runs(gh, reference):
    query = urlencode({'branch': reference, 'per_page': 100})
    response = gh.request_json(f'{API}/actions/workflows/native-engine.yml/runs?{query}')
    runs = response.get('workflow_runs', [])
    if not isinstance(runs, list):
        raise ClientError('GitHub 실행 목록이 올바르지 않습니다.')
    return runs


def dispatch_run(gh, repo, reference, wait, deadline):
    remote = gh.request_json(f'{API}/commits/{quote(reference, safe="")}')
    commit = remote.get('sha', '')
    if not SHA.fullmatch(commit):
        raise ClientError('원격 브랜치의 commit을 확인하지 못했습니다.')
    current = helper(repo, 'fingerprint')
    try:
        committed = helper(repo, 'fingerprint', '--commit', commit)
    except ClientError as error:
        raise ClientError('원격 엔진 commit을 로컬에서 확인하지 못했습니다. git fetch 후 다시 실행해주세요. '
                          '로컬 엔진 변경은 먼저 commit/push해야 합니다.') from error
    if current != committed:
        raise ClientError('현재 엔진 소스가 원격 브랜치 입력과 다릅니다. 엔진 변경을 commit/push한 뒤 실행해주세요.')
    previous = {run['id'] for run in list_runs(gh, reference)}
    gh.request_json(f'{API}/actions/workflows/native-engine.yml/dispatches', method='POST',
                    body={'ref': reference})
    while True:
        candidates = [run for run in list_runs(gh, reference)
                      if run.get('id') not in previous and run.get('head_sha') == commit
                      and run.get('event') == 'workflow_dispatch']
        if candidates:
            return max(candidates, key=lambda run: run['id'])['id']
        if not wait:
            raise ClientError('엔진 워크플로를 실행 요청했습니다. GitHub에 실행이 나타난 뒤 다시 가져와주세요.')
        if time.monotonic() >= deadline:
            raise ClientError('실행 요청 후 새 엔진 실행을 찾는 시간이 초과됐습니다. GitHub Actions에서 확인해주세요.')
        time.sleep(min(5, max(0, deadline - time.monotonic())))


def wait_for_run(gh, run, reference, wait, deadline):
    previous = None
    while True:
        validate_run(gh, run, reference)
        status = run.get('status')
        if status == 'completed':
            if run.get('conclusion') != 'success':
                raise ClientError('최신 엔진 실행이 성공하지 않았습니다. GitHub Actions 오류를 먼저 해결해주세요.')
            return run
        if not wait:
            raise ClientError(f'엔진 실행 {run["id"]}이 아직 완료되지 않았습니다. 완료 후 다시 실행해주세요.')
        if time.monotonic() >= deadline:
            raise ClientError(f'엔진 실행 {run["id"]} 대기 시간이 초과됐습니다. --run-id {run["id"]}로 이어서 확인해주세요.')
        if status != previous:
            print(f'엔진 실행 {run["id"]}: {status}', flush=True)
            previous = status
        time.sleep(min(5, max(0, deadline - time.monotonic())))
        run = gh.request_json(f'{API}/actions/runs/{run["id"]}')


def select_artifact(gh, run):
    response = gh.request_json(f'{API}/actions/runs/{run["id"]}/artifacts?per_page=100')
    matches = [item for item in response.get('artifacts', []) if item.get('name') == ARTIFACT]
    if len(matches) != 1:
        raise ClientError('성공한 실행에 quickcut-native-engine 아티팩트가 정확히 하나 있어야 합니다.')
    artifact = matches[0]
    if artifact.get('expired') is not False or not isinstance(artifact.get('id'), int):
        raise ClientError('엔진 아티팩트가 만료되었거나 ID가 올바르지 않습니다.')
    size = artifact.get('size_in_bytes')
    if not isinstance(size, int) or not 0 < size <= MAX_DOWNLOAD:
        raise ClientError('엔진 아티팩트 크기가 허용 범위를 벗어났습니다.')
    source = artifact.get('workflow_run') or {}
    if any(source.get(key) != run.get(key) for key in ('id', 'head_sha', 'head_branch')):
        raise ClientError('아티팩트의 실행/commit/브랜치가 선택한 실행과 다릅니다.')
    for key, source_key in [('repository', 'repository_id'), ('head_repository', 'head_repository_id')]:
        if source.get(source_key) != run[key].get('id'):
            raise ClientError('아티팩트의 저장소 ID가 선택한 실행과 다릅니다.')
    return artifact


def download_bundle(gh, repo, run, artifact):
    data = gh.request_bytes(f'{API}/actions/artifacts/{artifact["id"]}/zip')
    if not data or len(data) > MAX_DOWNLOAD:
        raise ClientError('다운로드한 아티팩트 크기가 허용 범위를 벗어났습니다.')
    digest = artifact.get('digest')
    if digest and digest != 'sha256:' + hashlib.sha256(data).hexdigest():
        raise ClientError('다운로드 SHA256 digest 해시가 GitHub 아티팩트와 다릅니다.')
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            files = archive.infolist()
            if len(files) != 1 or files[0].filename != BUNDLE:
                raise ClientError('아티팩트 ZIP에는 quickcut-native-engine.zip 파일 하나만 있어야 합니다.')
            entry = files[0]
            mode = entry.external_attr >> 16
            if (entry.is_dir() or stat.S_ISLNK(mode) or entry.flag_bits & 1
                    or entry.file_size > MAX_DOWNLOAD):
                raise ClientError('안전하지 않은 엔진 아티팩트 파일입니다.')
            bundle_data = archive.read(entry)
        with zipfile.ZipFile(io.BytesIO(bundle_data)) as archive:
            entries = [item for item in archive.infolist() if item.filename == 'manifest.json']
            if len(entries) != 1 or entries[0].file_size > 2 * 1024 * 1024:
                raise ClientError('엔진 manifest가 없거나 올바르지 않습니다.')
            manifest = json.loads(archive.read(entries[0]))
    except (zipfile.BadZipFile, RuntimeError, ValueError, OSError) as error:
        raise ClientError('다운로드한 엔진 ZIP/manifest를 읽을 수 없습니다.') from error
    if manifest.get('source_commit') != run['head_sha']:
        raise ClientError('엔진 source commit과 GitHub 실행의 head SHA가 다릅니다.')
    if (manifest.get('producer') or {}).get('kind') != 'github-actions':
        raise ClientError('GitHub Actions에서 생성된 엔진 번들만 가져올 수 있습니다.')
    if helper(repo, 'fingerprint') != manifest.get('source_fingerprint'):
        raise ClientError('현재 엔진 소스 fingerprint와 빌드 입력이 다릅니다. 엔진 변경을 commit/push한 뒤 새 실행을 사용해주세요.')
    directory = Path(repo) / '.gradle/native-engine/downloads'
    directory.mkdir(parents=True, exist_ok=True)
    owned = Path(tempfile.mkdtemp(prefix=f'run-{run["id"]}-', dir=directory))
    bundle = owned / BUNDLE
    bundle.write_bytes(bundle_data)
    return bundle


def update_engine(repo, reference=DEFAULT_REF, run_id=None, dispatch=False, wait=True,
                  timeout=1800, gh=None):
    repo = Path(repo).resolve()
    repository_from_origin(repo)
    validate_ref(reference)
    if timeout <= 0 or (run_id is not None and run_id <= 0):
        raise ClientError('--timeout과 --run-id는 양수여야 합니다.')
    if dispatch and run_id is not None:
        raise ClientError('--dispatch와 --run-id는 함께 사용할 수 없습니다.')
    gh = gh or GhClient(repo)
    deadline = time.monotonic() + timeout
    if dispatch:
        run_id = dispatch_run(gh, repo, reference, wait, deadline)
    if run_id is None:
        runs = list_runs(gh, reference)
        if not runs:
            raise ClientError('해당 브랜치의 엔진 실행이 없습니다. 최초에는 dev/native-engine/** 브랜치에 '
                              '엔진/워크플로 변경을 commit/push해야 합니다. 자동 push는 하지 않습니다.')
        run_id = max(runs, key=lambda item: item.get('id', 0))['id']
    run = gh.request_json(f'{API}/actions/runs/{run_id}')
    run = wait_for_run(gh, run, reference, wait, deadline)
    artifact = select_artifact(gh, run)
    bundle = download_bundle(gh, repo, run, artifact)
    fresh = gh.request_json(f'{API}/actions/runs/{run_id}')
    validate_run(gh, fresh, reference)
    if any(fresh.get(key) != run.get(key) for key in ('head_sha', 'run_attempt', 'status', 'conclusion')):
        raise ClientError('다운로드 중 실행 상태가 변경되었습니다. 다시 완료된 실행을 확인해주세요.')
    helper(repo, 'install', '--bundle', str(bundle), '--expected-commit', run['head_sha'])
    return {'run_id': run_id, 'commit': run['head_sha'], 'bundle': str(bundle)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parent.parent,
                        help='로컬 QuickCut 저장소 경로')
    parser.add_argument('--ref', default=DEFAULT_REF, help='엔진 빌드 브랜치')
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--run-id', type=int, help='가져올 성공한 엔진 실행 ID')
    group.add_argument('--dispatch', action='store_true', help='원격 브랜치의 엔진 워크플로를 명시적으로 실행')
    parser.add_argument('--wait', action=argparse.BooleanOptionalAction, default=True,
                        help='실행 완료까지 대기 (기본값: 대기)')
    parser.add_argument('--timeout', type=float, default=1800, help='대기 제한 초 (기본값: 1800)')
    parser.add_argument('--gh', help='GitHub CLI 실행 파일 경로')
    args = parser.parse_args(argv)
    try:
        repository_from_origin(args.repo)
        result = update_engine(args.repo, args.ref, args.run_id, args.dispatch, args.wait,
                               args.timeout, gh=GhClient(args.repo, args.gh))
        print(f'엔진 적용 완료: GitHub Actions #{result["run_id"]}, {result["commit"][:12]}')
        return 0
    except ClientError as error:
        print('오류: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
