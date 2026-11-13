"""Read-only release-boundary tests for OPS-08."""
import importlib.util
import os
from pathlib import Path
import re
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("ops08_final_audit", Path(__file__).with_name("final_audit.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class FinalPolicyTests(unittest.TestCase):
    def test_checked_out_git_source_has_complete_sha(self):
        self.assertRegex(audit.verified_head(), r"^[0-9a-f]{40}$")

    def test_main_validation_is_not_claimed_for_pull_requests(self):
        with patch.dict(os.environ, {"GITHUB_REF": "refs/pull/123/merge"}):
            result = audit.audit()
        self.assertFalse(result["main_verified"])
        self.assertEqual(result["repository"], audit.REPOSITORY)

    def test_no_credential_or_publisher_action_in_final_audit(self):
        source = (ROOT / ".github/workflows/ops08-final-policy.yml").read_text(encoding="utf-8")
        for forbidden in ("id-token: write", "google-github-actions/auth@",
                          "PLAY_ACCESS_TOKEN:", "androidpublisher.googleapis.com",
                          "publish-play", "--mode deploy", "PLAY_SERVICE_ACCOUNT"):
            with self.subTest(value=forbidden):
                self.assertNotIn(forbidden, source)
        self.assertIn("permissions:\n  contents: read", source)

    def test_policy_changes_retrigger_exact_main_android_matrix(self):
        source = (ROOT / ".github/workflows/emulator-smoke.yml").read_text(encoding="utf-8")
        for path in (".github/workflows/ops07-safety.yml",
                     ".github/workflows/ops08-final-policy.yml",
                     "scripts/ops07/**", "scripts/ops08/**"):
            with self.subTest(path=path):
                self.assertEqual(source.count('      - "' + path + '"'), 2)
        self.assertIn("  push:\n    branches: [main]", source)
        self.assertIn("api: [29, 35, 36]", source)

    def test_dirty_audited_worktree_is_not_attributed_to_head_sha(self):
        fixed_sha = "a" * 40
        def fake_run(command, **kwargs):
            if command[:3] == ["git", "rev-parse", "HEAD"]:
                return audit.subprocess.CompletedProcess(command, 0, fixed_sha + "\n")
            if command[:3] == ["git", "status", "--porcelain=v1"]:
                return audit.subprocess.CompletedProcess(
                    command, 0, " M .github/workflows/play-listing-images.yml\n")
            raise AssertionError("Unexpected git command")
        with patch.object(audit.subprocess, "run", side_effect=fake_run):
            with patch.dict(os.environ, {"GITHUB_SHA": fixed_sha}):
                with self.assertRaisesRegex(RuntimeError, "Audited policy inputs differ"):
                    audit.verified_head()

    def test_audit_report_precedes_tests_and_preserves_failure_evidence(self):
        source = (ROOT / ".github/workflows/ops08-final-policy.yml").read_text(encoding="utf-8")
        self.assertLess(source.index("Verify exact checked-out source"),
                        source.index("Run OPS-07 regression tests"))
        self.assertIn("if: always()", source)
        self.assertIn("ops08-output/report.json", source)

    def test_required_policy_files_include_all_release_stages(self):
        files = set(audit.REQUIRED)
        for value in ("AGENTS.md", "scripts/ops07/audit.py",
                      ".github/workflows/release-build.yml",
                      ".github/workflows/play-internal-release.yml",
                      ".github/workflows/play-closed-release.yml",
                      "scripts/ops06/store_gate.py"):
            with self.subTest(path=value):
                self.assertIn(value, files)

    def test_report_explicitly_distinguishes_api_from_review(self):
        result = audit.audit()
        self.assertIs(result["play_api_called"], False)
        self.assertIs(result["play_uploaded"], False)
        self.assertIs(result["store_changed"], False)
        self.assertIn(result["status"], {"pass", "blocked"})
        self.assertTrue(isinstance(result["policy_hashes_sha256"], dict))


if __name__ == "__main__":
    unittest.main()
