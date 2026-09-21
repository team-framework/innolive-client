#!/usr/bin/env python3
"""Export the pinned server ViT-KP-RPE backbone, including flip fusion, to Core ML.

Uses the existing trusted innolive-ai source; does not train or download weights.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import time

import coremltools as ct
import numpy as np
from PIL import Image
import torch

CHECKPOINT_SHA256 = '04b4bee1de7cefa9e97900f8449fca906d8afbab2029bd39cc5049d33e927ed9'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ai-repo', required=True, type=Path)
    parser.add_argument('--checkpoint', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] /
                        'apps/ios/InnoLive/InnoLive/Resources/PrivacyFaceRecognizer.mlpackage')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output already exists; use a different output path.')
    sha = hashlib.sha256(args.checkpoint.read_bytes()).hexdigest()
    if sha != CHECKPOINT_SHA256:
        parser.error('Checkpoint does not match the pinned ViT-KP-RPE WebFace12M model.')
    source = args.ai_repo / 'service/adaface_backbones.py'
    spec = importlib.util.spec_from_file_location('adaface_export_backbones', source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    backbone = module.build_adaface_backbone('vit_base_kprpe').eval()
    state = torch.load(args.checkpoint, map_location='cpu', weights_only=True, mmap=True)
    backbone.load_state_dict(module.checkpoint_backbone_state('vit_base_kprpe', state.get('state_dict', state)), strict=True)

    class Recognizer(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.backbone = backbone

        def forward(self, image, landmarks):
            mirrored = landmarks[:, [1, 0, 2, 4, 3], :]
            mirrored = torch.stack((1.0 - mirrored[:, :, 0], mirrored[:, :, 1]), dim=2)
            features, norms = self.backbone(torch.cat((image, torch.flip(image, dims=(3,))), dim=0),
                                           torch.cat((landmarks, mirrored), dim=0))
            fused = (features * norms).sum(dim=0, keepdim=True)
            return fused / torch.linalg.vector_norm(fused, dim=1, keepdim=True)

    model = Recognizer().eval()
    keypoints = np.array([[[.34, .46], [.66, .46], [.50, .64], [.37, .82], [.63, .82]]], dtype=np.float32)
    example = (torch.zeros(1, 3, 112, 112), torch.from_numpy(keypoints))
    with torch.inference_mode():
        traced = torch.jit.trace(model, example)
        converted = ct.convert(traced, convert_to='mlprogram', minimum_deployment_target=ct.target.iOS18,
                               inputs=[ct.ImageType(name='image', shape=(1, 3, 112, 112),
                                                    scale=1 / 127.5, bias=[-1, -1, -1], color_layout=ct.colorlayout.RGB),
                                       ct.TensorType(name='landmarks', shape=(1, 5, 2), dtype=np.float32)],
                               outputs=[ct.TensorType(name='embedding', dtype=np.float32)],
                               compute_precision=ct.precision.FLOAT16, compute_units=ct.ComputeUnit.CPU_ONLY)
    converted.user_defined_metadata.update({
        'innolive.contract': 'privacy-face-vit-kprpe-v1',
        'innolive.checkpoint_sha256': sha,
        'innolive.backbone_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'innolive.preprocessing': 'RGB112; x/127.5-1; bbox square 1.5x; landmarks top-left normalized; flip fusion included',
    })
    args.output.parent.mkdir(parents=True, exist_ok=True)
    converted.save(str(args.output))
    # Numerical conversion test only. Random inputs do not measure recognition quality.
    comparisons = []
    rng = np.random.default_rng(283)
    for index in range(3):
        pixels = rng.integers(0, 256, size=(112, 112, 3), dtype=np.uint8)
        landmarks = np.clip(keypoints + rng.normal(0, .015, keypoints.shape), 0, 1).astype(np.float32)
        tensor = torch.from_numpy((pixels.astype(np.float32) / 127.5 - 1).transpose(2, 0, 1).copy())[None]
        with torch.inference_mode():
            reference = model(tensor, torch.from_numpy(landmarks)).numpy().reshape(-1)
        start = time.perf_counter()
        actual = converted.predict({'image': Image.fromarray(pixels), 'landmarks': landmarks})['embedding'].reshape(-1)
        cosine = float(np.dot(reference, actual) / (np.linalg.norm(reference) * np.linalg.norm(actual)))
        error = float(np.max(np.abs(actual - reference)))
        comparisons.append({'input': index, 'cosine': cosine, 'max_abs': error,
                            'mac_cpu_ms': (time.perf_counter() - start) * 1000})
        if not np.isfinite(actual).all() or cosine < .999 or error > .01:
            raise RuntimeError(f'Conversion parity failed: {comparisons[-1]}')
    manifest = {'checkpoint_sha256': sha, 'parameters': sum(p.numel() for p in backbone.parameters()),
                'package_bytes': sum(p.stat().st_size for p in args.output.rglob('*') if p.is_file()),
                'torch': torch.__version__, 'coremltools': ct.__version__, 'conversion_checks': comparisons,
                'limitations': 'Random-input numerical parity only. No iPhone latency or recognition-accuracy claim.'}
    args.output.with_suffix('.manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
