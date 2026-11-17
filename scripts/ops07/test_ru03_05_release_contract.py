"""RU-03/04/05: offline contracts for three independent Play release stages."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


class ReleaseStageContractTests(unittest.TestCase):
    def test_ops03_is_manual_build_only(self):
        workflow = (WORKFLOWS / "release-build.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("expected_main_sha:", workflow)
        self.assertIn("batch_id:", workflow)
        self.assertIn("final_batch_approved:", workflow)
        self.assertNotIn("google-github-actions/auth@", workflow)
        self.assertNotIn("androidpublisher.googleapis.com", workflow)
        self.assertNotIn('inputs[mode]=deploy', workflow)
        self.assertNotIn('inputs[deploy]=true', workflow)

    def test_ops04_consumes_exact_existing_aab(self):
        workflow = (WORKFLOWS / "play-internal-release.yml").read_text(encoding="utf-8")
        verifier = (ROOT / "scripts/ops04/internal.py").read_text(encoding="utf-8")
        for field in ("expected_main_sha:", "build_run_id:", "artifact_name:",
                      "aab_sha256:", "version_code:", "version_name:"):
            with self.subTest(field=field):
                self.assertIn(field, workflow)
        self.assertIn("actions/download-artifact@", workflow)
        self.assertIn("run-id: ${{ inputs.build_run_id }}", workflow)
        self.assertIn("python3 scripts/ops04/internal.py verify", workflow)
        self.assertIn('inputs.mode == \'publish\'', workflow)
        self.assertIn('AAB_SHA256', verifier)
        self.assertIn('Expected exactly one AAB', verifier)
        self.assertIn('Downloaded AAB SHA-256 differs', verifier)
        self.assertIn('AAB applicationId, versionCode or versionName mismatch', verifier)
        self.assertIn('play_uploaded": False', verifier)

    def test_ops05_promotes_without_binary_upload(self):
        workflow = (WORKFLOWS / "play-closed-release.yml").read_text(encoding="utf-8")
        promoter = (ROOT / "scripts/ops05/promotion.py").read_text(encoding="utf-8")
        for field in ("expected_main_sha:", "version_code:", "aab_sha256:",
                      "closed_track:", "final_batch_approved:", "approval_phrase:"):
            with self.subTest(field=field):
                self.assertIn(field, workflow)
        self.assertIn('inputs.mode == \'promote\'', workflow)
        self.assertIn("PROMOTE_VERIFIED_INTERNAL_VERSION", promoter)
        self.assertIn('Source internal or destination closed track does not exist', promoter) if "Source internal or destination closed track does not exist" in promoter else self.assertIn("Source internal track does not exist", promoter)
        self.assertIn('aab_rebuilt": False', promoter)
        self.assertNotIn("bundleRelease", promoter)
        self.assertNotIn("/bundles:upload", promoter)
        self.assertNotIn("assembleRelease", promoter)


if __name__ == "__main__":
    unittest.main()
