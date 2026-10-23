#!/usr/bin/env python3
"""Run a bounded, local-only near-tail benchmark; no source is uploaded."""
import argparse
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parent.parent


def run(command, timeout=900):
    # ffmpeg/native errors may contain source names. Keep those out of reports.
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError(f"Local command failed (exit {result.returncode}); no source details recorded")
    return result.stdout


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.digest()


def probe(path):
    raw = json.loads(run(["ffprobe", "-v", "error", "-show_entries",
        "format=duration,size:stream=codec_type,codec_name,width,height,pix_fmt,r_frame_rate",
        "-of", "json", str(path)]))
    return raw


def fixture(directory, duration, profile):
    seed = directory / "seed.mp4"
    source = directory / "synthetic-source.mp4"
    if profile == "stress1080p":
        estimate = duration * 16_000_000 // 8
        required = round(estimate * 1.25) + 256 * 1024 * 1024
        print(f"Synthetic 1080p30/16Mbps estimate: {estimate} bytes; "
              f"free-space requirement: {required} bytes", file=sys.stderr, flush=True)
        if shutil.disk_usage(directory).free < required:
            raise RuntimeError("Insufficient disk space for selected stress profile")
        picture = "testsrc2=size=1920x1080:rate=30:duration=8"
        encoding = ["-preset", "veryfast", "-b:v", "16M", "-minrate", "16M",
                    "-maxrate", "16M", "-bufsize", "32M", "-g", "60", "-keyint_min", "60",
                    "-x264-params", "open-gop=0:nal-hrd=cbr:force-cfr=1"]
    else:
        picture = "color=c=navy:size=1920x1080:rate=10:duration=8"
        encoding = ["-preset", "ultrafast", "-crf", "35", "-g", "20", "-keyint_min", "20",
                    "-x264-params", "open-gop=0"]
    run(["ffmpeg", "-v", "error", "-f", "lavfi", "-i", picture, "-an", "-c:v", "libx264",
         "-threads", "1", "-pix_fmt", "yuv420p", "-sc_threshold", "0", "-bf", "0",
         *encoding, str(seed)])
    run(["ffmpeg", "-v", "error", "-stream_loop", "-1", "-i", str(seed),
         "-map", "0:v:0", "-c", "copy", "-t", str(duration), "-movflags", "+faststart", str(source)])
    if profile == "timestamp" and source.stat().st_size > 64 * 1024 * 1024:
        raise RuntimeError("Synthetic fixture exceeded its 64 MiB bound")
    return source


def execute(args):
    if not 16 <= args.duration_seconds <= 10800:
        raise RuntimeError("Synthetic duration must be between 16 and 10800 seconds")
    if args.runs < 1 or args.runs > 10:
        raise RuntimeError("Use between one and ten runs")
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    # A unique subdirectory prevents overwriting media or unrelated benchmark results.
    directory = Path(tempfile.mkdtemp(prefix="run-", dir=work))
    before = time.monotonic_ns()
    source = args.source.resolve(strict=True) if args.source else fixture(directory, args.duration_seconds, args.profile)
    if not source.is_file():
        raise RuntimeError("Source must be a regular local file")
    description = probe(source)
    duration_us = round(float(description["format"]["duration"]) * 1_000_000)
    start_us = args.start_us if args.start_us is not None else max(0, duration_us - 9_000_000)
    end_us = args.end_us if args.end_us is not None else duration_us - 2_000_000
    if not 0 <= start_us < end_us <= duration_us:
        raise RuntimeError("Requested range must be within the source duration")
    preparation_ms = (time.monotonic_ns() - before) // 1_000_000
    original = digest(source)
    report = {"schema": 1, "source_kind": "local" if args.source else "synthetic",
              "synthetic_profile": None if args.source else args.profile,
              "source_bytes": source.stat().st_size, "source_duration_us": duration_us,
              "streams": description["streams"], "preparation_ms": preparation_ms,
              "requested_duration_us": end_us - start_us, "runs": []}
    if not args.prepare_only:
        # Compile separately: compilation is never counted as export latency.
        manifest = ROOT / "native/quickcut-smartcut/Cargo.toml"
        run(["cargo", "+1.98.0", "build", "--manifest-path", str(manifest), "--locked",
             "--release", "--example", "large_source_benchmark"], timeout=1800)
        target = Path(os.environ.get("CARGO_TARGET_DIR", manifest.parent / "target"))
        if not target.is_absolute():
            target = Path.cwd() / target
        binary = target / "release/examples/large_source_benchmark"
        for number in range(args.runs):
            output = directory / f"export-{number + 1}.mp4"
            metrics = json.loads(run([str(binary), str(source), str(output),
                                     str(start_us), str(end_us), str(duration_us)]))
            measured = probe(output)
            # Decode the entire exported interval independently; no source decode or upload.
            before = time.monotonic_ns()
            run(["ffmpeg", "-v", "error", "-xerror", "-i", str(output), "-map", "0:v:0",
                 "-map", "0:a?", "-f", "null", "-"])
            metrics["independent_decode_ms"] = (time.monotonic_ns() - before) // 1_000_000
            metrics["output_duration_us"] = round(float(measured["format"]["duration"]) * 1_000_000)
            if abs(metrics["output_duration_us"] - metrics["retained_duration_us"]) > 100_000:
                raise RuntimeError("Export duration differed from recommended interval")
            if metrics["encoded_video_packets"] != 0:
                raise RuntimeError("Copy benchmark unexpectedly encoded video")
            metrics["independent_decode_passed"] = True
            report["runs"].append(metrics)
    report["source_unchanged"] = digest(source) == original
    if not report["source_unchanged"]:
        raise RuntimeError("Source changed during benchmark")
    report["engine_executed"] = not args.prepare_only
    destination = directory / "summary.json"
    destination.write_text(json.dumps(report, indent=2) + "\n")
    print(destination.relative_to(work))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Optional local original; read only, never uploaded")
    parser.add_argument("--work-dir", type=Path, default=ROOT / "build/large-source-benchmark")
    parser.add_argument("--profile", choices=["timestamp", "stress1080p"], default="timestamp",
                        help="Synthetic timestamp fixture or explicit 1080p30/16Mbps I/O stress profile")
    parser.add_argument("--duration-seconds", type=int, default=10800, help="Synthetic duration, maximum 10800")
    parser.add_argument("--start-us", type=int)
    parser.add_argument("--end-us", type=int)
    parser.add_argument("--runs", type=int, default=3, help="Each process has a cold application analysis cache")
    parser.add_argument("--prepare-only", action="store_true", help="Validate fixture only; does not test the engine")
    args = parser.parse_args()
    try:
        execute(args)
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired):
        parser.exit(1, "Local benchmark failed or declined. No source paths or raw errors were recorded.\n")


if __name__ == "__main__":
    main()
