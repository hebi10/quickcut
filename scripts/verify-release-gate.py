#!/usr/bin/env python3
"""Authorize publication from source only after both exact-main CI runs succeed.

No polling, artifacts, check runs, or third-party dependencies. A later workflow
completion retries the gate; API errors fail closed rather than authorizing.
"""
import json
from datetime import datetime
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
# Do not infer that every Android API passed from a workflow-level success alone.
# Require the exact Android build and complete three-device smoke job matrix.
REQUIRED_CI_JOBS = {
    'android.yml': frozenset({'build'}),
    'emulator-smoke.yml': frozenset({
        'emulator (29)', 'emulator (35)', 'emulator (36)',
    }),
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


def play_target(event, event_name):
    target = event.get('inputs', {}).get('target', 'internal')
    if event_name == 'workflow_run' and target == 'internal':
        return 'internal'
    if event_name == 'workflow_dispatch' and target in ('internal', 'closed'):
        return target
    raise ValueError('Automatic Play releases target internal; closed requires an explicit manual selection')


def completion_order(run):
    try:
        completed = datetime.strptime(run['updated_at'], '%Y-%m-%dT%H:%M:%SZ')
        if type(run['run_attempt']) is not int or run['run_attempt'] < 1:
            raise ValueError('Invalid workflow attempt')
        return completed, run['id']
    except (KeyError, TypeError, ValueError):
        raise ValueError('Missing or invalid trusted CI completion metadata') from None


def validate_ci_jobs(repo, sha, workflow, run, fetch):
    """Verify all required jobs belong to one exact successful workflow attempt.

    GitHub's jobs endpoint without an attempts suffix returns the latest attempt.
    Incomplete pages, renamed/extra matrix jobs, stale SHA, skips and duplicates
    must fail closed instead of allowing a release based on a green run badge.
    """
    run_id = run.get('id')
    attempt = run.get('run_attempt')
    if type(run_id) is not int or run_id <= 0 or type(attempt) is not int or attempt < 1:
        raise ValueError('Invalid trusted CI run identifier or attempt')
    response = fetch(f'/repos/{repo}/actions/runs/{run_id}/jobs?per_page=100')
    if not isinstance(response, dict):
        raise ValueError(f'{workflow}: malformed CI jobs response')
    jobs = response.get('jobs')
    total_count = response.get('total_count')
    expected = REQUIRED_CI_JOBS[workflow]
    if (type(total_count) is not int or not isinstance(jobs, list)
            or total_count != len(jobs) or total_count != len(expected)):
        return False, f'{workflow}: incomplete or unexpected required job matrix'
    observed = set()
    for job in jobs:
        if not isinstance(job, dict):
            return False, f'{workflow}: malformed job entry'
        name = job.get('name')
        if not isinstance(name, str) or name not in expected or name in observed:
            return False, f'{workflow}: missing, duplicate or unexpected CI job'
        observed.add(name)
        if (job.get('head_sha') != sha or job.get('run_id') != run_id
                or job.get('run_attempt') != attempt):
            return False, f'{workflow}/{name}: unrelated commit, run or attempt'
        if job.get('status') != 'completed' or job.get('conclusion') != 'success':
            return False, f"{workflow}/{name}: {job.get('status')}/{job.get('conclusion')}"
    if observed != expected:
        return False, f'{workflow}: required CI API matrix incomplete'
    return True, f'{workflow}: {len(observed)} same-SHA jobs passed'


def evaluate(repo, sha, tag, fetch, mode="release", trigger=None):
    if mode not in ("release", "play"):
        raise ValueError("Unknown publication gate mode")
    prefix = f'/repos/{repo}'
    main = fetch(prefix + '/git/ref/heads/main')
    if main['object']['sha'] != sha:
        return False, 'Commit is no longer the current main HEAD'
    completed_runs = []
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
        jobs_ready, jobs_reason = validate_ci_jobs(repo, sha, workflow, latest, fetch)
        if not jobs_ready:
            return False, jobs_reason
        completed_runs.append(latest)
    if mode == 'play' and trigger is not None:
        winner = max(completed_runs, key=completion_order)
        if (trigger.get('id'), trigger.get('run_attempt')) != (winner['id'], winner['run_attempt']):
            return False, 'Only the later completed required CI event authorizes automatic Play allocation'
    for endpoint in (() if mode == 'play' else ('git/ref/tags', 'releases/tags')):
        if fetch(f'{prefix}/{endpoint}/{tag}') is not None:
            return False, f'{tag} already exists; releases and assets are never overwritten'
    return True, 'Android build and all API29/35/36 jobs passed on the exact current main commit'


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
    # The gate reads the committed base; Play allocates its build override later.
    code = re.fullmatch(
        r'(?:providers\.gradleProperty\("quickcutTestVersionCode"\)\.orNull\?\.toInt\(\)[ \t]*\?:[ \t]*)?([1-9][0-9]*)',
        declarations['versionCode'])
    if not version or not code:
        raise ValueError('Unsupported source release version declaration')
    return version[1], code[1]


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=('release', 'play'), default='release')
    args = parser.parse_args()
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
    trigger = event['workflow_run'] if os.environ['GITHUB_EVENT_NAME'] == 'workflow_run' else None
    ready, reason = evaluate(repo, sha, tag, api_fetch, mode=args.mode, trigger=trigger)
    values = {'ready': str(ready).lower(), 'sha': sha, 'tag': tag,
              'version': version, 'version_code': code}
    if args.mode == 'play':
        values['target'] = play_target(event, os.environ['GITHUB_EVENT_NAME'])
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

