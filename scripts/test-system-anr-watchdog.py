#!/usr/bin/env python3
"""Guard against accidentally dismissing app failures or arbitrary dialogs."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "system_watchdog", Path(__file__).with_name("android-system-anr-watchdog.py"))
watchdog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watchdog)

TITLE = "Pixel Launcher isn't responding"
FOCUS = ("mCurrentFocus=Window{b0 u0 Application Not Responding: "
         "com.google.android.apps.nexuslauncher}")
DIALOG = ('<hierarchy><node package="android" resource-id="android:id/alertTitle" '
          'text="' + TITLE + '"/>'
          '<node package="android" resource-id="android:id/aerr_wait" '
          'text="Wait" enabled="true" bounds="[70,1296][1010,1422]"/></hierarchy>')


class SystemAnrGuardTests(unittest.TestCase):
    def test_only_verified_launcher_focus_can_trigger_inspection(self):
        self.assertTrue(watchdog.focus_has_allowed_anr(FOCUS))
        self.assertFalse(watchdog.focus_has_allowed_anr(
            "mCurrentFocus=Window{b0 u0 com.hebi.quickcut/com.hebi.quickcut.MainActivity}"))
        self.assertFalse(watchdog.focus_has_allowed_anr(
            "mCurrentFocus=Window{b0 u0 Application Not Responding: com.hebi.quickcut}"))
        self.assertFalse(watchdog.focus_has_allowed_anr(
            "mCurrentFocus=Window{b0 u0 com.google.android.apps.nexuslauncher}"))
        self.assertFalse(watchdog.focus_has_allowed_anr(FOCUS + "\n" + FOCUS))

    def test_only_exact_system_wait_dialog_is_accepted(self):
        self.assertEqual(watchdog.verified_wait_target(DIALOG),
                         (540, 1359, TITLE))
        self.assertIsNone(watchdog.verified_wait_target(
            DIALOG.replace(TITLE, "QuickCut isn't responding")))
        self.assertIsNone(watchdog.verified_wait_target(
            DIALOG.replace('text="Wait"', 'text="Close app"')))
        self.assertIsNone(watchdog.verified_wait_target(
            DIALOG.replace('enabled="true"', 'enabled="false"')))
        self.assertIsNone(watchdog.verified_wait_target(
            DIALOG.replace('package="android"', 'package="com.hebi.quickcut"')))
        self.assertIsNone(watchdog.verified_wait_target(
            DIALOG.replace('bounds="[70,1296][1010,1422]"',
                           'bounds="[9999,1296][1010,1422]"')))

    def test_only_system_ui_is_other_allowlisted_title(self):
        alt = DIALOG.replace(TITLE, "System UI isn't responding")
        self.assertEqual(watchdog.verified_wait_target(alt)[2],
                         "System UI isn't responding")
        self.assertTrue(watchdog.focus_has_allowed_anr(
            "mCurrentFocus=Window{b0 u0 Application Error: com.android.systemui}"))


if __name__ == "__main__":
    unittest.main()
