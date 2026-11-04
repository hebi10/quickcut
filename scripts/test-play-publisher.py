#!/usr/bin/env python3
import copy
import importlib.util
import hashlib
import io
import json
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from pathlib import Path
import unittest
import tempfile

SPEC = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-play-test.py'))
p = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p)


PAYLOAD = b'original signed bundle fixture'
DIGEST = hashlib.sha256(PAYLOAD).hexdigest()

def bundle(code=23):
    return {'versionCode': code, 'sha256': DIGEST}

def track(name, code=None, status='completed'):
    return {'track': name, 'releases': [] if code is None else [{'versionCodes': [str(code)], 'status': status}]}


class FakeAPI:
    def __init__(self):
        self.tracks = [track('production'), track('beta'), track('alpha'), track('internal')]
        self.bundles = []
        self.apks = []
        self.calls = []
        self.fail = None
        self.uploaded_code = 23
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
                uploaded = bundle(self.uploaded_code)
                self.bundles.append(uploaded)
                return uploaded
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
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.bundle = Path(temporary.name) / 'signed.aab'
        self.bundle.write_bytes(PAYLOAD)

    def publish(self, target='closed', closed='alpha'):
        return p.publish(self.api, self.bundle, 23, '0.4.17', '업데이트', target, closed)

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
        self.api.bundles = [bundle()]
        self.api.tracks[3] = track('internal', 23)
        self.publish()
        self.assertFalse(any(x[3] for x in self.api.calls))

    def test_already_completed_target_is_noop_without_commit(self):
        self.api.tracks[2] = track('alpha', 23)
        self.api.bundles = [bundle()]
        result = self.publish()
        self.assertEqual(result['outcome'], 'unchanged')
        self.assertFalse(any(x[0] == 'PUT' or x[3] or ':commit' in x[1] for x in self.api.calls))

    def test_draft_same_code_requires_promotion(self):
        self.api.tracks[2] = track('alpha', 23, 'draft')
        self.api.bundles = [bundle()]
        self.assertEqual(self.publish()['outcome'], 'committed')

    def test_partial_both_target_changes_only_missing_track(self):
        self.api.tracks[3] = track('internal', 23)
        self.api.bundles = [bundle()]
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


    def test_different_binary_same_completed_code_fails_before_any_mutation(self):
        self.api.bundles = [bundle()]
        self.api.tracks[2] = track('alpha', 23)
        self.bundle.write_bytes(b'different new app payload')
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            self.publish()
        self.assertFalse(any(c[3] or c[0] == 'PUT' or ':validate' in c[1] or ':commit' in c[1] for c in self.api.calls))

    def test_missing_malformed_or_duplicate_server_hash_never_reuses_code(self):
        for records in ([{'versionCode': 23}], [{'versionCode': 23, 'sha256': 'bad'}], [bundle(), bundle()]):
            self.setUp()
            self.api.bundles = records
            self.api.tracks[2] = track('alpha', 23)
            with self.subTest(records=records), self.assertRaises(ValueError):
                self.publish()
            self.assertFalse(any(c[3] or c[0] == 'PUT' for c in self.api.calls))

    def test_completed_track_without_reusable_bundle_is_rejected(self):
        self.api.tracks[2] = track('alpha', 23)
        with self.assertRaises(ValueError):
            self.publish()
        self.assertFalse(any(c[3] or c[0] == 'PUT' for c in self.api.calls))

    def test_upload_response_code_or_hash_mismatch_prevents_track_changes(self):
        original = self.api.request
        for response in (bundle(24), {'versionCode': 23}, {'versionCode': 23, 'sha256': '0' * 64}):
            self.api.calls.clear()
            def request(method, path, body=None, upload=None):
                if upload:
                    self.api.calls.append((method, path, body, upload))
                    return response
                return original(method, path, body, upload)
            self.api.request = request
            with self.subTest(response=response), self.assertRaises(ValueError):
                self.publish()
            self.assertFalse(any(c[0] == 'PUT' or ':validate' in c[1] or ':commit' in c[1] for c in self.api.calls))

    def test_result_records_actual_candidate_and_verified_server_hash(self):
        result = self.publish()
        self.assertEqual(result['bundle_sha256'], DIGEST)
        self.assertEqual(result['server_bundle_sha256'], DIGEST)


