#!/usr/bin/env python3
"""Decode the actual Android native outputs when its MediaCodec lacks Main10.

The instrumented test still checks the Android extractor, native packet counts,
copied VCL bytes, and audio. This required host gate verifies every picture and
its HDR metadata using an independent decoder; it is not a device-playback test.
"""
import json
from pathlib import Path
import subprocess
import sys


def run(*args):
    result = subprocess.run(args, check=True, capture_output=True)
    if result.stderr:
        raise AssertionError(result.stderr.decode(errors="replace"))
    return result.stdout


def probe(path, *args):
    return json.loads(run("ffprobe", "-v", "error", *args, "-of", "json", str(path)))


COLOR = ("pix_fmt", "color_range", "color_space", "color_primaries", "color_transfer")
HDR_TYPES = {"Mastering display metadata", "Content light level metadata"}


def evidence(frame):
    return ({key: frame.get(key) for key in COLOR},
            [side for side in frame.get("side_data_list", []) if side["side_data_type"] in HDR_TYPES])


def verify(directory):
    for variant, matrix, transfer, primaries in (
        ("sdr", "bt709", "bt709", "bt709"),
        ("pq", "bt2020nc", "smpte2084", "bt2020"),
        ("hlg", "bt2020nc", "arib-std-b67", "bt2020"),
    ):
        source = directory / f"{variant}-source.mp4"
        output = directory / f"{variant}-output.mp4"
        assert source.is_file() and output.is_file(), f"Missing Android {variant} evidence"
        frames = []
        for path, count in ((source, 240), (output, 135)):
            video = probe(path, "-select_streams", "v:0", "-show_streams")["streams"][0]
            assert video["codec_name"] == "hevc" and video["profile"] == "Main 10", video
            assert tuple(video[key] for key in COLOR) == (
                "yuv420p10le", "tv", matrix, primaries, transfer), video
            decoded = probe(path, "-select_streams", "v:0", "-show_frames")["frames"]
            assert len(decoded) == count, (variant, path, len(decoded), count)
            frames.append([evidence(frame) for frame in decoded])
            run("ffmpeg", "-v", "error", "-xerror", "-err_detect", "explode", "-i", str(path),
                "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-")
            if path == output:
                assert abs(float(video["duration"]) - 4.5) <= 0.000001, video["duration"]
                for index, frame in enumerate(decoded):
                    assert abs(float(frame["best_effort_timestamp_time"]) - index / 30) < 0.000002
        assert frames[1] == frames[0][90:225], f"{variant}: per-frame color/HDR metadata changed"
        if variant == "pq":
            assert {side["side_data_type"] for side in frames[1][0][1]} == HDR_TYPES
        print(f"Android native {variant}: 135 frames decoded, 4.5s, Main10/color/HDR and audio verified")


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
