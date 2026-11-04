#!/usr/bin/env python3
import copy
import importlib.util
import io
import json
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-play-test.py'))
p = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p)


def track(name, code=None, status='completed'):
    return {'track': name, 'releases': [] if code is None else [{'versionCodes': [str(code)], 'status': status}]}


class FakeAPI:
    def __init__(self):
        self.tracks = [track('production'), track('beta'), track('alpha'), track('internal')]
        self.bundles = []
        self.apks = []
        self.calls = []
        self.fail = None
        self.summaries = {}

    def request(self, method, path, body=None, upload=None):
        self.calls.append((method, path, copy.deepcopy(body), upload))
        if self.fail and self.fail in path:
            raise p.APIError(400, 'CHANGES_ALREADY_IN_REVIEW')
        if path == '/edits':
            return {'id': 'edit-1'}
        if path.endswith('/tracks'):
            return {'tracks': copy.deepcopy(self.tracks)}
        if path.endswith('/bundles'):
            if upload:
                self.bundles.append({'versionCode': 23})
                return {'versionCode': 23}
            return {'bundles': self.bundles}
        if path.endswith('/apks'):
            return {'apks': self.apks}
        if method == 'PUT':
            self.tracks = [body if t['track'] == body['track'] else t for t in self.tracks]
            return body
        if path.endswith('/releases'):
            return {'releases': self.summaries.get(path.split('/')[-2], [])}
        return {}


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.api = FakeAPI()

    def publish(self, target='closed', closed='alpha'):
        return p.publish(self.api, Path('signed.aab'), 23, '0.4.17', '업데이트', target, closed)

    def test_new_bundle_uploads_once_validates_then_commits_preserving_review(self):
        result = self.publish('both')
        calls = self.api.calls
        self.assertEqual(len([x for x in calls if x[3]]), 1)
        updates = [x[2] for x in calls if x[0] == 'PUT']
        self.assertEqual({x['track'] for x in updates}, {'alpha', 'internal'})
        self.assertEqual(updates[0]['releases'][0]['releaseNotes'], [{'language': 'ko-KR', 'text': '업데이트'}])
        paths = [x[1] for x in calls]
        self.assertLess(paths.index('/edits/edit-1:validate'), next(i for i, x in enumerate(paths) if ':commit?' in x))
        self.assertIn('changesInReviewBehavior=ERROR_IF_IN_REVIEW', paths[next(i for i,x in enumerate(paths) if ':commit?' in x)])
        self.assertEqual(result['outcome'], 'committed')
        self.assertFalse(result['published'])

    def test_existing_bundle_promotes_without_upload(self):
        self.api.bundles = [{'versionCode': 23}]
        self.api.tracks[3] = track('internal', 23)
        self.publish()
        self.assertFalse(any(x[3] for x in self.api.calls))

    def test_already_completed_target_is_noop_without_commit(self):
        self.api.tracks[2] = track('alpha', 23)
        result = self.publish()
        self.assertEqual(result['outcome'], 'unchanged')
        self.assertFalse(any(x[0] == 'PUT' or x[3] or ':commit' in x[1] for x in self.api.calls))

    def test_draft_same_code_requires_promotion(self):
        self.api.tracks[2] = track('alpha', 23, 'draft')
        self.api.bundles = [{'versionCode': 23}]
        self.assertEqual(self.publish()['outcome'], 'committed')

    def test_partial_both_target_changes_only_missing_track(self):
        self.api.tracks[3] = track('internal', 23)
        self.api.bundles = [{'versionCode': 23}]
        self.publish('both')
        self.assertEqual([x[2]['track'] for x in self.api.calls if x[0]=='PUT'], ['alpha'])

    def test_internal_alias_comes_from_actual_tracks(self):
        self.api.tracks[3] = track('qa')
        self.publish('internal')
        self.assertEqual([x[2]['track'] for x in self.api.calls if x[0]=='PUT'], ['qa'])

    def test_ambiguous_or_absent_internal_alias_fails_closed(self):
        for tracks in ([track('alpha')], [track('internal'), track('qa'), track('alpha')]):
            self.api.tracks = tracks
            with self.assertRaises(ValueError):
                self.publish('internal')

    def test_forbidden_unknown_or_missing_tracks_never_update(self):
        for closed in ('production', 'beta', 'internal', 'qa', 'wear:alpha', '../alpha', 'unknown'):
            with self.subTest(closed=closed), self.assertRaises(ValueError):
                self.publish(closed=closed)
        for target in ('production', 'beta', 'alpha', ''):
            with self.subTest(target=target), self.assertRaises(ValueError):
                self.publish(target)
        self.assertFalse(any(x[0]=='PUT' or x[3] for x in self.api.calls))

    def test_newer_version_anywhere_blocks_stale_release(self):
        for source in ('bundle', 'apk', 'track'):
            self.setUp()
            if source == 'bundle': self.api.bundles = [{'versionCode': 24}]
            if source == 'apk': self.api.apks = [{'versionCode': 24}]
            if source == 'track': self.api.tracks[0] = track('production', 24)
            with self.subTest(source=source), self.assertRaises(ValueError):
                self.publish()
            self.assertFalse(any(x[0]=='PUT' or x[3] for x in self.api.calls))

    def test_existing_apk_code_is_not_overwritten_by_bundle(self):
        self.api.apks = [{'versionCode': 23}]
        with self.assertRaises(ValueError): self.publish()
        self.assertFalse(any(x[3] for x in self.api.calls))

    def test_validation_or_commit_review_conflict_is_failure_no_retry(self):
        for suffix in (':validate', ':commit'):
            self.setUp()
            self.api.fail = suffix
            with self.subTest(suffix=suffix), self.assertRaises(p.APIError): self.publish()
            self.assertLessEqual(len([x for x in self.api.calls if ':commit' in x[1]]), 1)

    def test_commit_timeout_reads_back_and_reports_uncertain_without_retry(self):
        original = self.api.request
        def request(method, path, body=None, upload=None):
            if ':commit?' in path:
                self.api.calls.append((method,path,body,upload))
                raise p.TransportError('Uncertain request outcome')
            return original(method,path,body,upload)
        self.api.request = request
        result = self.publish()
        self.assertEqual(result['outcome'], 'uncertain')
        self.assertFalse(result['published'])
        self.assertEqual(len([x for x in self.api.calls if ':commit?' in x[1]]), 1)
        self.assertTrue(any(x[1].endswith('/tracks/alpha/releases') for x in self.api.calls))

    def test_failed_upload_never_updates_or_commits(self):
        original = self.api.request
        def request(method, path, body=None, upload=None):
            if upload: raise p.APIError(409)
            return original(method,path,body,upload)
        self.api.request = request
        with self.assertRaises(p.APIError): self.publish()
        self.assertFalse(any(x[0] == 'PUT' or ':commit' in x[1] for x in self.api.calls))

    def test_published_requires_matching_artifact_and_lifecycle(self):
        self.api.summaries['alpha'] = [{'activeArtifacts': [{'versionCode': 23}], 'releaseLifecycleState': 'RELEASE_LIFECYCLE_STATE_PUBLISHED'}]
        self.assertTrue(self.publish()['published'])


