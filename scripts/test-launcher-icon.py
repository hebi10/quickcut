#!/usr/bin/env python3
"""Keep launcher, Android 13 themed icon and native splash on one brand."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def attr(node, name):
    return node.attrib.get(ANDROID + name)


manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
app = manifest.find("application")
assert app is not None, "Android application missing"
assert attr(app, "icon") == "@mipmap/ic_launcher"
assert attr(app, "roundIcon") == "@mipmap/ic_launcher"

icon = ET.parse(RES / "drawable/ic_launcher_foreground.xml").getroot()
assert icon.tag == "vector", "Launcher foreground must be a scalable vector"
assert attr(icon, "viewportWidth") == "108"
assert attr(icon, "viewportHeight") == "108"
groups = {attr(group, "name"): group for group in icon.findall("group")}
assert {"scissors", "play"} == set(groups), "New QuickCut mark requires white scissors + play"
for name in ("scissors", "play"):
    paths = groups[name].findall("path")
    assert len(paths) == 1 and attr(paths[0], "fillColor") == "#FFFFFFFF"
    assert attr(paths[0], "pathData"), f"{name} path missing"

for variant in ("mipmap-anydpi-v26", "mipmap-anydpi-v33"):
    adaptive = ET.parse(RES / variant / "ic_launcher.xml").getroot()
    assert adaptive.tag == "adaptive-icon"
    assert attr(adaptive.find("background"), "drawable") == "@color/ic_launcher_background"
    assert attr(adaptive.find("foreground"), "drawable") == "@drawable/ic_launcher_foreground"
    if variant.endswith("v33"):
        assert attr(adaptive.find("monochrome"), "drawable") == "@drawable/ic_launcher_foreground"

colors = ET.parse(RES / "values/colors.xml").getroot()
background = {x.get("name"): (x.text or "").strip().upper() for x in colors}
assert background.get("ic_launcher_background") == "#6D28D9", "Launcher purple must match brand"
print("QuickCut launcher adaptive/monochrome/splash resources reference the updated scissors-play mark")