class AllocationTest(unittest.TestCase):
    def setUp(self):
        self.api = FakeAPI()

    def allocate(self, source=23, target='internal', closed='alpha'):
        return p.allocate_version_code(self.api, source, target, closed)

    def test_empty_play_inventory_uses_committed_base(self):
        result = self.allocate()
        self.assertEqual(result['source_version_code'], 23)
        self.assertEqual(result['observed_max_version_code'], 0)
        self.assertEqual(result['allocated_version_code'], 23)

    def test_maximum_from_all_tracks_apks_and_bundles_is_used(self):
        self.api.bundles = [bundle(28)]
        self.api.apks = [{'versionCode': 29}]
        self.api.tracks[0] = track('production', 31, 'draft')
        self.api.tracks[1] = track('beta', 30)
        result = self.allocate()
        self.assertEqual(result['allocated_version_code'], 32)
        self.assertEqual(result['source_version_code'], 23)
        self.assertEqual(result['observed_max_version_code'], 31)

    def test_source_base_can_exceed_play_and_date_style_codes_are_supported(self):
        self.api.bundles = [bundle(20)]
        self.assertEqual(self.allocate(25)['allocated_version_code'], 25)
        self.api.bundles = [bundle(2026110400)]
        self.assertEqual(self.allocate()['allocated_version_code'], 2026110401)

    def test_range_exhaustion_and_invalid_source_fail_before_any_mutation(self):
        for code in (0, -1, True, 23.5, '23x', 2100000001):
            with self.subTest(code=code), self.assertRaises(ValueError):
                self.allocate(code)
        self.assertEqual(self.api.calls, [])
        self.api.bundles = [bundle(2100000000)]
        with self.assertRaises(ValueError):
            self.allocate()
        self.assertEqual(sum(c[0] == 'DELETE' for c in self.api.calls), 1)

    def test_invalid_server_codes_are_not_silently_ignored(self):
        for code in (0, -1, True, 23.5, '23x', 2100000001):
            self.api = FakeAPI()
            self.api.apks = [{'versionCode': code}]
            with self.subTest(code=code), self.assertRaises(ValueError):
                self.allocate()
            self.assertEqual(sum(c[0] == 'DELETE' for c in self.api.calls), 1)

    def test_allocator_only_reads_inventory_and_deletes_edit(self):
        self.allocate()
        self.assertEqual([(c[0], c[1]) for c in self.api.calls], [
            ('POST', '/edits'), ('GET', '/edits/edit-1/tracks'),
            ('GET', '/edits/edit-1/bundles'), ('GET', '/edits/edit-1/apks'), ('DELETE', '/edits/edit-1')])

    def test_omitted_empty_google_list_field_is_supported(self):
        original = self.api.request
        def request(method, path, body=None, upload=None):
            response = original(method, path, body, upload)
            return {} if path.endswith('/apks') or path.endswith('/bundles') else response
        self.api.request = request
        self.api.tracks[3] = track('internal', 23)
        self.assertEqual(self.allocate()['allocated_version_code'], 24)

    def test_present_malformed_inventory_is_rejected_and_edit_deleted(self):
        for value in (None, {}, 'bad', [1]):
            self.api = FakeAPI()
            original = self.api.request
            def request(method, path, body=None, upload=None):
                response = original(method, path, body, upload)
                return {'apks': value} if path.endswith('/apks') else response
            self.api.request = request
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.allocate()
            self.assertEqual(sum(c[0] == 'DELETE' for c in self.api.calls), 1)

    def test_inventory_error_cleans_up_without_retry(self):
        self.api.fail = '/bundles'
        with self.assertRaises(p.APIError):
            self.allocate()
        self.assertEqual(sum(c[0] == 'DELETE' for c in self.api.calls), 1)
        self.assertEqual(sum(c[1].endswith('/bundles') for c in self.api.calls), 1)

    def test_unknown_closed_track_fails_before_build_and_edit_is_deleted(self):
        with self.assertRaises(ValueError):
            self.allocate(target='closed', closed='missing')
        self.assertEqual(sum(c[0] == 'DELETE' for c in self.api.calls), 1)


    def test_allocation_and_publication_use_new_code_without_changing_source_base(self):
        self.api.bundles = [bundle(23)]
        plan = self.allocate()
        self.assertEqual(plan['allocated_version_code'], 24)
        self.api.uploaded_code = 24
        with tempfile.TemporaryDirectory() as temporary:
            candidate = Path(temporary) / 'new.aab'
            candidate.write_bytes(PAYLOAD)
            result = p.publish(self.api, candidate, plan['allocated_version_code'], '0.4.17', '업데이트')
        self.assertEqual(result['version_code'], 24)
        self.assertEqual(plan['source_version_code'], 23)
        self.assertEqual([c[2]['releases'][0]['versionCodes'] for c in self.api.calls if c[0] == 'PUT'], [['24']])

    def test_external_same_code_collision_is_rejected_without_reallocation(self):
        self.api.bundles = [bundle(23)]
        allocated = self.allocate()['allocated_version_code']
        self.api.bundles.append({'versionCode': allocated, 'sha256': '0' * 64})
        self.api.tracks[3] = track('internal', allocated)
        with tempfile.TemporaryDirectory() as temporary:
            candidate = Path(temporary) / 'new.aab'
            candidate.write_bytes(PAYLOAD)
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                p.publish(self.api, candidate, allocated, '0.4.17', '업데이트')
        self.assertFalse(any(c[3] or c[0] == 'PUT' for c in self.api.calls))

    def test_highest_permitted_code_can_be_allocated(self):
        self.api.bundles = [bundle(2099999999)]
        self.assertEqual(self.allocate()['allocated_version_code'], 2100000000)


