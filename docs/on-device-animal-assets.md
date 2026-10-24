# On-device animal assets (integration contract)

Status: assets integrated; device validation pending. Targets: iOS and Android only.
Server AI behavior and signaling contracts remain unchanged.

## Delivery

Provide rabbit.blend and owl.blend with editable meshes/materials and a front orthographic camera.
Export rabbit.png and owl.png as 1024 x 1024 RGBA sRGB images with transparent backgrounds.
Include all ears in the canvas. Face interiors must be opaque, including eyes and mouth.
Provide a metadata JSON entry for each animal with `image`, `faceRect` ([x,y,width,height]),
and `anchor` ([x,y], eye midpoint, not box center), normalized to image size with a top-left origin.
The faceRect excludes ears and denotes the region fitted to the tracked human face box.
Do not fit the entire ear-inclusive canvas into the human face box.
Retain optional GLB exports as editable/runtime source interchange; neither inspected
privacy pipeline currently provides a 3D asset loader.

## Rendering

Both detectors use top-left 640 x 640 letterboxed model coordinates.
iOS PrivacyFaceCoordinator converts these to bottom-left Core Image coordinates.
Android PrivacyGpuFramePipeline works in upright frame coordinates and restores output rotation.
Apply the animal after the existing privacy blur composition. Preserve plate masks,
registered-face exceptions and held masks. Missing/invalid assets fall back to existing blur.
Never expose original pixels as an asset-loading fallback.

## Side and diagonal faces (schema 2)

| 항목 | 내용 |
| --- | --- |
| 자산 | Blender 3D 머리를 yaw 0–90° 10° 간격으로 렌더링한 atlas, 음수 yaw는 좌우 반전 |
| 배치 | face box를 덮는 균일 배율, box 중심 정렬, roll 회전; 가로·세로 개별 늘림 없음 |
| yaw·roll | 보호 대상 얼굴 박스 1.4배 정사각형을 160px로 잘라 YuNet 5점 추정, 큰 얼굴부터 프레임당 최대 3개 |
| 안정화 | BoT-SORT track별 지수 평활(0.5), 랜드마크 실패 시 이전 pose 유지, track 종료 시 삭제 |
| 실패 | YuNet 로딩·실행 오류 시 정면 view로 고정, 아래 블러 유지 |
| 범위 밖 | pitch view, 프레임당 YuNet 비용과 실제 얼굴 yaw 보정 상수(코 깊이 0.55) 기기 검증 |

## Acceptance

Validate persisted blur/rabbit/owl selection, server-backend exclusion, in-flight frame
invalidation on mode changes, camera/lifecycle reset, absent faces, multiple faces,
registered-face exceptions, license plates and asset failure. Test iOS and Android separately.
Blender sources and exports are retained in assets-source/animal-filters. Runtime PNG/JSON files are bundled in both clients. Latest-main integration preserves direct pixel-buffer output, reusable blur buffers, output framing, and the one-active/one-pending frame queue. Blur mode skips animal box construction. Device FPS remains unmeasured.

## Latest-main integration (2026-10-24)

Rebased semantically onto main `6cefbe9d`, preserving latest native settings UI,
direct iOS pixel-buffer rendering, blur-buffer reuse, output framing, and pending-frame
queues. Blur mode skips animal-box construction. Runtime sprites are cached and
composited after blur, before final output framing. Android GPU output retains its
existing ROI blur and mask-edge settings. Physical camera FPS remains unverified.

Verification on 2026-10-24: latest-main Android assembleDebug and 849 unit tests
passed. iOS Release device build passed with Swift -O and whole-module optimization;
signed bundle includes both animal PNG/JSON pairs. Six selected synthetic tests
passed on iPhone 16 Pro, using an optimized Debug test host with coverage disabled.
The bundled no-face 1080p fixture measured 20 warmed frames: median 14.25 ms,
mean 14.84 ms. This is not camera FPS or face/animal performance acceptance.
