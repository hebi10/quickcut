#!/usr/bin/env bash
set -euo pipefail

test -s docs/store-assets/scenic-coast.png
mkdir -p app/src/androidTest/assets quickcut-store-scenic
ffmpeg -hide_banner -loglevel error -y \
  -loop 1 -i docs/store-assets/scenic-coast.png \
  -f lavfi -i 'anoisesrc=color=pink:amplitude=0.015:sample_rate=44100:seed=42' \
  -vf "scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080,zoompan=z='1.03+0.00012*on':x='(iw-iw/zoom)*on/239':y='ih/2-(ih/zoom/2)':d=240:s=1280x720:fps=30" \
  -t 8 -frames:v 240 -c:v libx264 -threads 2 -preset ultrafast -profile:v baseline -bf 0 -refs 1 -pix_fmt yuv420p \
  -g 60 -keyint_min 60 -sc_threshold 0 -c:a aac -b:a 64k -shortest \
  -movflags +faststart app/src/androidTest/assets/quickcut_store_scenic.mp4

ffprobe -v error -show_streams -show_format -of json \
  app/src/androidTest/assets/quickcut_store_scenic.mp4 > quickcut-store-scenic/source-media.json
git rev-parse HEAD > quickcut-store-scenic/source-commit.txt
sha256sum docs/store-assets/scenic-coast.png \
  app/src/androidTest/assets/quickcut_store_scenic.mp4 > quickcut-store-scenic/source-sha256.txt
python3 - <<'PY'
import json
from pathlib import Path

record = json.loads(Path("quickcut-store-scenic/source-media.json").read_text())
video, = [stream for stream in record["streams"] if stream["codec_type"] == "video"]
audio, = [stream for stream in record["streams"] if stream["codec_type"] == "audio"]
assert video["codec_name"] == "h264" and video["pix_fmt"] == "yuv420p"
assert video["profile"] in {"Baseline", "Constrained Baseline"} and video["has_b_frames"] == 0
assert (video["width"], video["height"]) == (1280, 720)
assert video["r_frame_rate"] == "30/1" and int(video["nb_frames"]) == 240
assert audio["codec_name"] == "aac"
assert float(record["format"]["duration"]) == 8.0
print("New scenic source verified: baseline H.264/AAC, 1280x720, 30 fps, 240 frames, eight seconds.")
PY
