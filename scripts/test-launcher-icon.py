#!/usr/bin/env python3
"""Verify real generated QuickCut Android/Play icon art, without third-party libraries.

The app, splash, adaptive/masked and Android 13 monochrome appearances must
come from one logo. Verify full PNG chunk CRC + zlib data, not just file headers.
"""
from pathlib import Path
import math
import struct
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
BRAND = "quickcut_brand_foreground"
EXPECTED_DARK = "#181818"


def attr(node, name):
    return node.attrib.get(ANDROID + name)


def png(path, expected_size, expected_mode):
    payload = path.read_bytes()
    if payload[:8] != b"\x89PNG\r\n\x1a\n":
        raise AssertionError(f"Not a PNG: {path}")
    at = 8
    idat = []
    props = None
    ended = False
    while at < len(payload):
        if at + 12 > len(payload):
            raise AssertionError(f"Truncated PNG chunk: {path}")
        length = struct.unpack_from(">I", payload, at)[0]
        if length > 12_000_000 or at + 12 + length > len(payload):
            raise AssertionError(f"Invalid PNG chunk length: {path}")
        kind = payload[at + 4:at + 8]
        chunk = payload[at + 8:at + 8 + length]
        crc = struct.unpack_from(">I", payload, at + 8 + length)[0]
        assert zlib.crc32(kind + chunk) & 0xFFFFFFFF == crc, f"Bad PNG CRC: {path}"
        if kind == b"IHDR":
            width, height, depth, mode, comp, filt, interlace = struct.unpack(">IIBBBBB", chunk)
            assert (width, height) == expected_size, f"Wrong icon dimensions: {path}"
            assert (depth, mode, comp, filt, interlace) == (8, expected_mode, 0, 0, 0)
            props = (width, height, 4 if mode == 6 else 3)
        elif kind == b"IDAT":
            idat.append(chunk)
        elif kind == b"IEND":
            assert not length and at + 12 == len(payload)
            ended = True
            break
        at += 12 + length
    assert props is not None and idat and ended, f"Invalid PNG structure: {path}"
    width, height, bpp = props
    pixels = zlib.decompress(b"".join(idat))
    assert len(pixels) == height * (1 + width * bpp), f"Wrong inflated image size: {path}"
    image = bytearray(width * height * bpp)
    row_size = width * bpp + 1
    for y in range(height):
        row = pixels[y * row_size:(y + 1) * row_size]
        assert row[0] == 0, f"Unexpected PNG filter: {path}"
        image[y * width * bpp:(y + 1) * width * bpp] = row[1:]
    return width, height, bpp, image


manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
app = manifest.find("application")
assert app is not None
assert attr(app, "icon") == "@mipmap/ic_launcher"
assert attr(app, "roundIcon") == "@mipmap/ic_launcher"

for variant in ("mipmap-anydpi-v26", "mipmap-anydpi-v33"):
    adaptive = ET.parse(RES / variant / "ic_launcher.xml").getroot()
    assert adaptive.tag == "adaptive-icon"
    assert attr(adaptive.find("background"), "drawable") == "@color/ic_launcher_background"
    assert attr(adaptive.find("foreground"), "drawable") == "@drawable/" + BRAND + "_safe"
    if variant.endswith("v33"):
        assert attr(adaptive.find("monochrome"), "drawable") == "@drawable/" + BRAND + "_safe"

inset = ET.parse(RES / "drawable" / (BRAND + "_safe.xml")).getroot()
assert inset.tag == "inset"
assert attr(inset, "drawable") == "@drawable/" + BRAND
for side in ("Left", "Right", "Top", "Bottom"):
    assert attr(inset, "inset" + side) == "10dp"

colors = ET.parse(RES / "values/colors.xml").getroot()
values = {e.get("name"): (e.text or "").strip().upper() for e in colors}
assert values.get("ic_launcher_background") == EXPECTED_DARK

splash = ET.parse(RES / "values-v31/styles.xml").getroot()
icon_entry = splash.find(".//item[@name='android:windowSplashScreenAnimatedIcon']")
assert icon_entry is not None and (icon_entry.text or "").strip() == "@drawable/" + BRAND + "_safe"
artwork = (ROOT / "app/src/main/java/com/hebi/quickcut/ui/BrandArtwork.kt").read_text(encoding="utf-8")
assert "R.drawable." + BRAND in artwork

store_path = ROOT / "store/google-play/icon.png"
master_path = ROOT / "design/brand/quickcut-brand-icon-01-master.png"
store_w, store_h, store_bpp, store = png(store_path, (512, 512), 2)
master_w, master_h, master_bpp, master = png(master_path, (1024, 1024), 2)
fg_w, fg_h, fg_bpp, fg = png(
    RES / "drawable-nodpi" / (BRAND + ".png"), (432, 432), 6
)
assert store_bpp == master_bpp == 3 and fg_bpp == 4
for pix in ((0, 0), (511, 0), (0, 511), (511, 511)):
    off = (pix[1] * 512 + pix[0]) * 3
    assert tuple(store[off:off + 3]) == (24, 24, 24), "Play background has a tint"
assert max(store) >= 245 and min(store) <= 25, "Low contrast store icon"

visible = 0
max_radius = 0.0
for y in range(fg_h):
    for x in range(fg_w):
        off = (y * fg_w + x) * 4
        if fg[off + 3] >= 16:
            visible += 1
            assert all(value >= 245 for value in fg[off:off + 3])
            max_radius = max(max_radius, math.hypot(x - 215.5, y - 215.5))
assert visible >= 5_000, "Launcher symbol is unexpectedly small or absent"
# 10dp inset scales the artwork from 108dp down to 88dp. The foreground must
# stay within the Android 66/108dp central adaptive icon safe circle.
assert max_radius * (88 / 108) <= 132, "Logo would be clipped by circle adaptive icon"
print("BRAND-ICON-01: 1024 master, 512 Play, 432 RGBA Android, safe adaptive/mono/splash/header passed")
