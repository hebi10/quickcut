"""Evidence from the exact unsigned CI artifacts and an installed QA-signed release."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile

EVIDENCE = Path("quickcut-dex03-evidence")
PACKAGE = "com.hebi.quickcut"
FIXTURE = "/sdcard/Download/QuickCutDex03.mp4"
MAPPING = "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"
DEX_ENTRY = re.compile(r"base/dex/classes\d*\.dex")
SMARTCUT_LIBS = {
    f"base/lib/{abi}/libquickcut_smartcut.so" for abi in ("arm64-v8a", "x86_64")
}


def bundle_info(path):
    with ZipFile(path) as bundle:
        dex = [entry for entry in bundle.infolist() if DEX_ENTRY.fullmatch(entry.filename)]
        assert dex, f"Missing DEX in {path}"
        native = SMARTCUT_LIBS.intersection(bundle.namelist())
        assert native == SMARTCUT_LIBS, f"Lost native libraries in {path}: {native}"
        marker = any(b"~~R8{" in bundle.read(entry) for entry in dex)
        mapping = bundle.read(MAPPING).decode("utf-8") if MAPPING in bundle.namelist() else None
        return {
            "bundle_bytes": path.stat().st_size,
            "dex_uncompressed_bytes": sum(entry.file_size for entry in dex),
            "dex_compressed_bytes": sum(entry.compress_size for entry in dex),
            "dex_count": len(dex),
            "r8_marker": marker,
            "mapping_lines": len(mapping.splitlines()) if mapping else 0,
            "native_libraries": sorted(native),
        }, mapping


def emit(filename, result):
    report = json.dumps(result, ensure_ascii=False, indent=2)
    (EVIDENCE / filename).write_text(report + "\n", encoding="utf-8")
    print(report, flush=True)


def packages():
    baseline, _ = bundle_info(Path("baseline/app-release.aab"))
    candidate, mapping = bundle_info(Path("candidate/app-release.aab"))
    with ZipFile("candidate/app-release-unsigned.apk") as apk:
        entries = [entry for entry in apk.namelist() if re.fullmatch(r"classes\d*\.dex", entry)]
        assert any(b"~~R8{" in apk.read(entry) for entry in entries), "The APK to install has no R8 marker"
        assert {name.removeprefix("base/") for name in SMARTCUT_LIBS}.issubset(apk.namelist())
    emit("dex.json", {"baseline": baseline, "candidate": candidate})
    assert candidate["r8_marker"] and mapping, "R8 marker or mapping missing"
    for name in ("NativeSmartCutBridge", "NativeSmartCutCallbacks"):
        qualified = f"{PACKAGE}.media.{name}"
        assert f"{qualified} -> {qualified}:" in mapping, f"JNI name changed: {qualified}"
    (EVIDENCE / "mapping.txt").write_text(mapping, encoding="utf-8")
    assert candidate["dex_uncompressed_bytes"] < baseline["dex_uncompressed_bytes"]
    assert candidate["bundle_bytes"] < baseline["bundle_bytes"]


def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=25)


def shell(*args):
    return adb("shell", *args).decode("utf-8", errors="replace").strip()


def snapshot(label):
    remote = f"/sdcard/dex03-{os.getpid()}-{time.monotonic_ns()}.xml"
    try:
        shell("uiautomator", "dump", remote)
        xml = adb("exec-out", "cat", remote)
    finally:
        shell("rm", "-f", remote)
    (EVIDENCE / f"{label}.xml").write_bytes(xml)
    return list(ET.fromstring(xml).iter("node"))


def node_value(node):
    return (node.get("text") or node.get("content-desc") or "").split("\n", 1)[0]


def find(nodes, labels, package=PACKAGE):
    for label in labels:
        for node in nodes:
            if (package is None or (node.get("package") or "").endswith(package)) and (
                label == node_value(node) or label == node.get("content-desc")
            ) and node.get("enabled") == "true":
                if re.fullmatch(r"\[\d+,\d+\]\[\d+,\d+\]", node.get("bounds", "")):
                    return node
    return None


def wait_for(label, predicate, timeout=32):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            nodes = snapshot(label)
        except (subprocess.SubprocessError, ET.ParseError):
            time.sleep(1)
            continue
        match = predicate(nodes)
        if match is not None:
            print(f"Verified {label}: {node_value(match) if isinstance(match, ET.Element) else match}", flush=True)
            return match
        time.sleep(1)
    raise AssertionError(f"Timed out: {label}")


def tap(node):
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    assert right > left and bottom > top
    shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    print(f"Tapped {node_value(node)}", flush=True)


def screenshot(label):
    (EVIDENCE / f"{label}.png").write_bytes(adb("exec-out", "screencap", "-p"))


def start_import():
    for _ in range(5):
        node = wait_for("home", lambda nodes: find(nodes, ("빠른 자르기 시작", "시작하기", "다음")))
        selected = node_value(node)
        tap(node)
        if selected == "빠른 자르기 시작":
            break
    else:
        raise AssertionError("QuickCut home not found after onboarding")

    node = wait_for("document-picker", lambda nodes: find(
        nodes, ("QuickCutDex03.mp4", "Show roots", "Open navigation drawer"), "documentsui"))
    if node_value(node) != "QuickCutDex03.mp4":
        tap(node)
        tap(wait_for("picker-downloads", lambda nodes: find(nodes, ("Downloads",), "documentsui")))
        node = wait_for("picker-video", lambda nodes: find(nodes, ("QuickCutDex03.mp4",), "documentsui"))
    tap(node)
    wait_for("import", lambda nodes: find(nodes, ("영상 미리보기",)))
    screenshot("import")


def preview():
    play = wait_for("preview-ready", lambda nodes: find(nodes, ("재생",)))
    tap(play)

    # Accessibility dumps wait for idle. Pause after proving that the position advanced.
    time.sleep(3)
    screenshot("preview-playing")
    tap(play)

    def advanced(nodes):
        if find(nodes, ("재생",)) is None:
            return None
        return next((
            node_value(node) for node in nodes
            if node.get("package") == PACKAGE and node_value(node).startswith("현재 ")
            and re.search(r"[1-9]", node_value(node))
        ), None)

    return wait_for("preview-advanced", advanced, 25)


def export():
    tap(wait_for("save", lambda nodes: find(nodes, ("영상 저장",))))
    choices = ("저장 완료", "저장 시작", "정확한 위치로 자동 저장", "추천 위치로 고속 저장")

    def export_action(nodes):
        action = find(nodes, choices)
        if action is not None:
            return action
        prompt = find(nodes, ("Allow QuickCut to send you notifications?",), "permissioncontroller")
        if prompt is not None:
            return find(nodes, ("Allow",), "permissioncontroller")
        return None

    for _ in range(5):
        node = wait_for("export", export_action, 70)
        if node_value(node) == "저장 완료":
            break
        if node_value(node) == "Allow":
            screenshot("export-notification-permission")
        tap(node)
    else:
        raise AssertionError("Export never showed the completed state")
    screenshot("export-complete")
    nodes = snapshot("export-complete")
    report = next((node_value(node) for node in nodes if node_value(node).startswith("처리 방식: ")), None)
    return report


def device():
    original_hash = shell("sha256sum", FIXTURE).split()[0]
    try:
        start_import()
        position = preview()
        report = export()
        output = shell("find", "/sdcard/Movies/QuickCut", "-name", "*.mp4").splitlines()
        assert len(output) == 1 and "/QuickCut_" in output[0], output
        pulled = Path(os.environ["RUNNER_TEMP"]) / "quickcut-output.mp4"
        adb("pull", output[0], str(pulled))
        probe = json.loads(subprocess.check_output([
            "ffprobe", "-v", "error", "-show_entries", "format=duration,size:stream=codec_name,codec_type",
            "-of", "json", str(pulled),
        ], timeout=25))
        assert {stream["codec_type"] for stream in probe["streams"]} == {"video", "audio"}, probe
        assert 8.75 <= float(probe["format"]["duration"]) <= 9.25, probe
        subprocess.run(
            ["ffmpeg", "-nostdin", "-v", "error", "-xerror", "-i", str(pulled),
             "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-"],
            check=True, timeout=45,
        )
        probe["output_decoded"] = True
        assert shell("sha256sum", FIXTURE).split()[0] == original_hash, "The source video changed"
        probe["preview_position"] = position
        export_log = adb("logcat", "-d", "-v", "brief", "QuickCutExport:I", "*:S").decode()
        (EVIDENCE / "export-log.txt").write_text(export_log, encoding="utf-8")
        smartcut = re.search(r"method=native_smart_cut copiedVideoPackets=(\d+) encodedVideoPackets=(\d+)", export_log)
        assert smartcut and all(int(count) > 0 for count in smartcut.groups()), export_log
        probe["native_smartcut"] = {
            "copied_video_packets": int(smartcut[1]), "encoded_video_packets": int(smartcut[2]),
        }
        probe["export_mode"] = report
        probe["source_unchanged"] = True
        probe["output_sha256"] = hashlib.sha256(pulled.read_bytes()).hexdigest()
        emit("device.json", probe)
    except Exception:
        screenshot("device-failure")
        raise
    finally:
        logs = adb("logcat", "-d", "-b", "crash", "-b", "events", "-v", "brief",
                   "AndroidRuntime:E", "DEBUG:F", "libc:F", "am_crash:I", "am_anr:I", "*:S")
        (EVIDENCE / "crash-anr.txt").write_bytes(logs)
        spec = importlib.util.spec_from_file_location("startup", "scripts/android-startup-check.py")
        startup = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(startup)
        assert not startup.app_failed(logs.decode(errors="replace")), "App crash or ANR observed"


if __name__ == "__main__":
    EVIDENCE.mkdir(exist_ok=True)
    {"packages": packages, "device": device}[sys.argv[1]]()
