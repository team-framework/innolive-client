#!/usr/bin/env python3
"""Export the same pinned YOLO face/plate checkpoint as the iOS privacy pipeline."""

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from ultralytics import YOLO


CHECKPOINT_SHA256 = "307e9b5895654d25d264903451abc4acad9ee30f5e2f2bf64af164a9f46bc115"
SHAPES = [(1, 38, 8400), (1, 32, 160, 160)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists; do not overwrite a previously validated model.")
    actual_sha = hashlib.sha256(args.checkpoint.read_bytes()).hexdigest()
    if actual_sha != CHECKPOINT_SHA256:
        parser.error("Checkpoint differs from the iOS model.")

    model = YOLO(str(args.checkpoint))
    if model.names != {0: "face", 1: "number_plate"} or model.task != "segment":
        raise ValueError("Unexpected model classes or task")
    exported = Path(model.export(format="onnx", imgsz=640, batch=1, dynamic=False,
                                 nms=False, end2end=False, simplify=False, device="cpu"))
    graph = onnx.load(exported)
    shapes = [tuple(dim.dim_value for dim in output.type.tensor_type.shape.dim)
              for output in graph.graph.output]
    if sorted(shapes) != sorted(SHAPES):
        raise ValueError(f"Unexpected output shapes: {shapes}")

    graph.metadata_props.add(key="innolive.contract", value="privacy-segmentation-v1")
    graph.metadata_props.add(key="innolive.checkpoint_sha256", value=actual_sha)
    graph.metadata_props.add(key="innolive.preprocessing", value="RGB NCHW float32 /255; letterbox 640; pad 114")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(graph, args.output)
    onnx.checker.check_model(args.output)

    # Numeric parity catches export changes which preserve shape but alter output meaning.
    sample = np.random.default_rng(320).random((1, 3, 640, 640), dtype=np.float32)
    reference_model = YOLO(str(args.checkpoint)).model.eval()
    reference_model.model[-1].end2end = False
    with torch.inference_mode():
        reference = reference_model(torch.from_numpy(sample))[0]
    session = ort.InferenceSession(str(args.output), providers=["CPUExecutionProvider"])
    actual = session.run(None, {session.get_inputs()[0].name: sample})
    maximum_errors = [float(np.max(np.abs(a.detach().numpy() - b)))
                      for a, b in zip(reference, actual)]
    if len(maximum_errors) != 2 or not all(np.isfinite(maximum_errors)) or max(maximum_errors) > 0.005:
        raise ValueError(f"PyTorch/ONNX parity failed: {maximum_errors}")
    report = {"checkpoint_sha256": actual_sha, "onnx_sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
              "shapes": shapes, "max_absolute_errors": maximum_errors,
              "limitations": "Synthetic numeric parity only; real-device latency and privacy quality require separate tests."}
    args.output.with_suffix(".manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
