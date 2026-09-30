#!/usr/bin/env python3
"""Export iOS's pinned AdaFace ViT-KP-RPE with flip fusion to Android ONNX FP16."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnxconverter_common import float16


CHECKPOINT_SHA256 = "04b4bee1de7cefa9e97900f8449fca906d8afbab2029bd39cc5049d33e927ed9"


class Recognizer(torch.nn.Module):
    def __init__(self, backbone):
        super().__init__()
        self.backbone = backbone

    def forward(self, image, landmarks):
        mirrored = landmarks[:, [1, 0, 2, 4, 3], :]
        mirrored = torch.stack((1.0 - mirrored[:, :, 0], mirrored[:, :, 1]), dim=2)
        features, norms = self.backbone(torch.cat((image, torch.flip(image, dims=(3,))), dim=0),
                                       torch.cat((landmarks, mirrored), dim=0))
        fused = (features * norms).sum(dim=0, keepdim=True)
        return fused / torch.linalg.vector_norm(fused, dim=1, keepdim=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ai-repo", required=True, type=Path)
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists")
    sha = hashlib.sha256(args.checkpoint.read_bytes()).hexdigest()
    if sha != CHECKPOINT_SHA256:
        parser.error("Checkpoint differs from the iOS face recognizer")
    source = args.ai_repo / "service/adaface_backbones.py"
    spec = importlib.util.spec_from_file_location("adaface_export_backbones", source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    backbone = module.build_adaface_backbone("vit_base_kprpe").eval()
    state = torch.load(args.checkpoint, map_location="cpu", weights_only=True, mmap=True)
    backbone.load_state_dict(module.checkpoint_backbone_state("vit_base_kprpe", state.get("state_dict", state)), strict=True)
    model = Recognizer(backbone).eval()
    landmarks = np.array([[[.34, .46], [.66, .46], [.50, .64], [.37, .82], [.63, .82]]], dtype=np.float32)
    sample = np.random.default_rng(283).random((1, 3, 112, 112), dtype=np.float32) * 2 - 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    full_precision = args.output.with_name(args.output.stem + "-fp32.onnx")
    with torch.inference_mode():
        reference = model(torch.from_numpy(sample), torch.from_numpy(landmarks)).numpy()
        torch.onnx.export(model, (torch.from_numpy(sample), torch.from_numpy(landmarks)), str(full_precision),
                          input_names=["image", "landmarks"], output_names=["embedding"], opset_version=20,
                          dynamo=False)
    graph = onnx.load(full_precision)
    if [tuple(dim.dim_value for dim in output.type.tensor_type.shape.dim) for output in graph.graph.output] != [(1, 512)]:
        raise ValueError("Unexpected embedding shape")
    converted = float16.convert_float_to_float16(graph, keep_io_types=True,
                                                  disable_shape_infer=False, op_block_list=[])
    # The converter changes one explicit PyTorch float Cast's output to FP16
    # but leaves its ONNX `to` attribute at FP32. Reconcile it with the inferred type.
    types = {value.name: value.type.tensor_type.elem_type for value in
             list(converted.graph.input) + list(converted.graph.output) + list(converted.graph.value_info)}
    fixed = 0
    for node in converted.graph.node:
        if node.op_type == "Cast" and types.get(node.output[0]) == onnx.TensorProto.FLOAT16:
            for attribute in node.attribute:
                if attribute.name == "to" and attribute.i == onnx.TensorProto.FLOAT:
                    attribute.i = onnx.TensorProto.FLOAT16
                    fixed += 1
    if fixed != 1:
        raise ValueError(f"Unexpected FP16 cast correction count: {fixed}")
    converted.metadata_props.add(key="innolive.contract", value="privacy-face-vit-kprpe-v1")
    converted.metadata_props.add(key="innolive.checkpoint_sha256", value=sha)
    converted.metadata_props.add(key="innolive.backbone_sha256", value=hashlib.sha256(source.read_bytes()).hexdigest())
    converted.metadata_props.add(key="innolive.preprocessing", value="RGB112 /127.5-1; square 1.5x; normalized landmarks; flip fusion")
    onnx.save(converted, args.output)
    onnx.checker.check_model(args.output)
    session = ort.InferenceSession(str(args.output), providers=["CPUExecutionProvider"])
    actual = session.run(None, {"image": sample, "landmarks": landmarks})[0]
    cosine = float(np.sum(reference * actual) / (np.linalg.norm(reference) * np.linalg.norm(actual)))
    error = float(np.max(np.abs(reference - actual)))
    if not np.isfinite(actual).all() or cosine < .999 or error > .01:
        raise ValueError(f"PyTorch/ONNX parity failed: cosine={cosine}, error={error}")
    report = {"checkpoint_sha256": sha, "onnx_sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
              "embedding_shape": [1, 512], "cosine": cosine, "max_absolute_error": error,
              "limitations": "Synthetic parity only; recognition accuracy and Android latency require device tests."}
    args.output.with_suffix(".manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    full_precision.unlink()
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
