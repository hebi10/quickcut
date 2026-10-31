# Pocket Cut Studio artwork

Original raster assets generated for QuickCut on 2026-10-31 using the built-in image generation tool. No user media, third-party logos, fonts, SVG or external asset runtime is used. Transparent master PNG files are retained here. Android-ready resources are prepared by `python3 scripts/prepare-studio-artwork.py` (optional Pillow maintainer tool, not an app/build dependency).

## Launcher prompt

Use case: logo-brand. Asset type: original Android adaptive launcher foreground for QuickCut, a compact video trimming app. Create ONE beautifully simple premium app mark, no words: a soft mint-green rounded video frame folded into a right-pointing play shape, with one deliberate diagonal cut/negative-space split and one small warm-apricot cut piece. It should feel like a tiny tactile editing tool: friendly precise silhouette, subtly dimensional matte clay, clean polished bevels, restrained highlights, no busy details. Palette: pale mint #B9F3C3, warm apricot #F5B790, deep ink green #10201C only for inner cut/shadow. Strong readable icon silhouette at 48px. Perfectly centered, straight-on view, ALL artwork contained in the central 60% of a square canvas with broad transparent padding on all four sides for Android adaptive mask safety. Actual transparent background, no background tile, no checkerboard, no cast shadow extending beyond the mark, no scissors, no letters, no typography, no watermark. Render a finished production asset, not an icon sheet or a mockup.

## Home illustration prompt

Use case: stylized-concept. Asset type: compact decorative hero illustration for the home screen of QuickCut, a phone video-cutting app whose concept is Pocket Cut Studio. A single cohesive miniature editing still life, composed horizontally: two gently curved mint film strips with rounded rectangular frames and tiny understated sprocket holes, one small warm-apricot play wedge and a pale ivory rounded editing cursor, arranged as a flowing precise diagonal cut. Friendly premium matte clay with very subtle dimensionality and clean soft lighting, no toy faces. Palette only pale mint #B9F3C3, dark forest #244038, apricot #F5B790 and warm ivory #F0F7F1. The arrangement should stay simple and recognizable at 180x110 pixels. Generous transparent padding around the objects. Balanced composition with no text. Actual transparent background, no surface plane or background card, no grid, no checkerboard, no UI, no words, no numbers, no watermark, no extra objects. Render one final standalone graphic, not a collage or product mockup.

## Integration

- `quickcut-mark-master.png` → 576px adaptive foreground (central280px), monochrome alpha rendition, 352px in-app badge.
- `quickcut-studio-master.png` → 720px lossless WebP illustration, read locally with painterResource.
- Tiny invisible alpha noise is cleared before resampling; actual generated subjects are preserved. The icon's bright material forms the monochrome silhouette; dark cavities remain transparent.
- Adaptive foreground and API33 monochrome fit within the central 66/108 safe circle. Native system splash and both launcher variants share the ink background.
