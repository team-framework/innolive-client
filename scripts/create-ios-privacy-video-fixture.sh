#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"
source_image=apps/ios/InnoLive/InnoLiveTests/PrivacyFaceFixtures/privacy-performance-face.png
output_video=apps/ios/InnoLive/InnoLive/Resources/PrivacyPerformanceFixture.mp4
expected_sha=88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5
if [ ! -f "$source_image" ]; then
  echo 'Prepare the public astronaut fixture documented in docs/ios-privacy-performance-2026-10-19.md.' >&2
  exit 1
fi
actual_sha="$(shasum -a 256 "$source_image" | cut -d ' ' -f 1)"
if [ "$actual_sha" != "$expected_sha" ]; then
  echo 'Unexpected face fixture SHA-256.' >&2
  exit 1
fi
# Animate a fixed photo; this is not natural facial motion or a camera-capture benchmark.
# Store the portrait scene rotated left, matching landscape sensor buffers with rotation 90.
ffmpeg -hide_banner -loglevel error -loop 1 -i "$source_image" \
  -f lavfi -i color=c=0x303030:s=1080x1920:r=30 \
  -filter_complex "[0:v]scale=900:900[face];[1:v][face]overlay=x='90+60*sin(2*PI*t/10)':y='480+60*cos(2*PI*t/10)':shortest=1,transpose=2,format=yuv420p[v]" \
  -map '[v]' -t 10 -r 30 -c:v libx264 -preset fast -crf 18 -movflags +faststart -y "$output_video"
ffprobe -v error -select_streams v:0 -show_entries stream=width,height,r_frame_rate,nb_frames:format=duration -of json "$output_video"
