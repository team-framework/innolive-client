#!/usr/bin/env python3
"""Export the pinned iOS/Android YOLO segmentation weights as FP32 LiteRT."""

import argparse
import hashlib
import json
from pathlib import Path

import litert_torch
import numpy as np
import onnxruntime as ort
import torch
from ai_edge_litert.interpreter import Interpreter
from ultralytics import YOLO


CHECKPOINT_SHA256 = "307e9b5895654d25d264903451abc4acad9ee30f5e2f2bf64af164a9f46bc115"
OUTPUT_SHAPES = {(1, 38, 8400), (1, 32, 160, 160)}


class Detector(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, pixels):
        return self.model(pixels)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--reference-onnx", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists; do not overwrite a validated model.")
    if sha256(args.checkpoint) != CHECKPOINT_SHA256:
        parser.error("Checkpoint differs from the iOS detector.")

    source = YOLO(str(args.checkpoint))
    if source.task != "segment" or source.names != {0: "face", 1: "number_plate"}:
        raise ValueError("Unexpected detector classes or task")
    network = source.model.eval()
    network.model[-1].end2end = False
    network.model[-1].export = True
    network.model[-1].format = "tflite"
    wrapper = Detector(network).eval()
    sample = torch.rand((1, 3, 640, 640), generator=torch.Generator().manual_seed(320))
    with torch.inference_mode():
        outputs = wrapper(sample)
        if {tuple(value.shape) for value in outputs} != OUTPUT_SHAPES:
            raise ValueError("Unexpected PyTorch output shapes")
        converted = litert_torch.convert(wrapper, (sample,), enable_x64=False)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        converted.export(str(args.output))

    reference = ort.InferenceSession(str(args.reference_onnx), providers=["CPUExecutionProvider"])
    interpreter = Interpreter(model_path=str(args.output), num_threads=4)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    if len(input_details) != 1 or tuple(input_details[0]["shape"]) != (1, 3, 640, 640):
        raise ValueError("Unexpected LiteRT input")
    if {tuple(output["shape"]) for output in output_details} != OUTPUT_SHAPES:
        raise ValueError("Unexpected LiteRT outputs")
    errors = []
    for seed in (3, 320):
        pixels = np.random.default_rng(seed).random((1, 3, 640, 640), dtype=np.float32)
        wanted = reference.run(None, {reference.get_inputs()[0].name: pixels})
        interpreter.set_tensor(input_details[0]["index"], pixels)
        interpreter.invoke()
        actual = [interpreter.get_tensor(item["index"]) for item in output_details]
        compared = []
        for expected in wanted:
            result = next((value for value in actual if value.shape == expected.shape), None)
            if result is None or not np.isfinite(result).all():
                raise ValueError("Missing or nonfinite LiteRT output")
            compared.append(float(np.max(np.abs(result - expected))))
        if compared[0] >= .005 or compared[1] >= .005:
            raise ValueError(f"ONNX/LiteRT output differs: {compared}")
        errors.append(compared)
    report = {"checkpoint_sha256": CHECKPOINT_SHA256,
              "reference_onnx_sha256": sha256(args.reference_onnx),
              "litert_sha256": sha256(args.output), "output_shapes": sorted(OUTPUT_SHAPES),
              "max_absolute_errors": errors,
              "limitations": "Host numeric parity only; Android GPU availability and speed are checked at runtime."}
    args.output.with_suffix(".manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
