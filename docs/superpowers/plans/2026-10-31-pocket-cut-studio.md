# Pocket Cut Studio Implementation Plan

**Goal:** Replace QuickCut's generic imagery and flat styling with a coherent generated identity and usable editing workspace.
**Architecture:** Existing Compose screens keep their state and callbacks. A shared palette and shapes drive all routes; generated PNG/WebP resources provide launcher and home imagery.
**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android raster/adaptive resources, built-in image generation.
**Spec:** docs/superpowers/specs/2026-10-31-pocket-cut-studio-design.md

## Global constraints

Preserve all media behavior, tested Korean labels, semantics/tags and 48dp targets. No new permissions/dependencies/SVG. Work in the isolated restored source branch; remote publication uses GitHub commit APIs against the original main history, never the local snapshot history.

## Tasks

- [ ] Generate and inspect the launcher foreground and home illustration; save project copies and prompts. Prepare correctly sized Android assets and adaptive/themed icon references.
- [ ] Apply shared ink/mint/apricot colors, semantic on-colors, typography and 14/20dp shapes. Redesign start/progress/completion visuals without changing state paths.
- [ ] Apply the shared tokens and rounded hierarchy to detailed/quick editing, timeline and batch cards. Keep compact, landscape and expanded layout branches.
- [ ] Review contrast and raster alpha/safe zones, run appropriate existing build/lint/unit/instrumentation checks, inspect real rendered views and resolve visual regressions.
- [ ] Commit the completed design and generated assets to the repository with a concise change and validation record.

## Review focus

- Small screen/large font: actions remain reachable by scrolling and text is not clipped.
- Real video: image artwork never overlays the preview or changes its lifecycle.
- Bright mint controls: dark foreground text meets contrast expectations.
- Adaptive mask: central launcher artwork survives common icon masks; monochrome resource has real alpha.
- Batch and editor: no duplicated old blue palette; disabled states and consent labels remain intact.
