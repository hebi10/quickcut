#!/usr/bin/env python3
"""Offline regression tests for automatic app version-release scope."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "app_release_scope",
    Path(__file__).with_name("require-app-release-change.py"),
)
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class AppReleaseScopeTests(unittest.TestCase):
    def test_play_listing_only_never_allocates_a_new_app_version(self):
        paths = [
            "store/google-play/icon.png",
            "store/google-play/featureGraphic.png",
            "store/google-play/ko-KR/phoneScreenshots/01.png",
            "scripts/publish-play-listing-images.py",
            "scripts/test-play-listing-images.py",
            ".github/workflows/play-listing-images.yml",
        ]
        self.assertFalse(scope.app_release_required(paths))

    def test_ci_and_instrumentation_fixes_do_not_force_an_app_release(self):
        paths = [
            "scripts/verify-release-gate.py",
            "scripts/android-instrumentation-check.sh",
            "scripts/verify-api29-complete-suite.py",
            "scripts/test-release-gate.py",
            "app/src/androidTest/java/com/hebi/quickcut/ui/TrimInputInstrumentedTest.kt",
            "docs/52_QC02_EXACT_MAIN_CI_MATRIX.md",
            ".github/workflows/android.yml",
            ".github/workflows/play-closed-test.yml",
        ]
        self.assertFalse(scope.app_release_required(paths))

    def test_actual_app_change_remains_eligible(self):
        for path in [
            "app/src/main/java/com/hebi/quickcut/ui/EditorScreen.kt",
            "app/src/main/res/drawable/ic_launcher_foreground.xml",
            "app/build.gradle.kts",
            "native/src/lib.rs",
            "scripts/build-native-smartcut.sh",
            "scripts/native-smartcut-sources.json",
            "build.gradle.kts",
            "gradle/libs.versions.toml",
        ]:
            with self.subTest(path=path):
                self.assertTrue(scope.app_release_required([path]))

    def test_empty_and_unrelated_paths_do_not_trigger_app_publication(self):
        self.assertFalse(scope.app_release_required([]))
        self.assertFalse(scope.app_release_required([
            "README.md", "docs/PRIVACY_POLICY.md", "scripts/test-emulator-flow.py",
        ]))


if __name__ == "__main__":
    unittest.main()
