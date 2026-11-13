"""Regression fixtures for fail-closed release-authorization parsing."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ops07_regression_audit", Path(__file__).with_name("audit.py"))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def publish_on(event, command, job="sync", quoted=False):
    header = '"jobs":' if quoted else "jobs:"
    return (event + "\n" + header + "\n  " + job + ":\n"
            "    runs-on: ubuntu-latest\n    steps:\n      - run: " + command + "\n")


def store_workflow(guard=True, guard_run=None, pre_auth="", before_guard="", failure="", guard_if=""):
    approval = ("      - name: Verify immutable approved batch\n"
                + ("        if: " + guard_if + "\n" if guard_if else "")
                + ("        continue-on-error: " + failure + "\n" if failure else "")
                + "        run: " + (guard_run or "python3 scripts/ops06/store_gate.py apply") + "\n") if guard else ""
    return ("on:\n  workflow_dispatch:\njobs:\n  upload:\n"
            "    if: github.event_name == 'workflow_dispatch'\n"
            "    runs-on: ubuntu-latest\n    steps:\n"
            + before_guard + approval + pre_auth
            + "      - uses: google-github-actions/auth@v3\n"
            + "      - run: python3 scripts/play/listing_images.py --mode upload\n")


class FailClosedRegressionTests(unittest.TestCase):
    def test_inline_comment_and_quoted_jobs_cannot_hide_push(self):
        text = publish_on('on: [push, workflow_dispatch] # release', 
                          "python3 scripts/release/publisher.py deploy", quoted=True)
        self.assertTrue(audit.automatic_mutations("play.yml", text))
        self.assertEqual(audit.triggers(text), {"push", "workflow_dispatch"})
        self.assertIn("sync", audit.jobs(text))

    def test_flow_job_mapping_fails_closed(self):
        text = ('on: push\njobs: {upload: {runs-on: ubuntu-latest,'
                ' steps: [{run: "python3 scripts/release/publisher.py deploy"}]}}\n')
        self.assertTrue(audit.automatic_mutations("play.yml", text))
        self.assertIn("__unparsed_jobs__", audit.jobs(text))

    def test_actual_mobile_and_icon_publishers_without_mode(self):
        for command in ("python3 scripts/publish-mobile-play.py --aab release.aab",
                        "python3 scripts/play/store_icon.py --file icon.png"):
            with self.subTest(command=command):
                self.assertTrue(audit.automatic_mutations("other.yml", publish_on(
                    "on:\n  push:", command)))

    def test_dynamic_store_filename_requires_approval(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "renamed-assets.yml").write_text(store_workflow(guard=False), encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                self.assertTrue(any("STORE_GUARD" in issue for issue in audit.store_guard_findings()))

    def test_store_guard_must_execute_and_stop_on_failure(self):
        for kwargs in (
            {"guard_if": "false"},
            {"failure": "true"},
            {"failure": "$" + "{{ true }}"},
            {"guard_run": 'echo "python3 scripts/ops06/store_gate.py apply"'},
            {"guard_run": "python3 scripts/ops06/store_gate.py apply || true"},
        ):
            with self.subTest(kwargs=kwargs), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                folder = root / ".github/workflows"
                folder.mkdir(parents=True)
                (folder / "play-listing-images.yml").write_text(
                    store_workflow(**kwargs), encoding="utf-8")
                with patch.object(audit, "ROOT", root):
                    issues = audit.store_guard_findings()
                self.assertTrue(any("STORE_OPTIONAL" in item for item in issues), issues)

    def test_direct_play_edit_before_store_guard_blocks(self):
        before = ("      - run: curl -X POST "
                  "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/example/edits\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "store.yml").write_text(store_workflow(before_guard=before),
                                               encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_ORDER" in item for item in issues), issues)

    def test_release_approval_atom_must_be_unconditionally_required(self):
        safe = "github.event_name == 'workflow_dispatch' && inputs.ops01_release_approved == true"
        unsafe = "github.event_name == 'workflow_dispatch' && (inputs.ops01_release_approved == true || true)"
        predicate = "inputs.ops01_release_approved == true"
        self.assertIn(predicate, audit._guaranteed_atoms(safe))
        self.assertNotIn(predicate, audit._guaranteed_atoms(unsafe))

    def test_chained_publish_scripts_and_image_only_upload(self):
        for script in (
            "python3 scripts/play/preflight.py auth && python3 scripts/play/publisher.py deploy",
            "python3 scripts/play/preflight.py auth; python3 scripts/release/play_closed.py deploy",
            "python3 scripts/play/images_only.py --track internal",
        ):
            with self.subTest(script=script):
                self.assertTrue(audit.automatic_mutations(
                    "custom.yml", publish_on("on:\n  push:", script)))

    def test_id_first_upload_before_approval_is_blocked(self):
        before = ("      - id: early_upload\n"
                  "        run: python3 scripts/play/listing_images.py --mode upload\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "renamed-store.yml").write_text(
                store_workflow(before_guard=before), encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_ORDER" in issue for issue in issues), issues)

    def test_downstream_always_cannot_bypass_approval_failure(self):
        source = store_workflow().replace(
            "      - run: python3 scripts/play/listing_images.py --mode upload",
            "      - name: publish regardless\n"
            "        if: always()\n"
            "        run: python3 scripts/play/listing_images.py --mode upload")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "renamed-assets.yml").write_text(source, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_BYPASS" in issue for issue in issues), issues)

    def test_approval_is_not_itself_a_play_mutation(self):
        source = ("on:\n  workflow_dispatch:\njobs:\n  upload:\n"
                  "    if: github.event_name == 'workflow_dispatch'\n"
                  "    runs-on: ubuntu-latest\n    steps:\n"
                  "      - name: authorize either approved listing mode\n"
                  "        if: needs.source.outputs.mode == 'listing' || needs.source.outputs.mode == 'reconcile'\n"
                  "        run: python3 scripts/ops06/store_gate.py apply\n"
                  "      - name: upload listing\n"
                  "        if: needs.source.outputs.mode == 'listing'\n"
                  "        run: python3 scripts/release/listing.py apply --phone-only\n"
                  "      - name: reconcile listing\n"
                  "        if: needs.source.outputs.mode == 'reconcile'\n"
                  "        run: python3 scripts/release/listing.py apply --reconcile-brand\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "play-listing.yml").write_text(source, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertEqual(issues, [])

    def test_removing_both_internal_provenance_guards_fails(self):
        workflow = ("on:\n  workflow_dispatch:\njobs:\n"
                    "  verify:\n    runs-on: ubuntu-latest\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py verify\n"
                    "  publish:\n    runs-on: ubuntu-latest\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py verify\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          workflow if path == ".github/workflows/play-internal-release.yml" else None):
            findings = audit.findings()
        self.assertTrue(any("PROVENANCE" in item for item in findings), findings)





class SharedHardeningRegressionTests(unittest.TestCase):
    def test_if_first_and_folded_step_conditions(self):
        self.assertEqual(audit._step_condition(
            "      - if: false\n        run: echo skipped\n"), "false")
        self.assertEqual(audit._step_condition(
            "      - if: >-\n          always()\n        run: echo unsafe\n"),
            "always()")
        self.assertEqual(audit._step_condition(
            "      - name: release\n        if: >-\n          inputs.mode == 'publish'\n"
            "        run: echo release\n"), "inputs.mode == 'publish'")

    def test_chained_store_mutation_requires_approval(self):
        workflow = store_workflow(guard=False).replace(
            "python3 scripts/play/listing_images.py --mode upload",
            "python3 scripts/ops07/preflight.py check&&python3 scripts/play/listing_images.py --mode upload")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "renamed-store.yml").write_text(workflow, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_GUARD" in issue for issue in issues), issues)

    def test_run_first_approval_and_masked_pipeline(self):
        valid = store_workflow().replace(
            "      - name: Verify immutable approved batch\n"
            "        run: python3 scripts/ops06/store_gate.py apply\n",
            "      - run: python3 scripts/ops06/store_gate.py apply\n")
        bad = store_workflow(guard_run="python3 scripts/ops06/store_gate.py apply | tee approval.log")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            filename = folder / "play-listing-images.yml"
            with patch.object(audit, "ROOT", root):
                filename.write_text(valid, encoding="utf-8")
                self.assertEqual(audit.store_guard_findings(), [])
                filename.write_text(bad, encoding="utf-8")
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_OPTIONAL" in issue for issue in issues), issues)

    def test_provenance_guards_must_not_ignore_failed_checks(self):
        workflow = ("on:\n  workflow_dispatch:\njobs:\n"
                    "  verify:\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py guard || true\n"
                    "  publish:\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py guard || true\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          workflow if path == ".github/workflows/play-internal-release.yml"
                          else None):
            issues = audit.findings()
        self.assertTrue(any("PROVENANCE" in issue for issue in issues), issues)

    def test_compound_automatic_publisher_is_detected(self):
        workflow = publish_on("on:\n  push:",
            "python3 scripts/play/preflight.py auth&&python3 scripts/play/publisher.py deploy")
        self.assertTrue(audit.automatic_mutations("custom.yml", workflow))



class FailureBoundaryRegressionTests(unittest.TestCase):
    def test_step_leading_ignore_errors_rejects_store_approval(self):
        workflow = store_workflow().replace(
            "      - name: Verify immutable approved batch\n",
            "      - continue-on-error: true\n"
            "        name: Verify immutable approved batch\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "play-listing-images.yml").write_text(workflow, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_OPTIONAL" in item for item in issues), issues)

    def test_downstream_auth_cannot_ignore_failed_provenance(self):
        workflow = ("on:\n  workflow_dispatch:\njobs:\n"
                    "  verify:\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py guard\n"
                    "  publish:\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py guard\n"
                    "      - name: Unsafe Play authentication\n"
                    "        if: always()\n"
                    "        uses: google-github-actions/auth@v3\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          workflow if path == ".github/workflows/play-internal-release.yml"
                          else None):
            issues = audit.findings()
        self.assertTrue(any("PROVENANCE_BYPASS" in item for item in issues), issues)



class InterpreterOptionsRegressionTests(unittest.TestCase):
    def test_unbuffered_python_publisher_is_not_exempt(self):
        workflow = publish_on(
            "on:\n  push:",
            "python3 -u scripts/play/publisher.py deploy --bundle release.aab")
        self.assertTrue(audit.automatic_mutations("custom.yml", workflow))

    def test_unbuffered_store_publisher_requires_approval(self):
        workflow = store_workflow(guard=False).replace(
            "python3 scripts/play/listing_images.py --mode upload",
            "python3 -u scripts/play/listing_images.py --mode upload")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "renamed-assets.yml").write_text(workflow, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_GUARD" in item for item in issues), issues)



class FlexibleJobConditionRegressionTests(unittest.TestCase):
    def test_four_space_yaml_nesting_respects_manual_gate(self):
        workflow = ("on:\n  push:\n  workflow_dispatch:\njobs:\n"
                    "    sync:\n        runs-on: ubuntu-latest\n"
                    "        if: github.event_name == 'workflow_dispatch'\n"
                    "        steps:\n          - run: python3 scripts/play/publisher.py deploy\n")
        self.assertEqual(audit.automatic_mutations("play.yml", workflow), [])

    def test_quoted_job_conditions_are_unwrapped(self):
        workflow = ("on:\n  push:\n  workflow_dispatch:\njobs:\n"
                    "  sync:\n    runs-on: ubuntu-latest\n"
                    "    if: \"github.event_name == 'workflow_dispatch'\"\n"
                    "    steps:\n      - run: python3 scripts/play/publisher.py deploy\n")
        self.assertEqual(audit.automatic_mutations("play.yml", workflow), [])
        unsafe = workflow.replace(
            "github.event_name == 'workflow_dispatch'",
            "github.event_name == 'workflow_dispatch' || true")
        self.assertTrue(audit.automatic_mutations("play.yml", unsafe))


class ApprovalAndListRegressionTests(unittest.TestCase):
    def test_store_approval_and_list_must_stop_publication(self):
        source = store_workflow(
            guard_run="|\n"
                      "          python3 scripts/ops06/store_gate.py apply && echo approved\n"
                      "          echo finished")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "play-listing-images.yml").write_text(source, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_OPTIONAL" in issue for issue in issues), issues)

    def test_provenance_guard_and_list_must_stop_publication(self):
        workflow = ("on:\n  workflow_dispatch:\njobs:\n"
                    "  verify:\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py guard\n"
                    "  publish:\n    steps:\n"
                    "      - run: |\n"
                    "          python3 scripts/ops04/internal.py guard && echo approved\n"
                    "          echo finished\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          workflow if path == ".github/workflows/play-internal-release.yml"
                          else None):
            issues = audit.findings()
        self.assertTrue(any("PROVENANCE:" in issue for issue in issues), issues)


class NegatedSuccessBypassTests(unittest.TestCase):
    def test_store_auth_and_upload_must_not_run_when_approval_failed(self):
        condition = "$" + "{{ !success() }}"
        source = store_workflow()
        source = source.replace(
            "      - uses: google-github-actions/auth@v3\n",
            "      - if: " + condition + "\n"
            "        uses: google-github-actions/auth@v3\n")
        source = source.replace(
            "      - run: python3 scripts/play/listing_images.py --mode upload\n",
            "      - if: " + condition + "\n"
            "        run: python3 scripts/play/listing_images.py --mode upload\n")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "play-listing-images.yml").write_text(source, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_BYPASS" in issue for issue in issues), issues)

    def test_source_guard_failure_cannot_trigger_authentication(self):
        condition = "$" + "{{ !success() }}"
        workflow = ("on:\n  workflow_dispatch:\njobs:\n"
                    "  verify:\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py guard\n"
                    "  publish:\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py guard\n"
                    "      - if: " + condition + "\n"
                    "        uses: google-github-actions/auth@v3\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          workflow if path == ".github/workflows/play-internal-release.yml"
                          else None):
            issues = audit.findings()
        self.assertTrue(any("PROVENANCE_BYPASS" in issue for issue in issues), issues)


class ShellAndProvenanceOrderRegressionTests(unittest.TestCase):
    def test_custom_bash_without_errexit_cannot_hide_store_failure(self):
        source = store_workflow().replace(
            "        run: python3 scripts/ops06/store_gate.py apply\n",
            "        shell: bash {0}\n"
            "        run: |\n"
            "          python3 scripts/ops06/store_gate.py apply\n"
            "          echo finished\n", 1)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "play-listing-images.yml").write_text(source, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any(issue.startswith(("STORE_SHELL:", "STORE_OPTIONAL:"))
                            for issue in issues), issues)

    def test_custom_bash_source_guard_must_fail_closed(self):
        workflow = ("on:\n  workflow_dispatch:\njobs:\n"
                    "  verify:\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py guard\n"
                    "        shell: bash {0}\n"
                    "  publish:\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py guard\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          workflow if path == ".github/workflows/play-internal-release.yml"
                          else None):
            issues = audit.findings()
        self.assertTrue(any("PROVENANCE_SHELL" in issue for issue in issues), issues)

    def test_publication_must_not_precede_source_guard(self):
        workflow = ("on:\n  workflow_dispatch:\njobs:\n"
                    "  verify:\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py guard\n"
                    "  publish:\n    steps:\n"
                    "      - run: python3 scripts/play/publisher.py deploy --bundle release.aab\n"
                    "      - run: python3 scripts/ops04/internal.py guard\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          workflow if path == ".github/workflows/play-internal-release.yml"
                          else None):
            issues = audit.findings()
        self.assertTrue(any("PROVENANCE: OPS-04 publish cannot publish before" in issue
                            for issue in issues), issues)


class EffectiveShellScopeRegressionTests(unittest.TestCase):
    def test_unrelated_successful_lint_shell_does_not_require_store_approval(self):
        workflow = ("on:\n  push:\njobs:\n  lint:\n"
                    "    runs-on: ubuntu-latest\n"
                    "    steps:\n"
                    "      - run: echo lint\n"
                    "        shell: bash --noprofile --norc -e -o pipefail {0}\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "lint-only.yml").write_text(workflow, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                self.assertEqual(audit.store_guard_findings(), [])

    def test_fail_fast_custom_bash_shell_is_allowed_for_standalone_approval(self):
        source = store_workflow().replace(
            "        run: python3 scripts/ops06/store_gate.py apply\n",
            "        shell: bash --noprofile --norc -e -o pipefail {0}\n"
            "        run: python3 scripts/ops06/store_gate.py apply\n", 1)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "play-listing-images.yml").write_text(source, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                self.assertEqual(audit.store_guard_findings(), [])


class InlineTriggerNestingRegressionTests(unittest.TestCase):
    def test_nested_manual_dispatch_inputs_are_not_events(self):
        workflow = (
            "on: {workflow_dispatch: {inputs: {apply: {type: boolean, default: false}}}}\n"
            "jobs:\n  publish:\n    runs-on: ubuntu-latest\n"
            "    steps:\n      - run: python3 scripts/play/publisher.py deploy\n")
        self.assertEqual(audit.triggers(workflow), {"workflow_dispatch"})
        self.assertEqual(audit.automatic_mutations("play-release.yml", workflow), [])

    def test_mixed_outer_inline_events_are_detected(self):
        workflow = (
            "on: {workflow_dispatch: {inputs: {apply: {type: boolean}}}, push: {branches: [main]}}\n"
            "jobs:\n  publish:\n    runs-on: ubuntu-latest\n"
            "    steps:\n      - run: python3 scripts/play/publisher.py deploy\n")
        self.assertEqual(audit.triggers(workflow), {"workflow_dispatch", "push"})
        self.assertTrue(audit.automatic_mutations("play-release.yml", workflow))


class MandatoryTriggerAndMainRegressionTests(unittest.TestCase):
    def test_negated_status_does_not_hide_manual_dispatch_conjunction(self):
        condition = "$" + "{{ !cancelled() && github.event_name == 'workflow_dispatch' }}"
        source = ("on:\n  push:\n  workflow_dispatch:\njobs:\n"
                  "  publish:\n    if: " + condition + "\n"
                  "    runs-on: ubuntu-latest\n"
                  "    steps:\n      - run: python3 scripts/play/publisher.py deploy\n")
        self.assertEqual(audit.automatic_mutations("play.yml", source), [])
        unsafe = source.replace("&& github.event_name", "|| github.event_name")
        self.assertTrue(audit.automatic_mutations("play.yml", unsafe))

    def test_main_checkout_ref_without_job_gate_is_insufficient(self):
        workflow = ("on:\n  workflow_dispatch:\njobs:\n"
                    "  build:\n    runs-on: ubuntu-latest\n"
                    "    steps:\n      - uses: actions/checkout@v4\n"
                    "        with:\n"
                    "          ref: " + "$" + "{{ github.ref }}" + "\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          workflow if path == ".github/workflows/release-build.yml"
                          else None):
            issues = audit.findings()
        self.assertTrue(any("SOURCE: release-build.yml" in issue for issue in issues), issues)


class ReleaseJobBoundaryRegressionTests(unittest.TestCase):
    def test_default_test_track_publisher_is_automatic_mutation(self):
        source = publish_on(
            "on:\n  push:",
            "python3 scripts/publish-play-test.py --bundle release.aab",
            job="deliver")
        self.assertTrue(audit.automatic_mutations("sync.yml", source))

    def test_manual_only_publishing_step_does_not_run_on_push(self):
        source = ("on:\n  push:\n  workflow_dispatch:\njobs:\n"
                  "  deliver:\n    runs-on: ubuntu-latest\n    steps:\n"
                  "      - if: github.event_name == 'workflow_dispatch'\n"
                  "        run: python3 scripts/play/publisher.py deploy\n")
        self.assertEqual(audit.automatic_mutations("play-release.yml", source), [])
        unsafe = source.replace(
            "github.event_name == 'workflow_dispatch'",
            "github.event_name == 'workflow_dispatch' || true")
        self.assertTrue(audit.automatic_mutations("play-release.yml", unsafe))

    def test_unrelated_job_main_gate_cannot_authorize_release_job(self):
        source = ("on:\n  workflow_dispatch:\njobs:\n"
                  "  lint:\n    if: github.ref == 'refs/heads/main'\n"
                  "    runs-on: ubuntu-latest\n"
                  "    steps:\n      - run: echo lint\n"
                  "  build:\n    runs-on: ubuntu-latest\n"
                  "    steps:\n      - run: ./gradlew bundleRelease\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          source if path == ".github/workflows/release-build.yml"
                          else None):
            issues = audit.findings()
        self.assertTrue(any("SOURCE: release-build.yml: job build" in issue
                            for issue in issues), issues)

    def test_release_job_can_depend_on_a_main_gated_preflight(self):
        source = ("on:\n  workflow_dispatch:\njobs:\n"
                  "  source:\n    if: github.ref == 'refs/heads/main'\n"
                  "    runs-on: ubuntu-latest\n"
                  "    steps:\n      - run: echo checked\n"
                  "  build:\n    needs: source\n    runs-on: ubuntu-latest\n"
                  "    steps:\n      - run: ./gradlew bundleRelease\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          source if path == ".github/workflows/release-build.yml"
                          else None):
            issues = audit.findings()
        self.assertFalse(any("SOURCE: release-build.yml" in issue
                             for issue in issues), issues)


class FullYamlPublicationScannerRegressionTests(unittest.TestCase):
    def test_folded_run_publisher_on_push_is_detected(self):
        workflow = ("on:\n  push:\njobs:\n  sync:\n"
                    "    runs-on: ubuntu-latest\n    steps:\n"
                    "      - run: >-\n"
                    "          python3 scripts/publish-play-listing-images.py\n"
                    "          --publish\n")
        self.assertTrue(audit.automatic_mutations("play.yml", workflow))

    def test_folded_store_publisher_without_approval_is_blocked(self):
        workflow = store_workflow(guard=False).replace(
            "      - run: python3 scripts/play/listing_images.py --mode upload\n",
            "      - run: >-\n"
            "          python3 scripts/play/listing_images.py\n"
            "          --mode upload\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "play-listing-images.yml").write_text(workflow, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_GUARD" in issue for issue in issues), issues)

    def test_inline_flow_step_publisher_is_detected(self):
        source = ("on:\n  push:\njobs:\n  sync:\n"
                  "    runs-on: ubuntu-latest\n    steps:\n"
                  "      - {run: 'python3 scripts/publish-play-listing-images.py --publish'}\n")
        self.assertTrue(audit.automatic_mutations("play.yml", source))

    def test_inline_flow_step_store_mutation_cannot_skip_approval(self):
        source = store_workflow(guard=False).replace(
            "      - run: python3 scripts/play/listing_images.py --mode upload\n",
            "      - {run: 'python3 scripts/play/listing_images.py --mode upload'}\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "listing.yml").write_text(source, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_GUARD" in issue for issue in issues), issues)

    def test_build_only_dispatch_comment_cannot_hide_publisher(self):
        workflow = ("on:\n  workflow_dispatch:\njobs:\n"
                    "  build:\n    if: github.ref == 'refs/heads/main'\n"
                    "    runs-on: ubuntu-latest\n    steps:\n"
                    "      - run: python3 scripts/publish-play-test.py --bundle release.aab # /dispatches\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          workflow if path == ".github/workflows/release-build.yml"
                          else None):
            issues = audit.findings()
        self.assertTrue(any("BUILD: build-only workflow" in issue
                            for issue in issues), issues)

    def test_whitespace_variations_of_main_ref_gate_are_equivalent(self):
        source = ("on:\n  workflow_dispatch:\njobs:\n"
                  "  build:\n    if: github.ref=='refs/heads/main'\n"
                  "    runs-on: ubuntu-latest\n    steps:\n"
                  "      - run: ./gradlew bundleRelease\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          source if path == ".github/workflows/release-build.yml"
                          else None):
            issues = audit.findings()
        self.assertFalse(any("SOURCE: release-build.yml" in issue
                             for issue in issues), issues)


    def test_run_heredoc_if_false_cannot_hide_automatic_publisher(self):
        source = ("on:\n  push:\njobs:\n  sync:\n"
                  "    runs-on: ubuntu-latest\n    steps:\n"
                  "      - run: |\n"
                  "          python3 scripts/publish-play-test.py --bundle release.aab\n"
                  "          cat <<'EOF'\n"
                  "          if: false\n"
                  "          EOF\n")
        self.assertTrue(audit.automatic_mutations("play.yml", source))

    def test_step_level_if_false_still_blocks_disabled_publisher(self):
        source = ("on:\n  push:\njobs:\n  sync:\n"
                  "    runs-on: ubuntu-latest\n    steps:\n"
                  "      - run: python3 scripts/publish-play-test.py --bundle release.aab\n"
                  "        if: false\n")
        self.assertFalse(audit.automatic_mutations("play.yml", source))

    def test_heredoc_fake_if_does_not_skip_store_approval(self):
        workflow = store_workflow(guard=False).replace(
            "      - run: python3 scripts/play/listing_images.py --mode upload\n",
            "      - run: |\n"
            "          python3 scripts/play/listing_images.py --mode upload\n"
            "          cat <<'EOF'\n"
            "          if: false\n"
            "          EOF\n")
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "play-listing-images.yml").write_text(workflow, encoding="utf-8")
            with patch.object(audit, "ROOT", Path(temporary)):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_GUARD" in issue for issue in issues), issues)


    def test_conditional_shell_block_cannot_skip_store_approval(self):
        workflow = store_workflow(
            guard_run="|\n"
                      "          if [ \"$RUN_APPROVAL\" = true ]; then\n"
                      "            python3 scripts/ops06/store_gate.py apply\n"
                      "          fi")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "play-listing-images.yml").write_text(workflow, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_OPTIONAL" in issue for issue in issues), issues)

    def test_conditional_shell_block_cannot_skip_internal_provenance(self):
        workflow = ("on:\n  workflow_dispatch:\njobs:\n"
                    "  verify:\n    steps:\n"
                    "      - run: |\n"
                    "          if [ \"$CHECK_SOURCE\" = true ]; then\n"
                    "            python3 scripts/ops04/internal.py guard\n"
                    "          fi\n"
                    "  publish:\n    steps:\n"
                    "      - run: python3 scripts/ops04/internal.py guard\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          workflow if path == ".github/workflows/play-internal-release.yml"
                          else None):
            issues = audit.findings()
        self.assertTrue(any("PROVENANCE: OPS-04 verify" in issue for issue in issues), issues)



class LatestReviewRegressionTests(unittest.TestCase):
    def test_nested_environment_needs_is_not_a_job_dependency(self):
        workflow = ("on:\n  workflow_dispatch:\njobs:\n"
                    "  source:\n    if: github.ref == 'refs/heads/main'\n"
                    "    runs-on: ubuntu-latest\n"
                    "    steps:\n      - run: echo checked\n"
                    "  build:\n    runs-on: ubuntu-latest\n"
                    "    env:\n      needs: source\n"
                    "    steps:\n      - run: ./gradlew bundleRelease\n")
        with patch.object(audit, "read", side_effect=lambda path:
                          workflow if path == ".github/workflows/release-build.yml"
                          else None):
            findings = audit.findings()
        self.assertTrue(any("SOURCE: release-build.yml: job build" in issue
                            for issue in findings), findings)

    def test_unrelated_icon_sync_apply_conditional_cannot_authorize_upload(self):
        workflow = store_workflow(
            guard_if="inputs.apply == true",
            pre_auth=("      - run: |\n"
                      "          if [[ \"$APPLY_MANUAL\" == \"true\" ]]; then\n"
                      "            echo checked\n"
                      "          fi\n"))
        workflow = workflow.replace(
            "    steps:\n",
            "    env:\n      APPLY_MANUAL: '${{ inputs.apply }}'\n    steps:\n")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "store-icon-sync.yml").write_text(workflow, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_OPTIONAL" in issue for issue in issues), issues)

    def test_quoted_python_script_path_is_still_auto_publisher(self):
        workflow = publish_on("on:\n  push:",
                              'python3 "scripts/publish-play-test.py" --bundle release.aab',
                              job="deliver")
        self.assertTrue(audit.automatic_mutations("misc.yml", workflow))



class RootAndModuleInvocationRegressions(unittest.TestCase):
    def test_indented_workflow_root_is_rejected_not_silently_skipped(self):
        source = ("  on:\n    push:\n"
                  "  jobs:\n    sync:\n      runs-on: ubuntu-latest\n"
                  "      steps:\n"
                  "        - run: python3 scripts/publish-play-listing-images.py --publish\n")
        self.assertIn("__unparsed_jobs__", audit.jobs(source))
        self.assertTrue(audit.automatic_mutations("assets.yml", source))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "assets.yml").write_text(source, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_GUARD" in issue for issue in issues), issues)

    def test_python_module_invocation_is_detected_as_play_mutation(self):
        source = publish_on("on:\n  push:",
                            "python3 -m scripts.publish-play-listing-images --publish",
                            job="sync")
        self.assertTrue(audit.automatic_mutations("assets.yml", source))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / ".github/workflows"
            folder.mkdir(parents=True)
            (folder / "assets.yml").write_text(source, encoding="utf-8")
            with patch.object(audit, "ROOT", root):
                issues = audit.store_guard_findings()
        self.assertTrue(any("STORE_GUARD" in issue for issue in issues), issues)

    def test_python_module_check_only_does_not_mutate_play(self):
        source = publish_on("on:\n  push:",
                            "python3 -m scripts.publish-play-listing-images --check-only",
                            job="verify")
        self.assertEqual(audit.automatic_mutations("assets.yml", source), [])


if __name__ == "__main__":
    unittest.main()
