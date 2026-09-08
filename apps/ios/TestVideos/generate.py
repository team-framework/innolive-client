#!/usr/bin/env python3
"""Generate repeatable input-path fixtures; no face-accuracy ground truth."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--portrait", required=True, type=Path,
                        help="A permitted image containing one centered face")
    parser.add_argument("--output", required=True, type=Path,
                        help="New or empty output directory")
    args = parser.parse_args()
    portrait = args.portrait.resolve(strict=True)
    if not portrait.is_file():
        parser.error("portrait must be a file")
    for binary in ("ffmpeg", "ffprobe"):
        if shutil.which(binary) is None:
            parser.error(f"{binary} must be installed")
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error("output directory must be empty")
    output.mkdir(parents=True, exist_ok=True)

    # 6 seconds at 24 FPS, 640x640. Motion is a moving crop of a still image.
    still = "scale=640:640:force_original_aspect_ratio=decrease,pad=640:640:(ow-iw)/2:(oh-ih)/2,setsar=1"
    scenarios = {
        "single-face": still,
        "multiple-faces": "scale=320:640:force_original_aspect_ratio=decrease,pad=320:640:(ow-iw)/2:(oh-ih)/2,split[a][b];[a][b]hstack,setsar=1",
        "moving-face": "scale=800:800:force_original_aspect_ratio=increase,crop=640:640:x='80+80*sin(t)':y=80,setsar=1",
        "partial-face": still + ",drawbox=x=iw/2:y=0:w=iw/2:h=ih:color=black:t=fill",
        "low-light": still + ",eq=brightness=-0.35:contrast=0.7",
        "fast-motion": "scale=800:800:force_original_aspect_ratio=increase,crop=640:640:x='80+80*sin(12*t)':y='80+80*cos(12*t)',setsar=1",
        "no-face": None,
    }
    records = []
    for name, graph in scenarios.items():
        target = output / f"{name}.mp4"
        command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-n"]
        if graph is None:
            command += ["-f", "lavfi", "-i", "testsrc2=size=640x640:rate=24:duration=6"]
        else:
            command += ["-loop", "1", "-framerate", "24", "-i", str(portrait), "-filter_complex", graph]
        command += ["-t", "6", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-r", "24",
                    "-map_metadata", "-1", "-movflags", "+faststart", str(target)]
        subprocess.run(command, check=True)
        probe = json.loads(subprocess.check_output([
            "ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames",
            "-show_entries", "stream=width,height,r_frame_rate,nb_read_frames:format=duration",
            "-of", "json", str(target),
        ]))
        stream = probe["streams"][0]
        if (stream["width"], stream["height"], stream["r_frame_rate"], stream["nb_read_frames"]) != (640, 640, "24/1", "144"):
            raise RuntimeError(f"Unexpected output format: {target.name}: {stream}")
        records.append({"file": target.name, "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                        "probe": probe, "filter": graph})
    manifest = {
        "purpose": "Deterministic input-path regression, not recognition accuracy or realistic motion",
        "source_sha256": hashlib.sha256(portrait.read_bytes()).hexdigest(),
        "ffmpeg_version": subprocess.check_output(["ffmpeg", "-version"], text=True).splitlines()[0],
        "files": records,
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Verified {len(records)} videos: {output}")


if __name__ == "__main__":
    main()
