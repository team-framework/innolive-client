#!/usr/bin/env python3
"""Prepare an optional, local-only native Swift/OpenCV YuNet parity fixture.

Pass a public test image containing one face and the pinned YuNet ONNX file.
Camera captures and face-library files must not be used as repository fixtures.
Requires opencv-python and numpy in the model export environment.
"""
import argparse
import hashlib
import json
from pathlib import Path

import cv2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, type=Path)
    parser.add_argument("--onnx", required=True, type=Path)
    args = parser.parse_args()
    expected_sha = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
    if hashlib.sha256(args.onnx.read_bytes()).hexdigest() != expected_sha:
        raise ValueError("Expected the pinned YuNet 2023mar ONNX weights")
    image = cv2.imread(str(args.image))
    if image is None:
        raise ValueError("Cannot decode image")
    height, width = image.shape[:2]
    scale = min(1, 320 / max(width, height))
    image = cv2.resize(image, (round(width * scale), round(height * scale)), interpolation=cv2.INTER_AREA)
    height, width = image.shape[:2]
    detector = cv2.FaceDetectorYN.create(str(args.onnx), "", (width, height), 0.6, 0.3, 5000)
    _, faces = detector.detect(image)
    if faces is None or len(faces) != 1:
        raise ValueError("Fixture must contain exactly one detected face")
    output = Path(__file__).resolve().parents[1] / "apps/ios/InnoLive/InnoLiveTests/PrivacyFaceFixtures"
    output.mkdir(parents=True, exist_ok=True)
    # Lossless PNG gives Swift and OpenCV identical pixels despite JPEG decoder differences.
    if not cv2.imwrite(str(output / "yunet-sample.png"), image):
        raise ValueError("Cannot write fixture PNG")
    (output / "yunet-expected.json").write_text(json.dumps(faces[0].tolist()) + "\n")
    print(f"Prepared {width}x{height} native parity fixture in {output}")


if __name__ == "__main__":
    main()
