#!/usr/bin/env python3
"""Fail-closed UI-07-A Android screenshot pairs, using only Python stdlib.

File integrity and theme difference do not replace a human screen-by-screen
review, device-specific layout checks or actual video export validation.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

PAIRS = (
    # Existing UI-03-A full 4-tab screen proof
    ("home", "ui03a_{theme}_home.png"),
    ("projects_empty", "ui03a_{theme}_projects.png"),
    ("tools", "ui03a_{theme}_tools.png"),
    ("settings", "ui03a_{theme}_settings.png"),
    # Existing UI-04-A real preview render proof
    ("quick_trim", "editor_design_quick_trim_{theme}.png"),
    ("detailed", "editor_design_detailed_{theme}.png"),
    # UI-07-A additional workflow screenshots
    ("onboarding", "ui07a_{theme}_onboarding.png"),
    ("projects_populated", "ui07a_{theme}_projects.png"),
    ("gallery", "ui07a_{theme}_gallery.png"),
    ("batch", "ui07a_{theme}_batch.png"),
    ("extraction", "ui07a_{theme}_extraction.png"),
    ("crop", "ui07a_{theme}_crop.png"),
    ("audio", "ui07a_{theme}_audio.png"),
    ("text", "ui07a_{theme}_text.png"),
    ("save_options", "ui07a_{theme}_save_options.png"),
    ("completed", "ui07a_{theme}_completed.png"),
    ("progress", "ui07a_{theme}_progress.png"),
    ("original_delete", "ui07a_{theme}_original_delete.png"),
    ("error_dialog", "ui07a_{theme}_error_dialog.png"),
    ("export_cancel", "ui07a_{theme}_export_cancel.png"),
)
ADDITIONAL = tuple(pattern for _, pattern in PAIRS if pattern.startswith("ui07a_"))


def verify(directory: Path, stage20_path: Path) -> dict:
    spec = importlib.util.spec_from_file_location("stage20_png", stage20_path)
    if spec is None or spec.loader is None:
        raise ValueError("Cannot load the canonical PNG integrity validator")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    screenshots = []
    for screen, pattern in PAIRS:
        theme_files = {}
        for theme in ("light", "dark"):
            name = pattern.format(theme=theme)
            path = directory / name
            width, height = module.verify_png(path)
            payload = path.read_bytes()
            theme_files[theme] = {
                "name": name,
                "sha256": hashlib.sha256(payload).hexdigest(),
                "width": width,
                "height": height,
                "sizeBytes": len(payload),
            }
        if (theme_files["light"]["width"], theme_files["light"]["height"]) != (
            theme_files["dark"]["width"], theme_files["dark"]["height"]
        ):
            raise ValueError(f"UI-07-A light/dark dimensions mismatch: {screen}")
        if theme_files["light"]["sha256"] == theme_files["dark"]["sha256"]:
            raise ValueError(f"UI-07-A identical light/dark screenshots: {screen}")
        screenshots.append({"screen": screen, **theme_files})
    return {
        "contract": "ui07a-visual-matrix-v1",
        "status": "PNG integrity and light/dark difference checked; manual QA remains separate",
        "pairs": len(screenshots),
        "images": len(screenshots) * 2,
        "screenshots": screenshots,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--screenshots", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--validator", type=Path, default=Path(__file__).with_name(
        "stage20-evidence-verify.py"))
    args = parser.parse_args()
    report = verify(args.screenshots, args.validator)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"UI-07-A verified {report['pairs']} paired screens ({report['images']} PNGs) "
          "with CRC, dimensions, SHA-256 and distinct light/dark captures")


if __name__ == "__main__":
    main()
