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

## 얼굴 합성과 같은 동작 (schema 2)

| 항목 | 얼굴 합성(서버) | 토끼·부엉이(온디바이스) |
| --- | --- | --- |
| 선택 | 실험실 비식별화 방법, 관리자 전용, 세션 생성 시 고정 | 같은 드롭다운의 토끼·부엉이, 관리자 전용, 방송 준비 시 고정 |
| 처리 방식 | 서버 AI 전용 | 온디바이스 전용, 서버 AI에서는 블러 |
| 대상 연결 | YOLO 박스 0.3배 패딩 ROI의 YuNet, IoU 0.25 초과 | 같음, ROI를 긴 변 160px로 축소 |
| 정렬 | ArcFace 5점 템플릿 유사변환 | 각도별 동물 머리에 투영한 같은 템플릿과 최소제곱 유사변환 |
| 합성 범위 | 정렬 crop × 얼굴 polygon | 보호 마스크 안쪽, 동물이 덮지 않은 마스크 픽셀은 블러 |
| 블러 유지 | 미추적·held·16px 미만·대상 실패·모델 실패 | 같음 |
| 갱신 주기 | 합성 매 프레임, 속성 30프레임 | track별 랜드마크 3프레임마다, 사이 프레임은 박스 기준 상대 위치 |
| 측면 | 합성 정렬 실패 시 블러 | 0~90° 10° 간격 view, 음수 yaw는 좌우 반전 |

설명 앞 AI 합성 안내문과 설명 한도 축소는 서버가 합성 세션에만 적용한다. 동물 세션에는 적용하지 않는다.
