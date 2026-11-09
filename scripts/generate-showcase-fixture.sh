#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
image="$repo_dir/scripts/fixtures/quickcut-showcase.webp"
output="$repo_dir/app/src/androidTest/assets/quickcut_showcase.mp4"
expected_sha256=1d63928d144a015742e63989105b39566802c519a402942a9590802121a06e45

test -s "$image"
actual_sha256=$(sha256sum "$image" | awk '{ print $1 }')
if [[ "$actual_sha256" != "$expected_sha256" ]]; then
  echo "::error::QuickCut showcase source image checksum mismatch" >&2
  exit 1
fi
command -v ffmpeg >/dev/null
command -v ffprobe >/dev/null
mkdir -p "$(dirname -- "$output")"

ffmpeg -hide_banner -loglevel error -y \
  -loop 1 -framerate 30 -i "$image" \
  -f lavfi -i sine=frequency=880:sample_rate=44100 \
  -t 8 \
  -vf "zoompan=z='min(max(zoom,pzoom)+0.00018,1.045)':d=1:s=320x180:fps=30,pad=320:240:0:30:color=0xf2efe9,format=yuv420p" \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -g 60 -keyint_min 60 -sc_threshold 0 \
  -c:a aac -b:a 64k -af volume=0.08 \
  -shortest -movflags +faststart "$output"

python3 - "$output" <<'PY'
import json
import subprocess
import sys

result = json.loads(subprocess.check_output([
    "ffprobe", "-v", "error",
    "-show_entries", "format=duration:stream=index,codec_type,codec_name,width,height,r_frame_rate",
    "-of", "json", sys.argv[1]
], text=True))
video = [stream for stream in result["streams"] if stream["codec_type"] == "video"]
audio = [stream for stream in result["streams"] if stream["codec_type"] == "audio"]
assert len(video) == 1 and video[0]["codec_name"] == "h264"
assert (video[0]["width"], video[0]["height"], video[0]["r_frame_rate"]) == (320, 240, "30/1")
assert len(audio) == 1 and audio[0]["codec_name"] == "aac"
assert abs(float(result["format"]["duration"]) - 8.0) < 0.05
print("Clean showcase MP4 verified: 8s, 320x240, H.264/AAC, 30fps")
PY
