#!/usr/bin/env python3
"""Pin the iOS YuNet checkpoint and make its ONNX input size dynamic for Android."""

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort


SOURCE_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists")
    if hashlib.sha256(args.source.read_bytes()).hexdigest() != SOURCE_SHA256:
        parser.error("YuNet source differs from the iOS model")
    model = onnx.load(args.source)
    input_shape = model.graph.input[0].type.tensor_type.shape.dim
    for axis, label in ((2, "height"), (3, "width")):
        input_shape[axis].ClearField("dim_value")
        input_shape[axis].dim_param = label
    for output in model.graph.output:
        count = output.type.tensor_type.shape.dim[1]
        count.ClearField("dim_value")
        count.dim_param = "anchors_" + output.name.split("_")[-1]
    # Original fixed-size intermediate shape hints make ORT warn on valid smaller inputs.
    model.graph.ClearField("value_info")
    model.metadata_props.add(key="innolive.contract", value="privacy-yunet-2023mar-v1")
    model.metadata_props.add(key="innolive.checkpoint_sha256", value=SOURCE_SHA256)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, args.output)
    onnx.checker.check_model(args.output)
    old = ort.InferenceSession(str(args.source), providers=["CPUExecutionProvider"])
    new = ort.InferenceSession(str(args.output), providers=["CPUExecutionProvider"])
    sample = np.random.default_rng(329).random((1, 3, 640, 640), dtype=np.float32) * 255
    errors = [float(np.max(np.abs(a - b))) for a, b in zip(
        old.run(None, {"input": sample}), new.run(None, {"input": sample}))]
    smaller = new.run(None, {"input": sample[:, :, :320, :320]})
    if max(errors) > 1e-5 or smaller[0].shape != (1, 1600, 1):
        raise ValueError(f"YuNet conversion failed: {errors}, {smaller[0].shape}")
    report = {"checkpoint_sha256": SOURCE_SHA256,
              "onnx_sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
              "max_absolute_error": max(errors), "dynamic_320_shape": list(smaller[0].shape)}
    args.output.with_suffix(".manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
