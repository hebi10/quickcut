#!/usr/bin/env python3
"""Dismiss only verified Android launcher/system-UI ANR 'Wait' dialogs in CI.

Never acknowledge a QuickCut ANR, a generic error, or an unknown popup.
This runs only in the disposable GitHub Actions emulator, not the app.
"""
import argparse
import re
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PROCESS_TITLES = {
    "Pixel Launcher isn't responding": "com.google.android.apps.nexuslauncher",
    "System UI isn't responding": "com.android.systemui",
}
FOCUS_RE = re.compile(r"(?m)^\s*mCurrentFocus=([^\r\n]*)")
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
STOP = False


def focus_has_allowed_anr(dumpsys: str) -> bool:
    matches = FOCUS_RE.findall(dumpsys)
    if len(matches) != 1:
        return False
    focus = matches[0].lower()
    error = any(label in focus for label in (
        "application not responding", "application error", "app not responding",
    ))
    return error and any(package in focus for package in PROCESS_TITLES.values())


def verified_wait_target(xml: str):
    root = ET.fromstring(xml)
    titles = [
        node.attrib["text"] for node in root.iter("node")
        if node.get("package") == "android"
        and node.get("resource-id") == "android:id/alertTitle"
        and node.get("text") in PROCESS_TITLES
    ]
    waits = [
        node for node in root.iter("node")
        if node.get("package") == "android"
        and node.get("resource-id") == "android:id/aerr_wait"
        and node.get("text") == "Wait"
        and node.get("enabled") == "true"
    ]
    if len(titles) != 1 or len(waits) != 1:
        return None
    match = BOUNDS_RE.fullmatch(waits[0].get("bounds", ""))
    if not match:
        return None
    left, top, right, bottom = map(int, match.groups())
    if not (0 <= left < right <= 10000 and 0 <= top < bottom <= 10000):
        return None
    return (left + right) // 2, (top + bottom) // 2, titles[0]


def command(args, timeout=7):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"adb command exited {result.returncode}: {' '.join(args[:4])}")
    return result.stdout


def monitor(duration: int, interval: float):
    stop_at = time.monotonic() + duration
    acknowledges = 0
    while not STOP and time.monotonic() < stop_at:
        try:
            focus = command(["adb", "shell", "dumpsys", "window"])
            if focus_has_allowed_anr(focus):
                command(["adb", "shell", "uiautomator", "dump",
                         "/sdcard/quickcut-ci-system-error.xml"], timeout=12)
                xml = command(["adb", "shell", "cat",
                               "/sdcard/quickcut-ci-system-error.xml"])
                target = verified_wait_target(xml)
                if target is not None:
                    x, y, title = target
                    # The foreground window was a matching system ANR, and the
                    # accessibility hierarchy contains the exact system Wait control.
                    command(["adb", "shell", "input", "tap", str(x), str(y)])
                    acknowledges += 1
                    print(f"Verified system ANR Wait acknowledged: {title} ({acknowledges})",
                          flush=True)
                    if acknowledges > 6:
                        print("Too many system ANRs: emulator is unstable", file=sys.stderr)
                        return 2
                else:
                    print("System error focus found, but exact Wait dialog not verified",
                          flush=True)
        except (RuntimeError, subprocess.TimeoutExpired, ET.ParseError) as error:
            print(f"Read-only system ANR probe unavailable: {type(error).__name__}",
                  flush=True)
        time.sleep(interval)
    print(f"System ANR watchdog stopped; verified waits={acknowledges}", flush=True)
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--max-seconds", type=int, default=3600)
    parser.add_argument("--poll-seconds", type=float, default=2.0)
    args = parser.parse_args()
    if not (10 <= args.max_seconds <= 5400 and 1 <= args.poll_seconds <= 30):
        parser.error("Watchdog bounds exceeded")
    global STOP
    signal.signal(signal.SIGTERM, lambda *_: globals().__setitem__("STOP", True))
    return monitor(args.max_seconds, args.poll_seconds)


if __name__ == "__main__":
    sys.exit(main())
