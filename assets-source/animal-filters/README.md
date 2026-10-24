# InnoLive on-device animal filters

Original Blender-authored rabbit and owl assets, created on the assistant's cloud computer with Blender 4.3.2. This package contains no downloaded artwork, textures, or models.

## Runtime integration

Use only these files in each native client:

- `build/runtime/rabbit.png` and `build/runtime/rabbit.json`
- `build/runtime/owl.png` and `build/runtime/owl.json`

Each PNG is a transparent RGBA yaw-view atlas (schema version 2) with sRGB-encoded color and straight alpha. Ten cells hold the same head at yaw 0°–90° in 10° steps, rendered by one fixed orthographic camera at one scale. Positive yaw turns the muzzle toward image right; clients mirror a cell for negative yaw, so the studio lights are left/right symmetric. Composite locally over the client's existing privacy blur. Do not upload camera frames or enable animal effects in server-AI mode.

| 항목 | 토끼 | 부엉이 |
| --- | --- | --- |
| atlas | 2200 × 1360 | 2240 × 928 |
| cell | 440 × 680 | 448 × 464 |
| yaw views | 0–90°, 10° 간격 10개 | 0–90°, 10° 간격 10개 |

Sidecar coordinates use a normalized 0–1 top-left origin:

- `views[].cell`: `[x, y, width, height]` atlas pixels of one yaw view.
- `views[].faceRect`: `[x, y, width, height]` of the head, excluding ears/tufts, normalized inside that cell.
- `views[].landmarks`: the ArcFace 5-point template (InSwapper alignment) ray-cast onto the head and projected for that yaw, YuNet order, normalized inside that cell. The template is scaled by 0.72 around the head centre so the head spans a typical face.
- `faceRect`: the 0° view's face rectangle, kept for front-only readers.

Clients align a cell like face swap: a least-squares similarity from the view's landmarks to the face's YuNet landmarks, clipped to the protected mask. They never stretch width and height separately, so side faces are not squashed. YuNet's five landmarks also give yaw from the nose offset along the eye line; neighbouring views cross-fade only within ±0.5° of their midpoint. Keep the existing blur underneath because a stylized silhouette is not a privacy guarantee on its own.

## Editable 3D sources and archival exports

`build/source/rabbit.blend` and `build/source/owl.blend` (Blender 5.2, zstd-compressed) contain individually named mesh parts, editable PBR materials, a transparent front orthographic camera, and a three-light studio setup. Eyes, muzzles, cheeks, beaks and feather marks are ray-cast onto a deep head shell and oriented to its surface normal, so they stay attached in side views; owl tufts are tapered 3D lofts. `render_pose_views.py` replaces the lights with a symmetric set and fits the camera to every yaw.

`build/runtime/rabbit.glb` and `build/runtime/owl.glb` are optional self-contained glTF 2.0 archival exports, not required by the current sprite runtime. They have no textures, external buffers, rigs, animations, lights, or cameras. All material alpha modes are OPAQUE.

- Rabbit: 5,310 authoring vertices; 10,484 triangles; 262,388-byte GLB.
- Owl: 5,607 authoring vertices; 11,022 triangles; 288,892-byte GLB.

Coordinates: Blender +X right, +Z up, -Y toward camera. The GLB export converts to +X right, +Y up, +Z toward camera. One model unit is the distance between eye centers. The rabbit eye center elevation is 0.11, owl is 0.13. This is a stylized head asset, not a deformable face mesh or ARKit/ARCore rig.

## Reproduce

Requires Blender 4.3+; the current assets were generated with Blender 5.2.3 on macOS (Cycles, Metal GPU, denoising). No Blender add-on installation is required.

```sh
blender -b --factory-startup --python generate_animals.py -- --output ./build --samples 96
blender -b --factory-startup --python render_pose_views.py -- --output ./build --samples 128 --step 10
python3 pack_pose_atlas.py ./build
python3 validate_assets.py ./build
```

The atlas packer and validator use Pillow. Generation itself uses Blender's built-in Python APIs only. Cycles CPU rendering does not require the optional denoiser.

`build/asset-manifest.json` documents the package. `build/validation-report.json` records successful asset-level checks. Previews in `build/previews/` are three-quarter renders of the actual geometry, not hand-drawn or AI-generated substitutes. The PNG sprites have been visually inspected along with the 3D previews.

## Verification limits

Asset geometry, per-view clipping bounds and opaque face centers, sidecar ranges, opaque materials, file structure, absence of external dependencies, and polygon budget have been checked. Views cover yaw only; pitch keeps the nearest yaw view. Physical-device face tracking, frame rate, orientation/mirroring, filtering lifecycle, and server/on-device mode isolation must be verified by the client implementation. The assets alone do not establish those behaviors.

The repository retains the editable `.blend` sources and generation scripts.
The PNG/JSON runtime deliverables are also bundled in each native client.
Other `build/` files described above are reproducible local exports and validation
reports; they are not included in the feature commit.
