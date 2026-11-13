#!/usr/bin/env python3
"""Require an existing registered internal binary before approved publication.
This preflight never uploads bundles, changes tracks, or commits an edit.
"""
import importlib.util
import os
from pathlib import Path
import sys

module_path = Path(__file__).resolve().parents[1] / "publish-play-test.py"
spec = importlib.util.spec_from_file_location("quickcut_play_publisher", module_path)
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


def require_existing_internal(api):
    edit_id = api.request("POST", "/edits", {})["id"]
    if not isinstance(edit_id, str) or not edit_id.replace("-", "").replace("_", "").isalnum():
        raise ValueError("Invalid Play edit ID")
    edit_path = "/edits/" + edit_id
    try:
        tracks = publisher.inventory_list(api, edit_path + "/tracks", "tracks")
        aliases = [t for t in tracks if t.get("track") in ("internal", "qa")]
        if len(aliases) != 1:
            raise ValueError("Exactly one existing internal track is required")
        if not publisher.version_codes(aliases[0]):
            raise ValueError("Internal track has no registered binary")
        bundles = publisher.inventory_list(api, edit_path + "/bundles", "bundles")
        apks = publisher.inventory_list(api, edit_path + "/apks", "apks")
        registered = publisher.inventory_codes([], bundles, apks)
        if not registered.intersection(publisher.version_codes(aliases[0])):
            raise ValueError("Internal release binary is absent from Play artifact inventory")
    finally:
        api.request("DELETE", edit_path)


if __name__ == "__main__":
    try:
        require_existing_internal(publisher.PublisherAPI(os.environ.get("PLAY_ACCESS_TOKEN", "")))
        print("Existing internal Play binary verified without upload or commit")
    except (KeyError, ValueError, publisher.APIError, publisher.TransportError) as error:
        print("Play internal preflight blocked:", error, file=sys.stderr)
        sys.exit(1)
