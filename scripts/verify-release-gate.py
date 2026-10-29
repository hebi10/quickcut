#!/usr/bin/env python3
"""Authorize publication from source only after both exact-main CI runs succeed.

No polling, artifacts, check runs, or third-party dependencies. A later workflow
completion retries the gate; API errors fail closed rather than authorizing.
"""
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

REQUIRED_WORKFLOWS = {
    'android.yml': 'Android build',
    'emulator-smoke.yml': 'Android emulator smoke',
}


def trusted_run(run, repo, sha, name):
    return (run.get('name') == name and run.get('head_sha') == sha
            and run.get('head_branch') == 'main'
            and run.get('event') in ('push', 'workflow_dispatch')
            and run.get('head_repository', {}).get('full_name') == repo
            and run.get('repository', {}).get('full_name') == repo)


def event_sha(event, event_name, repo, ref, sha):
    if event_name == 'workflow_run':
        run = event['workflow_run']
        sha = run.get('head_sha', '')
        if (run.get('name') not in REQUIRED_WORKFLOWS.values()
                or not trusted_run(run, repo, sha, run.get('name'))
                or run.get('status') != 'completed' or run.get('conclusion') != 'success'):
            raise ValueError('Release requires a successful same-repository main CI completion')
    elif event_name != 'workflow_dispatch' or ref != 'refs/heads/main':
        raise ValueError('Manual releases must run from main')
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('Invalid release commit SHA')
    return sha


def evaluate(repo, sha, tag, fetch):
    prefix = f'/repos/{repo}'
    main = fetch(prefix + '/git/ref/heads/main')
    if main['object']['sha'] != sha:
        return False, 'Commit is no longer the current main HEAD'
    for workflow, name in REQUIRED_WORKFLOWS.items():
        query = urlencode({'branch': 'main', 'head_sha': sha, 'per_page': 100})
        response = fetch(f'{prefix}/actions/workflows/{workflow}/runs?{query}')
        if response.get('total_count', 0) > 100:
            raise ValueError('Too many runs for one commit; refusing an incomplete CI history')
        runs = [run for run in response['workflow_runs'] if trusted_run(run, repo, sha, name)]
        if not runs:
            return False, f'{name}: no trusted run for this exact main commit'
        # Never reuse an older success after a newer run starts or fails.
        latest = max(runs, key=lambda run: (run['run_number'], run['id']))
        if latest.get('status') != 'completed' or latest.get('conclusion') != 'success':
            return False, f"{name}: {latest.get('status')}/{latest.get('conclusion')}"
    for endpoint in ('git/ref/tags', 'releases/tags'):
        if fetch(f'{prefix}/{endpoint}/{tag}') is not None:
            return False, f'{tag} already exists; releases and assets are never overwritten'
    return True, 'Both required workflows succeeded on the exact current main commit'


def api_fetch(path):
    request = Request('https://api.github.com' + path, headers={
        'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
        'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
    })
    try:
        with urlopen(request, timeout=30) as response:
            return json.load(response)
    except HTTPError as error:
        if error.code == 404 and ('/git/ref/tags/' in path or '/releases/tags/' in path):
            return None
        raise


def parse_release_version(gradle):
    """Read only supported version declarations; never evaluate Gradle code."""
    declarations = {}
    for field in ('versionName', 'versionCode'):
        values = re.findall(r'^[ \t]*' + field + r'[ \t]*=[ \t]*(.*?)[ \t]*$', gradle, re.M)
        if len(values) != 1:
            raise ValueError('Expected exactly one ' + field + ' declaration')
        declarations[field] = values[0]
    version = re.fullmatch(r'"([0-9]+\.[0-9]+\.[0-9]+)"', declarations['versionName'])
    # This specific override exists solely for instrumentation upgrade fixtures.
    # A release always uses the committed production fallback, not a test code.
    code = re.fullmatch(
        r'(?:providers\.gradleProperty\("quickcutTestVersionCode"\)\.orNull\?\.toInt\(\)[ \t]*\?:[ \t]*)?([1-9][0-9]*)',
        declarations['versionCode'])
    if not version or not code:
        raise ValueError('Unsupported source release version declaration')
    return version[1], code[1]


def main():
    repo = os.environ['GITHUB_REPOSITORY']
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    sha = event_sha(event, os.environ['GITHUB_EVENT_NAME'], repo,
                    os.environ.get('GITHUB_REF', ''), os.environ.get('GITHUB_SHA', ''))
    # The checkout must be the authorized event commit, never the default branch
    # substituted by checkout's workflow_run behavior.
    import subprocess
    checked_out = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if checked_out != sha:
        raise ValueError('Checkout does not match the triggering commit')
    gradle = Path('app/build.gradle.kts').read_text()
    version, code = parse_release_version(gradle)
    tag = 'v' + version
    ready, reason = evaluate(repo, sha, tag, api_fetch)
    values = {'ready': str(ready).lower(), 'sha': sha, 'tag': tag,
              'version': version, 'version_code': code}
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            for key, value in values.items():
                output.write(f'{key}={value}\n')
    print(f'Release gate: {reason}')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write(f'Release gate for `{sha}` / `{tag}`: {reason}.\n')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, HTTPError) as error:
        print(f'::error::Release gate rejected: {error}', file=sys.stderr)
        sys.exit(1)
