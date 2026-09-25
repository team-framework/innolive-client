# Android privacy detector export

Use the same `innolive-ai/models/best.pt` checkpoint as the iOS privacy detector.
The converter checks its SHA-256 before exporting, verifies the ONNX graph's
output shapes, then compares both outputs against PyTorch on a fixed synthetic
input. It never replaces an existing output file.

```sh
python3 -m venv /tmp/innolive-android-model-venv
/tmp/innolive-android-model-venv/bin/pip install -r model-tools/requirements.txt
/tmp/innolive-android-model-venv/bin/python model-tools/export_detector.py \
  --checkpoint /path/to/innolive-ai/models/best.pt \
  --output /tmp/privacy-detector.onnx
shasum -a 256 /tmp/privacy-detector.onnx
```

Compare the generated manifest and checksum with
`src/main/assets/privacy-detector.manifest.json`. The Android runtime pins
that checksum in `PrivacyOnnxModel.kt`. Copy the model into assets only after
checking the new output and changing the pinned checksum intentionally.

## Local face models

The local face workflow uses the same YuNet 2023mar checkpoint and ViT-Base
KP-RPE WebFace12M AdaFace checkpoint as iOS. The AdaFace checkpoint is not in
this repository; use the official source and verify its pinned SHA-256 in the
export script. The trusted `innolive-ai` source checkout supplies the backbone
definition. Both converters refuse to overwrite an existing output.

```sh
/tmp/innolive-android-model-venv/bin/python model-tools/export_yunet.py \
  --source /path/to/yunet.onnx --output /tmp/privacy-yunet.onnx
/tmp/innolive-android-model-venv/bin/python model-tools/export_face.py \
  --ai-repo /path/to/innolive-ai --checkpoint /path/to/adaface/model.pt \
  --output /tmp/privacy-face.onnx
```

The converted AdaFace ONNX is 227 MB and is tracked with Git LFS. Android
copies it to the app's no-backup directory before loading it from a file to
avoid another 227 MB Java byte array. Check both manifests and update the
runtime checksums intentionally before replacing either app asset.
