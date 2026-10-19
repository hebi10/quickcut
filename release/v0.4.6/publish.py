"""Publish the byte-identical, externally signed QuickCut 0.4.6 artifacts.

The patch contains only ZIP metadata, public signatures, and copy ranges.
It contains no keystore, password, or private signing key.
"""
import base64
import gzip
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = "hebi10/quickcut"
HEAD = "c4193403cf07246007349a973db44ed9c1bc9b23"
TREE = "5de0491b28ea63ce2c56ef9dc2e3b3b44315f462"
RELEASE_ID = 416939531
TAG = "v0.4.6"
BUILD = 39205413554
ANDROID = 39205413452
FILES = {
    "QuickCut-v0.4.6-release.apk": (73027100, "c25402b912c56f11eadca2b1777a7cbcb341afc0c170c14c6211055ca6277ba2"),
    "QuickCut-v0.4.6-source.tar.gz": (54704562, "2e949c9a8d6993c8773e36c3283f98afe13d50f5748bb7d5ab0ea0656226d862"),
    "QuickCut-v0.4.6-source-guide.md": (76858, "93c7d19b6b4a437c3fbcdfa7147520b7555352454afdc463d98582a1a041724a"),
    "SHA256SUMS.txt": (288, "caa3357c05a16014448306650502cdf7213b9bd96341a842f4d5379b5cfbaa0c"),
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def gh(*args):
    return subprocess.check_output(["gh", *map(str, args)], text=True)


def api(path):
    return json.loads(gh("api", "repos/" + REPO + "/" + path))


def verify_files(directory):
    for name, (size, digest) in FILES.items():
        data = (directory / name).read_bytes()
        assert len(data) == size and sha256(data) == digest, name


def prepare(unsigned_file, source_file, output):
    output.mkdir()
    patch = json.loads(gzip.decompress(base64.b64decode(
        (HERE / "signed-apk-patch.json.gz.b64").read_bytes())))
    unsigned = unsigned_file.read_bytes()
    assert sha256(unsigned) == patch["unsignedSha256"]
    parts = []
    for segment in patch["segments"]:
        if "data" in segment:
            parts.append(base64.b64decode(segment["data"], validate=True))
        else:
            start, length = segment["offset"], segment["length"]
            assert 0 <= start <= start + length <= len(unsigned)
            parts.append(unsigned[start:start + length])
    signed = b"".join(parts)
    assert len(signed) == patch["signedBytes"]
    assert sha256(signed) == patch["signedSha256"]
    (output / "QuickCut-v0.4.6-release.apk").write_bytes(signed)
    shutil.copyfile(source_file, output / "QuickCut-v0.4.6-source.tar.gz")
    for name in ("QuickCut-v0.4.6-source-guide.md", "SHA256SUMS.txt"):
        shutil.copyfile(HERE / name, output / name)
    verify_files(output)


def verify_release(release):
    assert release["id"] == RELEASE_ID and release["tag_name"] == TAG
    assert release["name"] == "QuickCut v0.4.6" and not release["prerelease"]
    assets = {asset["name"]: asset for asset in release["assets"]}
    assert len(assets) == len(release["assets"])
    assert set(assets) <= set(FILES), "Unexpected release asset; refusing to overwrite"
    for name, asset in assets.items():
        size, digest = FILES[name]
        assert asset["state"] == "uploaded" and asset["size"] == size, name
        assert asset["digest"] == "sha256:" + digest, name
    return assets


def main():
    with tempfile.TemporaryDirectory(prefix="quickcut-publish-") as temporary:
        work = Path(temporary)
        output = work / "release"
        if len(sys.argv) == 4 and sys.argv[1] == "--verify-local":
            prepare(Path(sys.argv[2]), Path(sys.argv[3]), output)
            print("All four release files reconstructed with exact expected SHA-256 and size.")
            return
        assert len(sys.argv) == 1
        for run_id in (BUILD, ANDROID):
            run = api("actions/runs/" + str(run_id))
            assert run["head_sha"] == HEAD
            assert run["status"] == "completed" and run["conclusion"] == "success"
        assert api("git/commits/" + HEAD)["tree"]["sha"] == TREE
        initial = api("releases/" + str(RELEASE_ID))
        existing = verify_release(initial)
        assert initial["draft"] or set(existing) == set(FILES)
        for name, directory in (("quickcut-release-unsigned", "unsigned"),
                                ("quickcut-corresponding-source", "source")):
            gh("run", "download", BUILD, "--repo", REPO,
               "--name", name, "--dir", work / directory)
        unsigned = list((work / "unsigned").rglob("*.apk"))
        sources = list((work / "source").rglob("*.tar.gz"))
        assert len(unsigned) == len(sources) == 1
        identity_path = list((work / "source").rglob("source-identity.json"))
        assert len(identity_path) == 1
        identity = json.loads(identity_path[0].read_text())
        assert identity["gitTree"] == TREE and identity["signingSecretsIncluded"] is False
        prepare(unsigned[0], sources[0], output)
        for name in FILES:
            if name not in existing:
                gh("release", "upload", TAG, output / name, "--repo", REPO)
        staged = api("releases/" + str(RELEASE_ID))
        assert set(verify_release(staged)) == set(FILES)
        if staged["draft"]:
            gh("release", "edit", TAG, "--repo", REPO, "--target", HEAD,
               "--title", "QuickCut v0.4.6", "--notes-file", HERE / "release-notes.md",
               "--draft=false", "--latest")
        final = api("releases/" + str(RELEASE_ID))
        assert not final["draft"] and set(verify_release(final)) == set(FILES)
        assert api("git/ref/tags/" + TAG)["object"]["sha"] == HEAD
        gh("release", "download", TAG, "--repo", REPO,
           "--pattern", "*", "--dir", work / "downloaded")
        verify_files(work / "downloaded")
        result = {"url": final["html_url"], "tagCommit": HEAD,
                  "downloadedAssetsVerified": len(FILES), "files": FILES}
        print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
