#!/usr/bin/env python3
"""Export the existing two-class YOLO26 segmentation model for the local iOS lab.

Run with the versions in requirements-ios-privacy.txt. No training or downloading
of weights is performed. The checkpoint must be a trusted local PyTorch file.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile

import coremltools as ct
from ultralytics import YOLO


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] /
                        "apps/ios/InnoLive/InnoLive/Resources/PrivacyDetector.mlpackage")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output exists; choose a new path or remove the previously generated model first.")
    with tempfile.TemporaryDirectory(prefix="innolive-coreml-") as directory:
        checkpoint = Path(directory) / "PrivacyDetector.pt"
        shutil.copyfile(args.checkpoint, checkpoint)
        model = YOLO(str(checkpoint))
        if model.names != {0: "face", 1: "number_plate"}:
            raise ValueError(f"Unexpected classes: {model.names}")
        exported = model.export(format="coreml", imgsz=640, batch=1, dynamic=False,
                                quantize=16, nms=False, end2end=False, device="cpu")
        artifact = ct.models.MLModel(str(exported), skip_model_load=True)
        spec = artifact.get_spec()
        shapes = sorted(tuple(x.type.multiArrayType.shape) for x in spec.description.output)
        if shapes != sorted([(1, 38, 8400), (1, 32, 160, 160)]):
            raise ValueError(f"Unexpected output shapes: {shapes}")
        artifact.user_defined_metadata["innolive.contract"] = "privacy-segmentation-v1"
        artifact.user_defined_metadata["innolive.checkpoint_sha256"] = hashlib.sha256(args.checkpoint.read_bytes()).hexdigest()
        artifact.user_defined_metadata["innolive.preprocessing"] = "RGB, letterbox 640, pad 114; image input scales by 1/255"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        artifact.save(str(args.output))
        print(json.dumps({"output": str(args.output), "sha256": artifact.user_defined_metadata["innolive.checkpoint_sha256"],
                          "shapes": shapes}, indent=2))


if __name__ == "__main__":
    main()
