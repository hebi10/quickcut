#!/usr/bin/env python3
"""Offline safety tests for one-edit Google Play image publication."""
import importlib.util
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
from unittest.mock import patch
import unittest

source = Path(__file__).with_name("publish-play-listing-images.py")
spec = importlib.util.spec_from_file_location("play_image_publisher", source)
assert spec and spec.loader
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class ListingAssetTests(unittest.TestCase):
    def test_real_assets_are_all_valid(self):
        assets, records = mod.verified_assets()
        self.assertEqual(len(assets["phoneScreenshots"]), 8)
        self.assertEqual(len(records["icon"]), 1)
        self.assertEqual(len(records["featureGraphic"]), 1)
        self.assertEqual(len(records["phoneScreenshots"]), 8)

    def test_wrong_directory_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "exactly eight"):
            mod.verified_assets(Path("/this-does-not-exist"))

    def test_approved_screenshot_digest_mismatch_blocks_upload(self):
        with tempfile.TemporaryDirectory() as temp:
            cloned = Path(temp) / "google-play"
            shutil.copytree(mod.FOLDER, cloned)
            manifest = cloned / "ko-KR" / "phoneScreenshots" / "manifest.json"
            data = json.loads(manifest.read_text(encoding="utf-8"))
            data["screenshots"][0]["sha256"] = "0" * 64
            manifest.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "approved final-copy"):
                mod.verified_assets(cloned)

    def test_no_listing_does_not_delete_any_current_images(self):
        called = []
        def fake(token, method, path, body=None, asset=None):
            called.append((method, path))
            if method == "POST" and path == "/edits":
                return {"id": "test_edit"}
            if method == "GET" and path == "/edits/test_edit/listings":
                return {"listings": [{"language": "en-US"}]}
            return {}
        assets, _ = mod.verified_assets()
        with patch.object(mod, "play_request", side_effect=fake):
            with self.assertRaisesRegex(ValueError, "Korean listing"):
                mod.publish("dummy", assets, commit=True)
        self.assertEqual([m for m, _ in called], ["POST", "GET", "DELETE"])

    def test_matching_remote_checksums_never_upload_or_commit(self):
        events = []
        assets, _ = mod.verified_assets()
        expected = {kind: [
            __import__("hashlib").sha256(file.read_bytes()).hexdigest() for file in files
        ] for kind, files in assets.items()}

        def fake(token, method, path, body=None, asset=None):
            events.append((method, path, asset))
            if method == "POST" and path == "/edits":
                return {"id": "test_edit"}
            if method == "GET" and path.endswith("/listings"):
                return {"listings": [{"language": "ko-KR"}]}
            if method == "GET":
                kind = path.rsplit("/", 1)[-1]
                return {"images": [{"sha256": sha} for sha in expected[kind]]}
            return {}

        with patch.object(mod, "play_request", side_effect=fake):
            result = mod.publish("dummy", assets, commit=True)
        self.assertFalse(result["uploaded"])
        self.assertEqual(result["mode"], "unchanged")
        self.assertFalse(any(kind == "POST" and path.endswith(":commit")
                             for kind, path, _ in events))
        self.assertFalse(any(method == "DELETE" and "/listings/" in path
                             for method, path, _ in events))

    def test_exactly_three_types_and_eight_phone_images(self):
        records = []
        remote = {kind: [] for kind in ("icon", "featureGraphic", "phoneScreenshots")}
        def fake(token, method, path, body=None, asset=None):
            records.append((method, path, asset))
            if method == "POST" and path == "/edits":
                return {"id": "test_edit"}
            if method == "GET" and path.endswith("/listings"):
                return {"listings": [{"language": "ko-KR"}]}
            kind = path.rsplit("/", 1)[-1]
            if method == "GET":
                return {"images": remote[kind]}
            if method == "DELETE" and "/listings/" in path:
                remote[kind].clear()
            if method == "POST" and asset is not None:
                remote[kind].append({
                    "id": "test-image",
                    "sha256": hashlib.sha256(Path(asset).read_bytes()).hexdigest(),
                })
                return {"image": {"id": "test-image"}}
            return {}
        assets, _ = mod.verified_assets()
        with patch.object(mod, "play_request", side_effect=fake):
            result = mod.publish("dummy", assets, commit=True)
        self.assertTrue(result["uploaded"])
        self.assertEqual(result["mode"], "committed")
        self.assertTrue(result["precommit_sha256_verified"])
        self.assertFalse(result["review_or_publication_verified"])
        uploads = [r for r in records if r[2] is not None]
        self.assertEqual(len(uploads), 10)
        self.assertEqual(len([r for r in records if r[0] == "DELETE"]), 3)
        self.assertEqual(len([r for r in records if r[1].endswith(":commit")]), 1)
        self.assertFalse(any("/tracks" in path for _, path, _ in records))

    def test_bad_uploaded_sha_never_reaches_play_commit(self):
        records = []
        assets, _ = mod.verified_assets()
        remote = {kind: [] for kind in assets}
        def fake(token, method, path, body=None, asset=None):
            records.append((method, path))
            if method == "POST" and path == "/edits":
                return {"id": "test_edit"}
            if method == "GET" and path.endswith("/listings"):
                return {"listings": [{"language": "ko-KR"}]}
            kind = path.rsplit("/", 1)[-1]
            if method == "GET":
                return {"images": remote[kind]}
            if method == "DELETE" and "/listings/" in path:
                remote[kind].clear()
            if method == "POST" and asset is not None:
                remote[kind].append({"id": "test-image", "sha256": "0" * 64})
                return {"image": {"id": "test-image"}}
            return {}
        with patch.object(mod, "play_request", side_effect=fake):
            with self.assertRaisesRegex(ValueError, "does not match approved"):
                mod.publish("dummy", assets, commit=True)
        self.assertFalse(any(path.endswith(":commit") for _, path in records))
        self.assertIn(("DELETE", "/edits/test_edit"), records)


if __name__ == "__main__":
    unittest.main()