class HTTPBoundaryTest(unittest.TestCase):
    def response(self, value):
        class Response(io.BytesIO):
            def __enter__(self): return self
            def __exit__(self, *args): self.close()
        return Response(json.dumps(value).encode())

    def test_short_lived_token_is_only_in_authorization_header(self):
        with patch.object(p, 'urlopen', return_value=self.response({'id': 'edit'})) as send:
            self.assertEqual(p.PublisherAPI('private-token').request('POST', '/edits', {}), {'id':'edit'})
        request = send.call_args.args[0]
        self.assertEqual(request.get_header('Authorization'), 'Bearer private-token')
        self.assertNotIn('private-token', request.full_url)
        self.assertNotIn(b'private-token', request.data)

    def test_http_error_does_not_expose_body_or_token(self):
        error = HTTPError('private-url', 403, 'private-token', {}, io.BytesIO(b'{"error":{"message":"private-token"}}'))
        with patch.object(p, 'urlopen', side_effect=error), self.assertRaises(p.APIError) as caught:
            p.PublisherAPI('private-token').request('POST','/edits',{})
        self.assertEqual(str(caught.exception), 'Google Play HTTP 403: API_REQUEST_REJECTED')

    def test_review_error_keeps_safe_actionable_reason(self):
        body = {'error': {'details': [{'reason':'CHANGES_ALREADY_IN_REVIEW'}]}}
        error = HTTPError('url',400,'error',{},io.BytesIO(json.dumps(body).encode()))
        with patch.object(p,'urlopen',side_effect=error), self.assertRaises(p.APIError) as caught:
            p.PublisherAPI('private-token').request('POST','/edits/e:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW')
        self.assertIn('existing review preserved',str(caught.exception))

    def test_network_error_is_sanitized_without_retry(self):
        with patch.object(p,'urlopen',side_effect=URLError('private-token')) as send, self.assertRaises(p.TransportError) as caught:
            p.PublisherAPI('private-token').request('POST','/edits',{})
        self.assertEqual(send.call_count,1)
        self.assertNotIn('private-token',str(caught.exception))

    def test_nonobject_api_response_is_rejected_as_uncertain(self):
        with patch.object(p,'urlopen',return_value=self.response([])), self.assertRaises(p.TransportError):
            p.PublisherAPI('private-token').request('POST','/edits',{})


if __name__ == '__main__':
    unittest.main()
