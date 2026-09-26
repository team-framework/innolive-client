#!/usr/bin/env python3
"""Convert the pinned iOS AdaFace weights to LiteRT and verify original-model parity."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import types

import litert_torch
import numpy as np
import torch
from ai_edge_litert.interpreter import Interpreter

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


def gpu_attention(self, inputs, context, bucket_ids):
    """Same attention and keypoint lookup using rank <=4 tensors and one-hot matmul."""
    batch, tokens, dimensions = inputs.shape
    query, key, value = [part.reshape(batch, tokens, self.num_heads, -1).permute(0, 2, 1, 3)
                         for part in self.qkv(inputs).chunk(3, dim=-1)]
    attention = (query * self.scale) @ key.transpose(-2, -1)
    lookup = self._gpu_lookup.to(context.dtype)
    rows = context.permute(2, 0, 1, 3).reshape(tokens, batch * self.num_heads, context.shape[-1])
    bias = (rows @ lookup).permute(1, 0, 2).reshape(batch, self.num_heads, tokens, tokens)
    attention = self.attn_drop((attention + bias).softmax(dim=-1))
    output = (attention @ value).transpose(1, 2).reshape(batch, tokens, dimensions)
    return self.proj_drop(self.proj(output))


def gpu_contexts(self, keypoints, dtype):
    centers = self._patch_centers.to(dtype=dtype)
    relative = (centers - keypoints.unsqueeze(1)).flatten(2)
    contexts = self.keypoint_linear(relative)
    return tuple(part.reshape(keypoints.shape[0], -1, 16, 49).permute(0, 2, 1, 3)
                 for part in contexts.split(16 * 49, dim=-1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ai-repo", required=True, type=Path)
    parser.add_argument("--checkpoint", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--from-fp32", type=Path, help="Reuse an existing FP32 export; still validate against unchanged PyTorch")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists")
    if hashlib.sha256(args.checkpoint.read_bytes()).hexdigest() != CHECKPOINT_SHA256:
        parser.error("Checkpoint differs from the iOS face recognizer")
    source = args.ai_repo / "service/adaface_backbones.py"
    spec = importlib.util.spec_from_file_location("adaface_export_backbones", source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    backbone = module.build_adaface_backbone("vit_base_kprpe").eval()
    state = torch.load(args.checkpoint, map_location="cpu", weights_only=True, mmap=True)
    backbone.load_state_dict(module.checkpoint_backbone_state("vit_base_kprpe", state.get("state_dict", state)), strict=True)
    lookup = torch.nn.functional.one_hot(backbone._rpe_bucket_ids, 49).permute(0, 2, 1).float()
    for block in backbone.blocks:
        block.attn.register_buffer("_gpu_lookup", lookup, persistent=False)
        block.attn.forward = types.MethodType(gpu_attention, block.attn)
    backbone._keypoint_contexts = types.MethodType(gpu_contexts, backbone)
    model = Recognizer(backbone).eval()
    points = np.array([[[.34, .46], [.66, .46], [.50, .64], [.37, .82], [.63, .82]]], dtype=np.float32)
    rng = np.random.default_rng(283)
    pixels = rng.random((1, 3, 112, 112), dtype=np.float32) * 2 - 1
    inputs = (torch.from_numpy(pixels), torch.from_numpy(points))
    print("Converting unchanged ViT-KP-RPE and flip fusion", flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fp32 = args.from_fp32 or args.output.with_name(args.output.stem + "-fp32.tflite")
    if args.from_fp32 is None:
        if fp32.exists():
            parser.error("FP32 intermediate already exists")
        with torch.inference_mode():
            converted = litert_torch.convert(model, inputs, enable_x64=False)
        converted.export(str(fp32))
    from ai_edge_quantizer import quantizer, qtyping
    weights = quantizer.Quantizer(str(fp32))
    for op in (qtyping.TFLOperationName.CONV_2D, qtyping.TFLOperationName.FULLY_CONNECTED):
        weights.add_weight_only_config(".*", op, 16, algorithm_key="float_casting")
    weights.quantize(serialize_to_path=str(args.output))
    # Verify against the unchanged source implementation, not the rewritten gather.
    for block in backbone.blocks:
        block.attn.forward = types.MethodType(module.KPRPEAttention.forward, block.attn)
    backbone._keypoint_contexts = types.MethodType(module.ViTBaseKPRPE._keypoint_contexts, backbone)
    interpreter = Interpreter(model_path=str(args.output), num_threads=4)
    interpreter.allocate_tensors()
    details = interpreter.get_input_details()
    checks = []
    for sample in range(3):
        image = rng.random((1, 3, 112, 112), dtype=np.float32) * 2 - 1
        landmarks = np.clip(points + rng.normal(0, .015, points.shape), 0, 1).astype(np.float32)
        for detail in details:
            shape = tuple(detail["shape"])
            interpreter.set_tensor(detail["index"], image if shape == (1, 3, 112, 112) else landmarks)
        interpreter.invoke()
        actual = interpreter.get_tensor(interpreter.get_output_details()[0]["index"]).reshape(-1)
        with torch.inference_mode():
            reference = model(torch.from_numpy(image), torch.from_numpy(landmarks)).numpy().reshape(-1)
        cosine = float(np.dot(reference, actual) / (np.linalg.norm(reference) * np.linalg.norm(actual)))
        error = float(np.max(np.abs(actual - reference)))
        checks.append({"sample": sample, "cosine": cosine, "max_abs": error})
        if not np.isfinite(actual).all() or cosine < .999 or error > .01:
            raise ValueError(f"Conversion parity failed: {checks[-1]}")
    report = {"checkpoint_sha256": CHECKPOINT_SHA256,
              "model_sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
              "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
              "torch": torch.__version__, "conversion_checks": checks,
              "graph_changes": "Equivalent keypoint lookup via one-hot matmul; rank<=4 attention; int32 indices; unchanged weights",
              "weight_storage": "FP16 Conv/FC weights, no training; FP32 runtime or FP16 with FP32 accumulation",
              "litert_torch": "0.9.4", "litert": "2.2.0", "ai_edge_quantizer": "0.9.0",
              "inputs": [{"name": d["name"], "shape": d["shape"].tolist()} for d in details],
              "limitations": "CPU conversion parity only. Android GPU correctness and latency require device tests."}
    args.output.with_suffix(".manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
