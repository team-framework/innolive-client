# InnoLive on-device animal filters

Original Blender-authored rabbit and owl assets, created on the assistant's cloud computer with Blender 4.3.2. This package contains no downloaded artwork, textures, or models.

## Runtime integration

Use only these files in each native client:

- `build/runtime/rabbit.png` and `build/runtime/rabbit.json`
- `build/runtime/owl.png` and `build/runtime/owl.json`

Both PNGs are 1024 × 1024, transparent RGBA, with sRGB-encoded color and straight alpha. The front render includes the complete ears/tufts, an opaque animal face, and baked soft lighting. Composite locally over the client's existing privacy blur. Do not upload camera frames or enable animal effects in server-AI mode.

Sidecar coordinates use a normalized 0–1 top-left origin:

- `faceRect`: `[x, y, width, height]` of the main face shell, excluding ears/tufts. Align this rectangle with the tracked face rectangle.
- `anchor`: `[x, y]` of the midpoint between the model's eyes. Use for eye-landmark alignment when the tracker supplies landmarks; do not treat it as the face-bounding-box center.
- `width` and `height`: image pixel dimensions, both 1024.

When mapping by a tracked face rectangle `(tx, ty, tw, th)`, sprite width is `tw / faceRect.width`, height is `th / faceRect.height`, and top-left is `(tx - faceRect.x * spriteWidth, ty - faceRect.y * spriteHeight)`. Face tracking, mirroring, orientation, mask smoothing, and face-loss behavior remain client responsibilities. Keep the existing blur underneath because a stylized silhouette is not a privacy guarantee on its own.

Rabbit: faceRect `[0.2451456311, 0.4199029126, 0.5097087379, 0.5145631068]`; anchor `[0.5, 0.6262135922]`.
Owl: faceRect `[0.2390776699, 0.4211165049, 0.5218446602, 0.5169902913]`; anchor `[0.5, 0.6213592233]`.

## Editable 3D sources and archival exports

`build/source/rabbit.blend` and `build/source/owl.blend` contain individually named mesh parts, editable PBR materials, a transparent front orthographic camera, and a three-light studio setup. The camera saved in each source is the exact production front camera, not the three-quarter preview camera.

`build/runtime/rabbit.glb` and `build/runtime/owl.glb` are optional self-contained glTF 2.0 archival exports, not required by the current sprite runtime. They have no textures, external buffers, rigs, animations, lights, or cameras. All material alpha modes are OPAQUE.

- Rabbit: 5,310 authoring vertices; 10,484 triangles; 262,388-byte GLB.
- Owl: 5,607 authoring vertices; 11,022 triangles; 288,892-byte GLB.

Coordinates: Blender +X right, +Z up, -Y toward camera. The GLB export converts to +X right, +Y up, +Z toward camera. One model unit is the distance between eye centers. The rabbit eye center elevation is 0.11, owl is 0.13. This is a stylized head asset, not a deformable face mesh or ARKit/ARCore rig.

## Reproduce

Requires an existing Blender 4.3+ installation. No Blender add-on installation is required.

```sh
blender -b --factory-startup --python generate_animals.py -- --output ./build --size 1024 --samples 96
python3 validate_assets.py ./build
```

The validator uses Pillow. Generation itself uses Blender's built-in Python APIs only. Cycles CPU rendering does not require the optional denoiser.

`build/asset-manifest.json` documents the package. `build/validation-report.json` records successful asset-level checks. Previews in `build/previews/` are three-quarter renders of the actual geometry, not hand-drawn or AI-generated substitutes. The PNG sprites have been visually inspected along with the 3D previews.

## Verification limits

Asset geometry, dimensions, transparency, clipping bounds, sidecar ranges, opaque materials, file structure, absence of external dependencies, and polygon budget have been checked. Physical-device face tracking, frame rate, orientation/mirroring, filtering lifecycle, and server/on-device mode isolation must be verified by the client implementation. The assets alone do not establish those behaviors.

The repository retains the editable `.blend` sources and generation scripts.
The PNG/JSON runtime deliverables are also bundled in each native client.
Other `build/` files described above are reproducible local exports and validation
reports; they are not included in the feature commit.
