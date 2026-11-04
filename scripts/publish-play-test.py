#!/usr/bin/env python3
"""Publish only existing QuickCut test tracks. Never retry a mutating request."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

PACKAGE = 'com.hebi.quickcut'
API_ROOT = 'https://androidpublisher.googleapis.com'
API_PATH = '/androidpublisher/v3/applications/' + PACKAGE


class APIError(RuntimeError):
    def __init__(self, status, reason='API_REQUEST_REJECTED'):
        self.status = status
        super().__init__(f'Google Play HTTP {status}: {reason}')


class TransportError(RuntimeError):
    pass


class PublisherAPI:
    def __init__(self, token):
        if not token or any(c.isspace() for c in token):
            raise ValueError('Missing or malformed PLAY_ACCESS_TOKEN')
        self.token = token

    def request(self, method, path, body=None, upload=None):
        if not path.startswith(('/edits', '/tracks/')) or '..' in path:
            raise ValueError('Refusing an unexpected Publisher API path')
        headers = {'Authorization': 'Bearer ' + self.token, 'Accept': 'application/json'}
        if upload:
            data = Path(upload).read_bytes()
            headers['Content-Type'] = 'application/octet-stream'
            url = API_ROOT + '/upload' + API_PATH + path + '?uploadType=media'
        else:
            data = json.dumps(body).encode('utf-8') if body is not None else None
            headers['Content-Type'] = 'application/json; charset=utf-8'
            url = API_ROOT + API_PATH + path
        request = Request(url, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=300 if upload else 60) as response:
                raw = response.read()
                decoded = json.loads(raw) if raw else {}
                if not isinstance(decoded, dict):
                    raise ValueError('Expected object API response')
                return decoded
        except HTTPError as error:
            # Never echo Google response bodies, URLs, headers, or credentials.
            reason = 'API_REQUEST_REJECTED'
            try:
                details = json.loads(error.read()).get('error', {}).get('details', [])
                if any(x.get('reason') == 'CHANGES_ALREADY_IN_REVIEW' for x in details):
                    reason = 'CHANGES_ALREADY_IN_REVIEW (existing review preserved; wait for it to complete)'
            except (ValueError, AttributeError):
                pass
            raise APIError(error.code, reason) from None
        except (URLError, TimeoutError, OSError):
            raise TransportError('Network failure; request outcome may be uncertain. No automatic retry was made.') from None
        except (ValueError, UnicodeError):
            raise TransportError('Invalid API response; request outcome may be uncertain. No automatic retry was made.') from None


def resolve_tracks(tracks, target, closed):
    if target not in ('internal', 'closed', 'both'):
        raise ValueError('Target must be internal, closed, or both')
    if (not re.fullmatch(r'[a-z][a-z0-9_-]{0,49}', closed)
            or closed in ('production', 'beta', 'internal', 'qa', 'open')):
        raise ValueError('Closed track must be an existing closed-test ID; production/open/internal IDs are forbidden')
    names = [t['track'] for t in tracks]
    if len(set(names)) != len(names):
        raise ValueError('Duplicate track IDs returned by Google Play')
    selected = []
    if target in ('internal', 'both'):
        aliases = [name for name in ('internal', 'qa') if name in names]
        if len(aliases) != 1:
            raise ValueError('Expected exactly one existing internal track (internal or qa)')
        selected.extend(aliases)
    if target in ('closed', 'both'):
        if closed not in names:
            raise ValueError('Configured closed-test track does not exist; configure QUICKCUT_PLAY_CLOSED_TRACK')
        selected.append(closed)
    return selected


def version_codes(track):
    return [int(code) for release in track.get('releases', []) for code in release.get('versionCodes', [])]


def completed(track, code):
    return any(release.get('status') == 'completed' and str(code) in release.get('versionCodes', [])
               for release in track.get('releases', []))


def observe(api, tracks, code):
    states = {}
    for name in tracks:
        try:
            releases = api.request('GET', '/tracks/' + quote(name, safe='') + '/releases').get('releases', [])
            states[name] = sorted(set(
                r.get('releaseLifecycleState', 'UNKNOWN') for r in releases
                if any(int(a['versionCode']) == code for a in r.get('activeArtifacts', []))))
        except (APIError, TransportError, ValueError, KeyError, TypeError):
            states[name] = ['UNAVAILABLE']
    return states


def publish(api, bundle, code, version, notes, target='closed', closed='alpha'):
    if code <= 0 or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError('Invalid source version')
    if not notes.strip() or len(notes) > 500:
        raise ValueError('Korean release notes must contain 1–500 characters')
    edit_id = api.request('POST', '/edits', {})['id']
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', edit_id):
        raise ValueError('Unexpected edit identifier')
    edit = '/edits/' + edit_id
    commit_attempted = False
    try:
        tracks = api.request('GET', edit + '/tracks').get('tracks', [])
        selected = resolve_tracks(tracks, target, closed)
        bundles = api.request('GET', edit + '/bundles').get('bundles', [])
        apks = api.request('GET', edit + '/apks').get('apks', [])
        bundle_codes = {int(b['versionCode']) for b in bundles}
        apk_codes = {int(a['versionCode']) for a in apks}
        all_codes = bundle_codes | apk_codes | {c for t in tracks for c in version_codes(t)}
        if all_codes and max(all_codes) > code:
            raise ValueError('A newer versionCode already exists in Play; increment the committed source versionCode')
        missing = [name for name in selected if not completed(next(t for t in tracks if t['track'] == name), code)]
        if not missing:
            outcome = 'unchanged'
            api.request('DELETE', edit)
        else:
            if code not in bundle_codes:
                if code in all_codes:
                    raise ValueError('versionCode already exists without a reusable bundle; increment the committed source versionCode')
                uploaded = api.request('POST', edit + '/bundles', upload=bundle)
                if int(uploaded['versionCode']) != code:
                    raise ValueError('Uploaded bundle versionCode does not match committed source')
            for name in missing:
                body = {'track': name, 'releases': [{'name': 'QuickCut ' + version,
                        'versionCodes': [str(code)], 'status': 'completed',
                        'releaseNotes': [{'language': 'ko-KR', 'text': notes}]}]}
                api.request('PUT', edit + '/tracks/' + quote(name, safe=''), body)
            api.request('POST', edit + ':validate')
            commit_attempted = True
            try:
                api.request('POST', edit + ':commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW')
                outcome = 'committed'
            except TransportError:
                # A response timeout can happen after Google applied the edit.
                # Observe independently; never blindly repeat the commit/upload.
                outcome = 'uncertain'
        states = observe(api, selected, code)
        published = all('RELEASE_LIFECYCLE_STATE_PUBLISHED' in s for s in states.values())
        return {'package': PACKAGE, 'version': version, 'version_code': code,
                'tracks': selected, 'edit_id': edit_id, 'outcome': outcome,
                'published': published, 'release_states': states}
    except Exception:
        if not commit_attempted:
            try:
                api.request('DELETE', edit)
            except (APIError, TransportError):
                pass
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--notes', type=Path, required=True)
    parser.add_argument('--target', choices=('internal', 'closed', 'both'), default='closed')
    parser.add_argument('--closed-track', default='alpha')
    parser.add_argument('--result', type=Path, required=True)
    args = parser.parse_args()
    if not args.bundle.is_file() or not args.bundle.stat().st_size:
        raise ValueError('Signed AAB is missing')
    result = publish(PublisherAPI(os.environ.get('PLAY_ACCESS_TOKEN', '')), args.bundle,
                     args.version_code, args.version, args.notes.read_text(encoding='utf-8').strip(),
                     args.target, args.closed_track)
    with args.bundle.open('rb') as stream:
        result['bundle_sha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
    args.result.parent.mkdir(parents=True, exist_ok=True)
    args.result.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
            summary.write('## Google Play test release\n\n```json\n' + json.dumps(result, indent=2) + '\n```\n\n')
            summary.write('Commit acceptance and tester availability are separate. Only PUBLISHED confirms availability.\n')
    if result['outcome'] == 'uncertain':
        raise TransportError('Commit outcome uncertain. Inspect the recorded lifecycle state before a manual rerun.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, APIError, TransportError, OSError) as error:
        print(f'::error::Play test release failed: {error}', file=sys.stderr)
        sys.exit(1)
