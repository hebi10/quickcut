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
                  event='push', status='completed', conclusion='success',
                  head_repository={'full_name': REPO}, repository={'full_name': REPO})
    record.update(changes)
    return record


class ReleaseVersionTest(unittest.TestCase):
    def test_actual_gradle_declaration(self):
        gradle = SCRIPT.parent.parent / 'app/build.gradle.kts'
        self.assertEqual(gate.parse_release_version(gradle.read_text()), ('0.4.11', '16'))

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
