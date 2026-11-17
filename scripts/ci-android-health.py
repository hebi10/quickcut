#!/usr/bin/env python3
"""Bounded ADB identity probe; never mark absent emulator evidence as successful."""
import argparse
import subprocess
import sys
import time


def adb_output(adb, arguments, timeout):
    try:
        result = subprocess.run([adb, *arguments], capture_output=True, text=True,
                                timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def probe(expected_api, adb, attempts, timeout, delay):
    for index in range(attempts):
        state = adb_output(adb, ["get-state"], timeout)
        sdk = adb_output(adb, ["shell", "getprop", "ro.build.version.sdk"], timeout) if state == "device" else None
        if state == "device" and sdk == str(expected_api):
            print("Android emulator alive and API identity verified: " + sdk, flush=True)
            return True
        print("::warning::Android emulator unavailable: state=" +
              (state or "offline") + ", api=" + (sdk or "unknown") +
              ", attempt " + str(index + 1) + "/" + str(attempts), flush=True)
        if index + 1 < attempts:
            time.sleep(delay)
    print("::error::Android emulator identity could not be verified; no subsequent "
          "instrumentation phase may be approved.", flush=True)
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-api", type=int, required=True, choices=(29, 35, 36))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--attempts", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=3.0)
    parser.add_argument("--delay", type=float, default=2.0)
    args = parser.parse_args()
    if args.attempts < 1 or args.timeout <= 0 or args.delay < 0:
        parser.error("Retry and timeout bounds must be nonnegative")
    return 0 if probe(args.expected_api, args.adb, args.attempts, args.timeout, args.delay) else 86


if __name__ == "__main__":
    raise SystemExit(main())