class VersionPlanTest(unittest.TestCase):
    def plan(self):
        allocation = p.allocate_version_code(FakeAPI(), 23)
        allocation.update(observed_max_version_code=23, allocated_version_code=24)
        return p.version_plan(allocation, '0.4.17', 'a' * 40, 'b' * 40)

    def test_provenance_preserves_source_base_and_actual_build_override(self):
        plan = self.plan()
        self.assertEqual(plan['source_version_code'], 23)
        self.assertEqual(plan['allocated_version_code'], 24)
        self.assertEqual(plan['source_sha'], 'a' * 40)
        self.assertEqual(plan['source_tree'], 'b' * 40)
        self.assertEqual(plan['version_name'], '0.4.17')
        self.assertEqual(plan['gradle_property'], 'quickcutTestVersionCode=24')

    def test_inconsistent_allocation_or_source_identity_is_rejected(self):
        for field, value in (('source_version_code', 25), ('allocated_version_code', 25),
                             ('observed_max_version_code', -1), ('package', 'com.other.app'), ('gradle_property', 'quickcutTestVersionCode=23')):
            plan = self.plan()
            plan[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                p.version_plan(plan, '0.4.17', 'a' * 40, 'b' * 40)
        for sha in ('main', 'a' * 39, 'A' * 40):
            with self.subTest(sha=sha), self.assertRaises(ValueError):
                p.version_plan(self.plan(), '0.4.17', sha, 'b' * 40)

    def test_cli_writes_provenance_and_allocated_environment_without_changing_source(self):
        spec = importlib.util.spec_from_file_location('allocator', Path(__file__).with_name('allocate-play-version.py'))
        allocator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(allocator)
        api = FakeAPI()
        api.bundles = [bundle(23)]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            event = root / 'event.json'
            event.write_text('{}')
            result = root / 'plan.json'
            environment = root / 'env'
            argv = ['allocate-play-version.py', '--source-version-code', '23', '--version', '0.4.17',
                    '--source-sha', 'a' * 40, '--source-tree', 'b' * 40, '--target', 'internal', '--result', str(result)]
            with patch.object(allocator.publisher, 'PublisherAPI', return_value=api), \
                    patch.object(allocator.sys, 'argv', argv), \
                    patch.object(allocator.os, 'environ', {'GITHUB_EVENT_NAME': 'workflow_run',
                        'GITHUB_EVENT_PATH': str(event), 'GITHUB_ENV': str(environment)}), \
                    patch('sys.stdout', new=io.StringIO()):
                allocator.main()
            plan = json.loads(result.read_text())
            self.assertEqual((plan['source_version_code'], plan['allocated_version_code']), (23, 24))
            self.assertEqual(environment.read_text(), 'RELEASE_VERSION_CODE=24\n')

    def test_publisher_rejects_wrong_provenance_before_api_creation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            candidate = root / 'signed.aab'
            candidate.write_bytes(PAYLOAD)
            plan = self.plan()
            plan['bundle_sha256'] = DIGEST
            path = root / 'plan.json'
            path.write_text(json.dumps(plan))
            argv = ['publish-play-test.py', '--bundle', str(candidate), '--version', '0.4.17',
                    '--version-code', '24', '--notes', 'notes.md', '--result', 'result.json', '--version-plan', str(path)]
            with patch.object(p, 'PublisherAPI') as client, patch.object(p.sys, 'argv', argv), \
                    patch.object(p.os, 'environ', {'RELEASE_SHA': 'c' * 40}):
                with self.assertRaisesRegex(ValueError, 'recorded source/version plan'):
                    p.main()
            client.assert_not_called()


class TargetAuthorizationTest(unittest.TestCase):
    def test_internal_is_default_for_publisher_and_cli(self):
        api = FakeAPI()
        with tempfile.TemporaryDirectory() as temporary:
            candidate = Path(temporary) / 'signed.aab'
            candidate.write_bytes(PAYLOAD)
            result = p.publish(api, candidate, 23, '0.4.17', '업데이트')
        self.assertEqual(result['tracks'], ['internal'])
        with patch.object(p, 'PublisherAPI'), patch.object(p, 'publish') as publish, \
                patch.object(Path, 'is_file', return_value=True), \
                patch.object(Path, 'stat') as stat, \
                patch.object(Path, 'read_text', return_value='업데이트'), \
                patch.object(p.sys, 'argv', ['publish-play-test.py', '--bundle', 'signed.aab',
                    '--version', '0.4.17', '--version-code', '23', '--notes', 'notes.md', '--result', 'result.json']), \
                patch.object(p.os, 'environ', {}):
            stat.return_value.st_size = 1
            publish.side_effect = RuntimeError('stop before writing result')
            with self.assertRaisesRegex(RuntimeError, 'stop before writing result'):
                p.main()
        self.assertEqual(publish.call_args.args[-2], 'internal')

    def test_closed_requires_matching_explicit_manual_input(self):
        for target in ('closed', 'both'):
            p.authorize_target(target, 'workflow_dispatch', {'inputs': {'target': target}})
            for event, inputs in (('workflow_run', {'target': target}), ('push', {'target': target}),
                                  ('workflow_dispatch', {}), ('workflow_dispatch', {'target': 'internal'}), ('', {})):
                with self.subTest(target=target, event=event, inputs=inputs), self.assertRaises(ValueError):
                    p.authorize_target(target, event, {'inputs': inputs})

    def test_closed_cli_rejects_automatic_context_before_any_api_call(self):
        with patch.object(p, 'PublisherAPI') as client, \
                patch.object(p.sys, 'argv', ['publish-play-test.py', '--bundle', 'signed.aab',
                    '--version', '0.4.17', '--version-code', '23', '--notes', 'notes.md',
                    '--result', 'result.json', '--target', 'closed']), \
                patch.object(p.os, 'environ', {'GITHUB_EVENT_NAME': 'workflow_run'}):
            with self.assertRaisesRegex(ValueError, 'explicit matching workflow_dispatch'):
                p.main()
        client.assert_not_called()

    def test_internal_needs_no_closed_authorization(self):
        for event in ('workflow_run', 'workflow_dispatch', ''):
            p.authorize_target('internal', event, {})


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
