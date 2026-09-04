# Third-Party Notices

This repository includes the following third-party runtime dependencies and
assets. The versioned `apps/web/package-lock.json` remains the authoritative
inventory for the web application's complete dependency tree.

## MediaPipe face detection

- **MediaPipe Tasks Vision** (`@mediapipe/tasks-vision` 0.10.35)
  - Copyright Google LLC
  - License: Apache License 2.0
  - Source: https://github.com/google-ai-edge/mediapipe
- **BlazeFace short-range model**
  (`apps/web/public/models/blaze-face-short-range.tflite`)
  - Distributed for this integration with MediaPipe Tasks Vision.
  - Source and license reference: https://github.com/google-ai-edge/mediapipe
  - License: Apache License 2.0

## Web runtime dependencies

| Dependency | License |
| --- | --- |
| Next.js and `@next/*` | MIT |
| React, React DOM, Scheduler | MIT |
| node-postgres (`pg` and related packages) | MIT / ISC |
| `@mediapipe/tasks-vision` | Apache-2.0 |
| `@swc/helpers` | Apache-2.0 |
| `sharp` and `@img/*` | Apache-2.0 / MIT |
| `sharp-libvips` | LGPL-3.0-or-later |
| `caniuse-lite` | CC-BY-4.0 |
| `styled-jsx` | MIT |
| `source-map-js` | BSD-3-Clause |
| `tslib` | 0BSD |
| `nanoid`, `semver`, `picocolors`, `split2`, `xtend` | MIT / ISC |

## Bundled font

- **Wanted Sans** (`apps/web/public/fonts/wanted-sans`)
  - License: SIL Open Font License 1.1 (`OFL.txt` 동봉)
  - Source: https://github.com/wanteddev/wanted-sans

The project-level Apache License 2.0 applies only to InnoLive-owned source
code. Third-party materials remain available under their respective licenses.
