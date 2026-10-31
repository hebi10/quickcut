# Pocket Cut Studio

The user delegated the concept and requested new generated app imagery plus a matching implemented Android design. The selected direction is a small, polished video workroom: ink-green surfaces, mint primary actions, apricot secondary accents, softly rounded cards, clear hierarchy and quiet decoration around real video.

## Direction and alternatives

- Selected: Pocket Cut Studio; friendly, tactile image assets with a restrained dark editing workspace. This makes the identity visible at launch while keeping footage dominant.
- Considered: a dense professional console; rejected because the app focuses on quick phone editing.
- Considered: a full illustrated mascot theme; rejected because repeated decoration would crowd timelines and editing controls.

## Deliverable

- Generate an original transparent launcher mark and a matching start-screen illustration using the built-in image tool. Preserve the generation prompts and source raster assets in the repository.
- Replace launcher imagery, set a matching adaptive-icon background, support the Android themed icon, and use the mark inside the app.
- Use shared colors, readable mint-on-ink controls, 14dp control corners and 20dp panel corners across start, quick trim, detailed editor, batch editor, dialogs, progress and completion.
- Give the start screen a concise hero, obvious editing mode choices and a calmer settings summary. Keep the existing Korean action labels.
- Keep video previews black. Respect narrow phones, landscape, expanded Fold layouts, large font, scrolling and 48dp minimum targets.

## Boundaries

No changes to media/export engines, consent, deletion, saved preferences, draft editing state, recovery, timestamps, gestures or lifecycle. Preserve tested labels, test tags, callbacks and disabled states. No new network access, analytics, permissions, typefaces or dependencies. Existing Material icons remain functional controls; generated raster images provide the new identity. No new SVG assets.

## Acceptance

Generated assets are actually consumed by the app; adaptive and themed icon resource references resolve. The theme is shared with batch editing. Existing Android build/lint/unit and UI tests remain green. Capture actual rendered start/editor/batch views where the available runner supports it; do not label a design mockup as an app screenshot.
