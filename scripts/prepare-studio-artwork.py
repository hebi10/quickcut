#!/usr/bin/env python3
"""Prepare density-independent Android assets from the generated raster masters.

Only packaging operations are applied: alpha cleanup below visible precision,
resampling, transparent padding, and an alpha rendition for Android themed icons.
Requires Pillow for this optional maintainer command; not used by the app/build.
"""
from pathlib import Path
from math import dist
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'design/brand'
DEST = ROOT / 'app/src/main/res/drawable-nodpi'
DEST.mkdir(parents=True, exist_ok=True)

def artwork(name):
    image = Image.open(SOURCE / name).convert('RGBA')
    alpha = image.getchannel('A').point(lambda x: 0 if x < 8 else x)
    image.putalpha(alpha)
    return image.crop(alpha.getbbox())

mark = artwork('quickcut-mark-master.png')
mark.thumbnail((320, 320), Image.Resampling.LANCZOS)
adaptive_mark = mark.copy()
adaptive_mark.thumbnail((280, 280), Image.Resampling.LANCZOS)
# The actual silhouette, including its corners, fits the 66/108 safe circle.
foreground = Image.new('RGBA', (576, 576))
foreground.alpha_composite(adaptive_mark,
    ((576-adaptive_mark.width)//2, (576-adaptive_mark.height)//2))
alpha = foreground.getchannel('A')
assert all(dist((x + .5, y + .5), (288, 288)) < 176
    for y in range(576) for x in range(576) if alpha.getpixel((x, y)) >= 8)
foreground.save(DEST / 'quickcut_launcher_art.png', optimize=True)

badge = Image.new('RGBA', (352, 352))
badge.alpha_composite(mark, ((352-mark.width)//2, (352-mark.height)//2))
badge.save(DEST / 'quickcut_mark.webp', lossless=True, method=6)

# The generated dark cavities become transparent in the monochrome rendition.
lightness = foreground.convert('RGB').convert('L').point(
    lambda x: max(0, min(255, (x-90)*4)))
mono = Image.new('RGBA', foreground.size, (255, 255, 255, 0))
mono.putalpha(ImageChops.multiply(lightness, foreground.getchannel('A')))
mono.save(DEST / 'quickcut_launcher_monochrome.png', optimize=True)

hero = artwork('quickcut-studio-master.png')
hero.thumbnail((720, 360), Image.Resampling.LANCZOS)
hero.save(DEST / 'quickcut_studio.webp', lossless=True, method=6)

for p in sorted(DEST.glob('quickcut*')):
    image = Image.open(p)
    assert image.mode == 'RGBA'
    print(p.relative_to(ROOT), image.size, p.stat().st_size)
