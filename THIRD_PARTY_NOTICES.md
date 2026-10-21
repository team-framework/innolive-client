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
  (`apps/web/public/models/blaze-face-short-range.tflite` and
  `apps/landing/public/models/blaze-face-short-range.tflite`)
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

## Android direct publishing

Used by the Android app to publish anonymized video and audio directly to
YouTube and CHZZK over RTMP(S).

| Dependency | License |
| --- | --- |
| RootEncoder `rtmp`, `common` 2.8.1 (`com.github.pedroSG94.RootEncoder`, https://github.com/pedroSG94/RootEncoder) | Apache-2.0 |
| Ktor network and TLS 3.5.2 (`io.ktor`) | Apache-2.0 |
| kotlinx-io 0.9.1 (`org.jetbrains.kotlinx`) | Apache-2.0 |
| SLF4J API 2.0.18 (`org.slf4j:slf4j-api`) | MIT |

## Bundled font

- **Wanted Sans** (`apps/web/public/fonts/wanted-sans`)
  - License: SIL Open Font License 1.1 (`OFL.txt` 동봉)
  - Source: https://github.com/wanteddev/wanted-sans

The project-level Apache License 2.0 applies only to InnoLive-owned source
code. Third-party materials remain available under their respective licenses.

## iOS BoT-SORT tracking

- `PrivacyBoTSORT.swift`, `PrivacyTrackKalman.swift`, and `PrivacySparseFlow.mm`
  implement the tracking and sparse optical flow algorithms with reference to
  Ultralytics 8.4.104 (`bot_sort.py`, `byte_tracker.py`, `kalman_filter.py`, `gmc.py`).
  Copyright Ultralytics. These ports are licensed under AGPL-3.0-or-later;
  see `apps/ios/PrivacyTracking/LICENSE-AGPL-3.0.txt`.
  Source: https://github.com/ultralytics/ultralytics/tree/v8.4.104/ultralytics/trackers
- OpenCV 4.12.0 supplies Shi-Tomasi corners, pyramidal Lucas-Kanade optical flow,
  and RANSAC affine estimation. License: Apache-2.0.
  Source: https://github.com/opencv/opencv/tree/4.12.0
- The OpenCV XCFramework is distributed through `yeatse/opencv-spm` 4.12.0,
  pinned in `Package.resolved`; SwiftPM checks its archive checksum.
  Package source: https://github.com/yeatse/opencv-spm/tree/4.12.0

## Android BoT-SORT tracking

- `PrivacyBotSort.kt` implements the tracking, Kalman filter, and cost-limited
  assignment with reference to Ultralytics 8.4.104 (`bot_sort.py`,
  `byte_tracker.py`, `kalman_filter.py`, `matching.py`, `stracks.py`).
  Copyright Ultralytics. This port is licensed under AGPL-3.0-or-later;
  see `apps/ios/PrivacyTracking/LICENSE-AGPL-3.0.txt`.
  Source: https://github.com/ultralytics/ultralytics/tree/v8.4.104/ultralytics/trackers
