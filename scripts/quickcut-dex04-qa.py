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

EVIDENCE = Path("quickcut-dex04-evidence")
PACKAGE = "com.hebi.quickcut"
FIXTURE = "/sdcard/Download/QuickCutDex04.mp4"
STARTUP = None
SYSTEM_DIALOG_WAITS = 0
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
    return ET.fromstring(xml)


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


def crash_events():
    return adb("logcat", "-d", "-b", "crash", "-b", "events", "-v", "brief",
               "AndroidRuntime:E", "DEBUG:F", "libc:F", "am_crash:I", "am_anr:I", "*:S")


def recover_system_dialog(hierarchy):
    global SYSTEM_DIALOG_WAITS
    coordinates = STARTUP.system_launcher_wait_target(hierarchy)
    if coordinates is None:
        return False
    assert SYSTEM_DIALOG_WAITS < 3, "Emulator system ANR kept blocking QuickCut"
    assert not STARTUP.app_failed(crash_events().decode(errors="replace")), "QuickCut crash or ANR observed"
    shell("input", "tap", *(str(point) for point in coordinates))
    SYSTEM_DIALOG_WAITS += 1
    print("Acknowledged an allowlisted emulator launcher or System UI ANR dialog", flush=True)
    return True


def wait_for(label, predicate, timeout=32):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            hierarchy = snapshot(label)
        except (subprocess.SubprocessError, ET.ParseError):
            time.sleep(1)
            continue
        if recover_system_dialog(hierarchy):
            continue
        match = predicate(list(hierarchy.iter("node")))
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


def choose(label, description):
    return wait_for(label, lambda nodes: find(nodes, (description,)))


def onboard():
    for _ in range(4):
        node = wait_for("baseline-onboarding", lambda nodes: find(nodes, ("시작하기", "다음")))
        tap(node)
        if node_value(node) == "시작하기":
            choose("baseline-home", "빠른 자르기 시작")
            return
    raise AssertionError("QuickCut did not complete onboarding")


def select_dark_theme():
    tap(choose("baseline-settings-tab", "설정"))
    tap(choose("baseline-system-theme", "테마: 시스템 설정"))
    tap(choose("baseline-dark-option", "다크"))
    choose("baseline-dark-theme", "테마: 다크")
    screenshot("baseline-dark-theme")


def verify_retained_theme():
    choose("optimized-home-without-onboarding", "빠른 자르기 시작")
    tap(choose("optimized-settings-tab", "설정"))
    choose("optimized-retained-dark-theme", "테마: 다크")
    screenshot("optimized-dark-theme")
    tap(choose("optimized-home-tab", "홈"))


def start_import():
    tap(choose("optimized-home-import", "빠른 자르기 시작"))

    def visible_video(nodes):
        if find(nodes, ("Open from",), "documentsui") is not None:
            return None
        return find(nodes, ("QuickCutDex04.mp4",), "documentsui")

    try:
        node = wait_for("picker-recent-video", visible_video, 24)
    except AssertionError:
        tap(wait_for("picker-roots", lambda nodes: find(
            nodes, ("Show roots", "Open navigation drawer"), "documentsui")))

        def downloads_row(nodes):
            if find(nodes, ("Open from",), "documentsui") is not None:
                return find(nodes, ("Downloads",), "documentsui")
            return None

        def download_video(nodes):
            if find(nodes, ("Downloads",), "documentsui") is not None:
                return visible_video(nodes)
            return None

        for _ in range(3):
            tap(wait_for("picker-downloads", downloads_row))
            try:
                node = wait_for("picker-downloads-video", download_video, 22)
                break
            except AssertionError:
                continue
        else:
            raise AssertionError("Downloads remained covered by the DocumentsUI navigation drawer")
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
    nodes = snapshot("export-complete").iter("node")
    report = next((node_value(node) for node in nodes if node_value(node).startswith("처리 방식: ")), None)
    return report


def enable_qa_launcher_recovery():
    repository_dialog_target = STARTUP.system_launcher_wait_target

    def known_launcher_dialog(hierarchy):
        target = repository_dialog_target(hierarchy)
        if target is not None:
            return target

        nodes = list(hierarchy.iter("node"))
        title = find(nodes, ("Quickstep isn't responding",), "android")
        close = find(nodes, ("Close app",), "android")
        if (title is None or title.get("resource-id") != "android:id/alertTitle"
                or close is None or close.get("resource-id") != "android:id/aerr_close"):
            return None
        log = crash_events().decode(errors="replace")
        if STARTUP.app_failed(log) or not re.search(r"am_anr[^\n]*com\.android\.launcher3", log):
            return None
        left, top, right, bottom = map(int, re.findall(r"\d+", close.get("bounds")))
        assert 0 <= left < right <= 10000 and 0 <= top < bottom <= 10000
        print("Acknowledging the verified AOSP Quickstep ANR using its Android Close app control", flush=True)
        return (left + right) // 2, (top + bottom) // 2

    STARTUP.system_launcher_wait_target = known_launcher_dialog


def device():
    global STARTUP
    spec = importlib.util.spec_from_file_location("startup", "scripts/android-startup-check.py")
    STARTUP = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(STARTUP)
    enable_qa_launcher_recovery()
    original_hash = shell("sha256sum", FIXTURE).split()[0]
    api = int(os.environ["QUICKCUT_EMULATOR_API"])
    try:
        STARTUP.check_startup(EVIDENCE / "baseline-startup")
        onboard()
        select_dark_theme()
        shell("am", "force-stop", PACKAGE)
        candidate = Path(os.environ["RUNNER_TEMP"]) / "quickcut-candidate-qa.apk"
        installed = adb("install", "-r", str(candidate)).decode(errors="replace")
        print(installed, flush=True)
        assert "Success" in installed
        assert shell("sha256sum", FIXTURE).split()[0] == original_hash, "The source video changed after upgrade"
        STARTUP.check_startup(EVIDENCE / "optimized-startup")
        verify_retained_theme()
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
        if smartcut is not None:
            assert all(int(count) > 0 for count in smartcut.groups()), export_log
            probe["native_smartcut"] = {
                "copied_video_packets": int(smartcut[1]), "encoded_video_packets": int(smartcut[2]),
            }
        else:
            # API 29 can return through the native pending-MediaStore path,
            # which reports partial encoding in the UI without a method log.
            assert api == 29 and report and "일부 구간 재인코딩" in report, export_log
            assert "method=media3" not in export_log and "method=fast_remux" not in export_log, export_log
            probe["native_smartcut"] = {"direct_pending_export": True}
        probe["updated_in_place"] = True
        probe["retained_theme"] = "다크"
        probe["api"] = api
        probe["export_mode"] = report
        probe["source_unchanged"] = True
        probe["source_sha256"] = original_hash
        probe["output_sha256"] = hashlib.sha256(pulled.read_bytes()).hexdigest()
        emit("device.json", probe)
    except Exception:
        screenshot("device-failure")
        raise
    finally:
        export_log = adb("logcat", "-d", "-v", "brief", "QuickCutExport:I", "*:S")
        (EVIDENCE / "export-log.txt").write_bytes(export_log)
        logs = crash_events()
        (EVIDENCE / "crash-anr.txt").write_bytes(logs)
        assert not STARTUP.app_failed(logs.decode(errors="replace")), "App crash or ANR observed"



if __name__ == "__main__":
    EVIDENCE.mkdir(exist_ok=True)
    {"packages": packages, "device": device}[sys.argv[1]]()
