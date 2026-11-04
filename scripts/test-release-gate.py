#!/usr/bin/env python3
"""Release authorization regression tests; API responses are supplied in memory."""
import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name('verify-release-gate.py')
SPEC = importlib.util.spec_from_file_location('release_gate', SCRIPT)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)

REPO = 'owner/quickcut'
SHA = 'a' * 40


def run_record(name, **changes):
    record = dict(id=10, run_number=10, name=name, head_sha=SHA, head_branch='main',
                  event='push', status='completed', conclusion='success', run_attempt=1, updated_at='2026-11-04T10:00:00Z',
                  head_repository={'full_name': REPO}, repository={'full_name': REPO})
    record.update(changes)
    return record


class ReleaseVersionTest(unittest.TestCase):
    def test_actual_gradle_declaration(self):
        gradle = SCRIPT.parent.parent / 'app/build.gradle.kts'
        self.assertEqual(gate.parse_release_version(gradle.read_text()), ('0.4.17', '23'))

    def test_literal_version_code(self):
        self.assertEqual(gate.parse_release_version('versionName = "0.4.11"\nversionCode = 16\n'), ('0.4.11', '16'))

    def test_supported_test_override_extracts_only_production_default(self):
        source = 'versionName = "0.4.11"\nversionCode = providers.gradleProperty("quickcutTestVersionCode").orNull?.toInt() ?: 42\n'
        self.assertEqual(gate.parse_release_version(source), ('0.4.11', '42'))

    def test_unsupported_or_ambiguous_declarations_fail_closed(self):
        for code in ('providers.gradleProperty("releaseVersionCode").orNull?.toInt() ?: 16',
                     'providers.gradleProperty("quickcutTestVersionCode").orNull?.toInt() ?: 16 + 1',
                     'providers.gradleProperty("quickcutTestVersionCode").get().toInt()',
                     '16 + 1', '0', '-1', '16\nversionCode = 17'):
            with self.subTest(code=code), self.assertRaises(ValueError):
                gate.parse_release_version('versionName = "0.4.11"\nversionCode = ' + code + '\n')
        for name in ('"0.4.11-dev"', 'releaseName', '"0.4.11"\nversionName = "0.4.12"'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                gate.parse_release_version('versionName = ' + name + '\nversionCode = 16\n')


class PlayTargetTest(unittest.TestCase):
    def test_automatic_release_targets_internal(self):
        self.assertEqual(gate.play_target({}, 'workflow_run'), 'internal')

    def test_automatic_release_cannot_select_closed(self):
        for target in ('closed', 'both', 'production'):
            with self.subTest(target=target), self.assertRaises(ValueError):
                gate.play_target({'inputs': {'target': target}}, 'workflow_run')

    def test_manual_release_defaults_internal_and_allows_explicit_closed(self):
        self.assertEqual(gate.play_target({}, 'workflow_dispatch'), 'internal')
        for target in ('internal', 'closed'):
            self.assertEqual(gate.play_target({'inputs': {'target': target}}, 'workflow_dispatch'), target)

    def test_unknown_events_and_targets_fail_closed(self):
        for event in ('push', 'pull_request', ''):
            with self.subTest(event=event), self.assertRaises(ValueError):
                gate.play_target({}, event)
        for target in ('production', 'both', '', None):
            with self.subTest(target=target), self.assertRaises(ValueError):
                gate.play_target({'inputs': {'target': target}}, 'workflow_dispatch')


class ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        self.responses = {
            f'/repos/{REPO}/git/ref/heads/main': {'object': {'sha': SHA}},
            f'/repos/{REPO}/git/ref/tags/v0.4.11': None,
            f'/repos/{REPO}/releases/tags/v0.4.11': None,
        }
        self.runs = {}
        for workflow, name in gate.REQUIRED_WORKFLOWS.items():
            self.runs[workflow] = [run_record(name)]
        self.calls = []

    def fetch(self, path):
        self.calls.append(path)
        for workflow, runs in self.runs.items():
            if f'/actions/workflows/{workflow}/runs?' in path:
                return {'workflow_runs': runs, 'total_count': len(runs)}
        return self.responses[path]

    def check(self):
        return gate.evaluate(REPO, SHA, 'v0.4.11', self.fetch)

    def test_both_exact_main_successes_authorize(self):
        self.assertTrue(self.check()[0])

    def test_different_sha_never_reuses_green_runs(self):
        self.runs['emulator-smoke.yml'][0]['head_sha'] = 'b' * 40
        self.assertFalse(self.check()[0])

    def test_pull_request_or_fork_success_does_not_authorize(self):
        for change in ({'event': 'pull_request'}, {'head_branch': 'feature'},
                       {'head_repository': {'full_name': 'fork/quickcut'}},
                       {'repository': {'full_name': 'fork/quickcut'}},
                       {'name': 'Some other workflow'}):
            with self.subTest(change=change):
                self.runs['android.yml'] = [run_record('Android build', **change)] if 'name' not in change else [run_record(change['name'])]
                self.assertFalse(self.check()[0])

    def test_pending_other_workflow_skips_without_polling(self):
        self.runs['emulator-smoke.yml'] = [run_record('Android emulator smoke', status='in_progress', conclusion=None)]
        ready, reason = self.check()
        self.assertFalse(ready)
        self.assertIn('in_progress', reason)
        self.assertEqual(len(self.calls), 3)

    def test_failure_is_explained(self):
        self.runs['emulator-smoke.yml'][0]['conclusion'] = 'failure'
        ready, reason = self.check()
        self.assertFalse(ready)
        self.assertIn('failure', reason)

    def test_newer_failed_or_pending_run_blocks_old_success(self):
        for status, conclusion in [('completed', 'failure'), ('queued', None)]:
            with self.subTest(status=status):
                self.runs['android.yml'] = [run_record('Android build'), run_record('Android build', id=11, run_number=11, status=status, conclusion=conclusion)]
                self.assertFalse(self.check()[0])

    def test_manual_main_smoke_is_allowed(self):
        self.runs['emulator-smoke.yml'][0]['event'] = 'workflow_dispatch'
        self.assertTrue(self.check()[0])

    def test_outdated_main_never_publishes(self):
        self.responses[f'/repos/{REPO}/git/ref/heads/main']['object']['sha'] = 'b' * 40
        self.assertFalse(self.check()[0])

    def test_existing_tag_or_release_never_overwrites(self):
        for endpoint in ('git/ref/tags', 'releases/tags'):
            with self.subTest(endpoint=endpoint):
                self.setUp()
                self.responses[f'/repos/{REPO}/{endpoint}/v0.4.11'] = {'id': 7}
                self.assertFalse(self.check()[0])

    def test_play_mode_allows_existing_github_release(self):
        self.responses[f'/repos/{REPO}/git/ref/tags/v0.4.11'] = {'id': 7}
        self.assertTrue(gate.evaluate(REPO, SHA, 'v0.4.11', self.fetch, mode='play')[0])
        self.assertFalse(any('/tags/' in path for path in self.calls))

    def test_play_mode_still_requires_exact_successful_current_main(self):
        self.runs['android.yml'][0]['conclusion'] = 'failure'
        self.assertFalse(gate.evaluate(REPO, SHA, 'v0.4.11', self.fetch, mode='play')[0])

    def test_only_later_completed_ci_event_authorizes_automatic_play(self):
        build = self.runs['android.yml'][0]
        emulator = self.runs['emulator-smoke.yml'][0]
        build.update(id=10, updated_at='2026-11-04T10:00:00Z')
        emulator.update(id=11, updated_at='2026-11-04T10:01:00Z')
        self.assertFalse(gate.evaluate(REPO, SHA, 'v0.4.11', self.fetch, mode='play', trigger=build)[0])
        self.assertTrue(gate.evaluate(REPO, SHA, 'v0.4.11', self.fetch, mode='play', trigger=emulator)[0])

    def test_completion_tie_uses_id_and_old_attempt_cannot_authorize(self):
        self.runs['android.yml'][0]['id'] = 10
        self.runs['emulator-smoke.yml'][0].update(id=11, run_attempt=2)
        winner = self.runs['emulator-smoke.yml'][0]
        self.assertTrue(gate.evaluate(REPO, SHA, 'v0.4.11', self.fetch, mode='play', trigger=winner)[0])
        self.assertFalse(gate.evaluate(REPO, SHA, 'v0.4.11', self.fetch, mode='play', trigger=dict(winner, run_attempt=1))[0])
        self.assertFalse(gate.evaluate(REPO, SHA, 'v0.4.11', self.fetch, mode='play', trigger=self.runs['android.yml'][0])[0])

    def test_invalid_completion_metadata_fails_closed(self):
        for field, value in (('updated_at', None), ('updated_at', '2026-02-30T00:00:00Z'),
                             ('run_attempt', None), ('run_attempt', 0)):
            self.setUp()
            self.runs['android.yml'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                gate.evaluate(REPO, SHA, 'v0.4.11', self.fetch, mode='play', trigger=self.runs['android.yml'][0])

    def test_unknown_gate_mode_fails_closed(self):
        with self.assertRaises(ValueError):
            gate.evaluate(REPO, SHA, 'v0.4.11', self.fetch, mode='production')

    def test_workflow_event_requires_same_repo_main_success(self):
        event = {'workflow_run': run_record('Android build')}
        self.assertEqual(gate.event_sha(event, 'workflow_run', REPO, '', ''), SHA)
        for change in ({'head_branch': 'feature'}, {'conclusion': 'failure'},
                       {'head_repository': {'full_name': 'fork/quickcut'}}, {'event': 'pull_request'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                gate.event_sha({'workflow_run': run_record('Android build', **change)}, 'workflow_run', REPO, '', '')

    def test_dispatch_rejects_non_main(self):
        self.assertEqual(gate.event_sha({}, 'workflow_dispatch', REPO, 'refs/heads/main', SHA), SHA)
        with self.assertRaises(ValueError):
            gate.event_sha({}, 'workflow_dispatch', REPO, 'refs/heads/feature', SHA)


if __name__ == '__main__':
    unittest.main()

