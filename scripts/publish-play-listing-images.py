#!/usr/bin/env python3
"""Upload only the verified QuickCut Korean Play listing images in one protected edit.

No upload retry and no production track changes. All edits are committed atomically.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

PACKAGE = "com.hebi.quickcut"
API = "https://androidpublisher.googleapis.com"
BASE = "/androidpublisher/v3/applications/" + PACKAGE
LOCALE = "ko-KR"
FOLDER = Path("store/google-play")


def verified_assets(folder=FOLDER):
    assets = {
        "icon": [folder / "icon.png"],
        "featureGraphic": [folder / "featureGraphic.png"],
        "phoneScreenshots": sorted((folder / LOCALE / "phoneScreenshots").glob("*.png")),
    }
    if [p.stem for p in assets["phoneScreenshots"]] != [f"{i:02d}" for i in range(1, 9)]:
        raise ValueError("Expected exactly eight ordered phone screenshots")
    sizes = {"icon": (512, 512), "featureGraphic": (1024, 500),
             "phoneScreenshots": (1080, 1920)}
    summary = {}
    for kind, files in assets.items():
        entries = []
        for file in files:
            data = file.read_bytes()
            if (len(data) < 1000 or len(data) > 10_000_000
                    or data[:8] != bytes.fromhex("89504e470d0a1a0a")
                    or data[12:16] != b"IHDR"):
                raise ValueError("Invalid Play PNG asset: " + str(file))
            width = int.from_bytes(data[16:20], "big")
            height = int.from_bytes(data[20:24], "big")
            if (width, height) != sizes[kind]:
                raise ValueError(f"Invalid Play PNG dimensions: {file}, {(width, height)}")
            entries.append({"file": str(file), "bytes": len(data),
                            "sha256": hashlib.sha256(data).hexdigest()})
        summary[kind] = entries
    return assets, summary


def play_request(token, method, path, body=None, asset=None):
    if not re.fullmatch(r"/edits(?:/[A-Za-z0-9_-]+(?:(?:/listings(?:/ko-KR(?:/(?:icon|featureGraphic|phoneScreenshots))?)?)|(?::(?:validate|commit)))?)?", path.split("?")[0]):
        raise ValueError("Unexpected Google Play endpoint: refusing API call")
    query = "?uploadType=media" if asset is not None else ""
    if path.endswith(":commit"):
        path += "?changesInReviewBehavior=ERROR_IF_IN_REVIEW"
    url = API + ("/upload" if asset is not None else "") + BASE + path + query
    if asset is not None:
        data = Path(asset).read_bytes()
        content_type = "image/png"
    elif body is not None:
        data = json.dumps(body).encode("utf-8")
        content_type = "application/json; charset=utf-8"
    else:
        data = None
        content_type = "application/json"
    request = Request(url, data=data, method=method,
                      headers={"Authorization": "Bearer " + token,
                               "Accept": "application/json",
                               "Content-Type": content_type})
    try:
        with urlopen(request, timeout=90 if asset else 45) as response:
            raw = response.read()
            return json.loads(raw) if raw else {}
    except HTTPError as error:
        raise RuntimeError(f"Google Play {method} returned HTTP {error.code}") from None
    except (URLError, TimeoutError, OSError, ValueError):
        raise RuntimeError("Google Play connection/result failed; mutation was not retried") from None


def publish(token, assets, commit=False):
    edit = play_request(token, "POST", "/edits", {})
    edit_id = edit.get("id", "")
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", edit_id):
        raise ValueError("Google Play returned an invalid edit ID")
    prefix = "/edits/" + edit_id
    committed = False
    try:
        listing = play_request(token, "GET", prefix + "/listings")
        languages = {item.get("language") for item in listing.get("listings", [])}
        if LOCALE not in languages:
            raise ValueError("Existing Korean listing is missing; refusing to create an unexpected locale")
        for kind in assets:
            play_request(token, "GET", prefix + "/listings/" + LOCALE + "/" + kind)
        if not commit:
            return {"mode": "read-only", "locale": LOCALE, "uploaded": False}
        for kind, files in assets.items():
            endpoint = prefix + "/listings/" + LOCALE + "/" + kind
            play_request(token, "DELETE", endpoint)
            for file in files:
                result = play_request(token, "POST", endpoint, asset=file)
                if not result.get("image", {}).get("id"):
                    raise ValueError("Google Play image upload did not return an image ID")
        play_request(token, "POST", prefix + ":validate", {})
        committed = True
        play_request(token, "POST", prefix + ":commit")
        return {"mode": "publish", "locale": LOCALE, "uploaded": True,
                "images": {key: len(value) for key, value in assets.items()}}
    finally:
        if not committed:
            try:
                play_request(token, "DELETE", prefix)
            except RuntimeError:
                pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    if args.check_only == args.publish:
        raise SystemExit("Select exactly one of --check-only or --publish")
    assets, summary = verified_assets()
    if args.check_only:
        print(json.dumps({"result": "assets_valid", "files": summary}, sort_keys=True))
        return
    token = os.environ.get("PLAY_ACCESS_TOKEN", "")
    if not token or any(ch.isspace() for ch in token):
        raise SystemExit("PLAY_ACCESS_TOKEN is missing or malformed")
    result = publish(token, assets, commit=True)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
