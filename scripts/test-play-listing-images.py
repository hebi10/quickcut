#!/usr/bin/env python3
"""Offline safety tests for one-edit Google Play image publication."""
import importlib.util
from pathlib import Path
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

    def test_exactly_three_types_and_eight_phone_images(self):
        records = []
        def fake(token, method, path, body=None, asset=None):
            records.append((method, path, asset))
            if method == "POST" and path == "/edits":
                return {"id": "test_edit"}
            if method == "GET" and path.endswith("/listings"):
                return {"listings": [{"language": "ko-KR"}]}
            if method == "POST" and asset is not None:
                return {"image": {"id": "test-image"}}
            return {}
        assets, _ = mod.verified_assets()
        with patch.object(mod, "play_request", side_effect=fake):
            result = mod.publish("dummy", assets, commit=True)
        self.assertTrue(result["uploaded"])
        uploads = [r for r in records if r[2] is not None]
        self.assertEqual(len(uploads), 10)
        self.assertEqual(len([r for r in records if r[0] == "DELETE"]), 3)
        self.assertEqual(len([r for r in records if r[1].endswith(":commit")]), 1)
        self.assertFalse(any("/tracks" in path for _, path, _ in records))


if __name__ == "__main__":
    unittest.main()
