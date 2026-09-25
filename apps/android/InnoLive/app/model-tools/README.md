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
