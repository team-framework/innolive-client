#!/usr/bin/env python3
"""Convert the server's pinned YuNet ONNX weights and verify against OpenCV DNN.

The adapter implements only the operators in this checksum-pinned graph.
"""
import argparse
import hashlib
import json
from pathlib import Path
import coremltools as ct
import cv2
import numpy as np
import onnx
from PIL import Image
import torch
import torch.nn.functional as F

SHA = '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4'


class YuNet(torch.nn.Module):
    def __init__(self, graph):
        super().__init__()
        arrays = {x.name: onnx.numpy_helper.to_array(x).copy() for x in graph.initializer}
        self.constants = {}
        for i, (name, value) in enumerate(arrays.items()):
            key = f'constant_{i}'
            self.register_buffer(key, torch.from_numpy(value))
            self.constants[name] = key
        self.nodes = []
        for node in graph.node:
            attrs = {a.name: onnx.helper.get_attribute_value(a) for a in node.attribute}
            if node.op_type == 'Reshape': attrs['shape'] = tuple(int(x) for x in arrays[node.input[1]])
            if node.op_type == 'Resize':
                assert attrs['mode'] == b'nearest' and attrs['coordinate_transformation_mode'] == b'asymmetric'
                assert attrs['nearest_mode'] == b'floor'
                attrs['scale'] = tuple(float(x) for x in arrays[node.input[2]][2:])
            if node.op_type == 'Conv': assert attrs.get('pads', [0]*4)[:2] == attrs.get('pads', [0]*4)[2:]
            assert node.op_type in {'Conv', 'Relu', 'MaxPool', 'Resize', 'Add', 'Transpose', 'Reshape', 'Sigmoid'}
            self.nodes.append((node.op_type, list(node.input), node.output[0], attrs))
        self.outputs = [x.name for x in graph.output]

    def forward(self, image):
        values = {name: getattr(self, key) for name, key in self.constants.items()}
        values['input'] = image
        for op, inputs, output, a in self.nodes:
            x = values[inputs[0]]
            if op == 'Conv':
                y = F.conv2d(x, values[inputs[1]], values[inputs[2]], stride=tuple(a.get('strides', [1, 1])),
                             padding=tuple(a.get('pads', [0]*4)[:2]), dilation=tuple(a.get('dilations', [1, 1])), groups=a.get('group', 1))
            elif op == 'Relu': y = F.relu(x)
            elif op == 'MaxPool': y = F.max_pool2d(x, tuple(a['kernel_shape']), tuple(a['strides']), ceil_mode=bool(a['ceil_mode']))
            elif op == 'Resize': y = F.interpolate(x, scale_factor=a['scale'], mode='nearest')
            elif op == 'Add': y = x + values[inputs[1]]
            elif op == 'Transpose': y = x.permute(a['perm'])
            elif op == 'Reshape': y = x.reshape(a['shape'])
            elif op == 'Sigmoid': y = torch.sigmoid(x)
            values[output] = y
        return tuple(values[name] for name in self.outputs)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--onnx', type=Path, required=True)
    p.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'apps/ios/InnoLive/InnoLive/Resources/PrivacyYuNet.mlpackage')
    a = p.parse_args()
    if a.output.exists(): p.error('Output already exists.')
    if hashlib.sha256(a.onnx.read_bytes()).hexdigest() != SHA: p.error('Unrecognized YuNet weights.')
    graph = onnx.load(a.onnx).graph
    model = YuNet(graph).eval()
    with torch.inference_mode(): traced = torch.jit.trace(model, torch.zeros(1, 3, 320, 320))
    converted = ct.convert(traced, convert_to='mlprogram', minimum_deployment_target=ct.target.iOS18,
                           inputs=[ct.ImageType(name='image', shape=(1, 3, ct.RangeDim(32, 2048, 320), ct.RangeDim(32, 2048, 320)), color_layout=ct.colorlayout.BGR)],
                           outputs=[ct.TensorType(name=name, dtype=np.float32) for name in model.outputs],
                           compute_precision=ct.precision.FLOAT32, compute_units=ct.ComputeUnit.CPU_ONLY)
    converted.user_defined_metadata.update({'innolive.contract': 'privacy-yunet-2023mar-v1', 'innolive.checkpoint_sha256': SHA})
    a.output.parent.mkdir(parents=True, exist_ok=True)
    converted.save(str(a.output))
    net = cv2.dnn.readNetFromONNX(str(a.onnx))
    rng = np.random.default_rng(284)
    results = []
    for h,w in [(320, 320), (512, 512), (384, 640)]:
        bgr = rng.integers(0, 256, (h,w,3), dtype=np.uint8)
        net.setInput(cv2.dnn.blobFromImage(bgr))
        reference = net.forward(model.outputs)
        actual = converted.predict({'image': Image.fromarray(bgr[:,:,::-1])})
        errors = {name: float(np.max(np.abs(reference[i] - actual[name]))) for i,name in enumerate(model.outputs)}
        if max(errors.values()) > 0.003: raise RuntimeError(f'OpenCV parity failed: {errors}')
        results.append({'height':h,'width':w,'max_abs_by_output':errors})
    report = {'checkpoint_sha256':SHA, 'package_bytes':sum(p.stat().st_size for p in a.output.rglob('*') if p.is_file()), 'checks':results}
    a.output.with_suffix('.manifest.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__ == '__main__': main()
