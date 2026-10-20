# Android 온디바이스 BoT-SORT 파이프라인

대상은 Android의 로컬 미리보기와 온디바이스 방송 비식별화다. HTTP·WebRTC 계약은 변경하지 않는다.
기준 서버는 `team-framework/innolive-ai`의 `service/tracking.py`, `service/runtime.py`,
`config/botsort.yaml`과 Ultralytics 8.4.104이며, iOS 적용(#703, [ios-botsort-pipeline.md](ios-botsort-pipeline.md))과
같은 보호 경계를 사용한다.

## 처리 순서

1. 기존 LiteRT·ONNX YOLO 모델이 640×640 letterbox 영상에서 얼굴·번호판과 마스크 계수를 추론한다.
2. Kotlin 또는 네이티브 후처리가 신뢰도 0.05 초과 후보를 클래스별 NMS IoU 0.70으로 추린다.
3. `PrivacyBotSort`가 XYWH Kalman 예측으로 높은 신뢰도, 낮은 신뢰도, 미확정 트랙 순으로 매칭한다.
4. `PrivacyObjectTracking`이 보호할 객체를 고른다. 신뢰도 0.25 이상 탐지, 기존 트랙을 이은 낮은 신뢰도
   탐지, 이번 프레임에 놓친 트랙의 1프레임 유지 객체 순서다.
5. 기존 YuNet·AdaFace 등록 얼굴 판정이 확정된 BoT-SORT ID에 연결된다.
6. 마스크 안정화가 현재 마스크를 합치고, 유지 객체는 같은 ID의 직전 마스크를 예측 박스로 옮긴다.
7. 기존 GPU 또는 CPU 블러 경로가 마스크 영역을 처리한다.

Kotlin(`PrivacySegmentation`, `PrivacyMaskStabilizer`)과 네이티브(`privacy_postprocess.cpp`) 후처리는 같은
선택과 마스크를 만든다. 네이티브 경로는 `PrivacyNativePostprocessor.select`로 추적 결과를 받는다.

## 서버와 맞춘 설정

| 항목 | 값 |
| --- | --- |
| NMS IoU | 0.70 |
| 새 트랙·높은 신뢰도 기준 | ≥ 0.25 |
| 낮은 신뢰도 연속 탐지 | > 0.05, < 0.25 |
| 높은 신뢰도 매칭 비용 상한 | 0.80, score fusion 사용 |
| 낮은 신뢰도 매칭 비용 상한 | 0.50, score fusion 미사용 |
| 미확정 트랙 매칭 비용 상한 | 0.70 |
| lost track buffer | 처리한 30프레임 |
| Kalman 상태 | cx, cy, w, h, vx, vy, vw, vh |
| Kalman 위치·속도 noise weight | 1/20, 1/160 |
| appearance ReID | 비활성화 |
| 누락 마스크 유지 | 1프레임; 현재 객체와 IoU ≥ 0.5이면 제외 |

서버 detector는 신뢰도 0.01부터 후보를 받지만 추적기는 0.05 이하를 쓰지 않으므로 Android는 0.05 초과만
디코딩한다. Kalman 공분산은 좌표와 자기 속도만 결합하므로 축별 2×2 블록으로 같은 식을 계산한다.
매칭은 LAPJV와 같은 비용 목적함수의 Hungarian 구현이며 동률에서는 선택 순서가 다를 수 있다.

## Android 경계와 보호 동작

- GMC는 적용하지 않는다. Android 앱에 OpenCV가 없으며 서버 실시간 WebRTC 경로도 GMC를 끈다.
  iOS는 sparseOptFlow GMC를 사용하므로 카메라가 빠르게 움직이는 장면의 추적 결과는 iOS와 다를 수 있다.
- 신규 고신뢰도 탐지는 미확정 상태에서도 즉시 가린다. 서버는 첫 프레임 이후 신규 트랙의 출력을 확정까지 보류한다.
- 낮은 신뢰도 탐지만으로 새 ID를 만들거나 lost 트랙을 복구하지 않는다.
- 등록 얼굴 예외는 확정된 트랙에서만 허용한다. 겹침·실종·인식 결과 만료 시 예외를 해제하고 재등장하면 다시 인식한다.
  유지 객체는 탐지되지 않은 얼굴이므로 예외 판정과 인식 대상에서 제외하고 항상 가린다.
- 기존 Android 마스크의 0.12초 경계 감쇠는 유지한다. 유지 객체의 마스크는 감쇠하지 않는다.
- 0.25 이상 객체가 100개를 넘으면 기존 보호 중단 오류를 유지한다. 낮은 신뢰도 후보는 100개를 넘는 분량만 버린다.
- 카메라 전환·해상도 변경·처리 오류의 기존 reset과 1초 이상의 처리 공백이 추적 상태를 비운다.
  reset 후에도 트랙 ID는 증가만 하여 이전 ID의 얼굴 판정이 새 객체에 연결되지 않는다.

## 파일

- `PrivacyBotSort.kt`: BoT-SORT 추적기, XYWH Kalman, 비용 상한 선형 할당.
- `PrivacyObjectTracking.kt`: 보호 객체 선택과 1프레임 마스크 유지.
- `PrivacyMaskStabilizer.kt`, `privacy_postprocess.cpp`: 유지 객체의 ID별 마스크 이동.
- `PrivacyFaceTracking.kt`: BoT-SORT ID에 연결한 얼굴 인식 결과 수명 관리.

## 검증

- `PrivacyBotSortTest`: 서버 고정 Ultralytics 8.4.104 `BOTSORT`(GMC none, ReID 비활성화)로 만든 14프레임 기준과
  확정 트랙 ID·확정 여부·tracked/lost bbox 비교, 오차 0.002 이내. 낮은 신뢰도 연속 탐지, 실종·재활성화,
  2프레임 확정, 1프레임 후보 제거, 다중 클래스를 포함한다.
- `PrivacyObjectTrackingTest`, `PrivacyMaskStabilizerTest`, `PrivacyFaceTrackingTest`: 보호 선택, 마스크 유지·만료,
  ID별 얼굴 예외 해제.
- `PrivacyNativePostprocessorDeviceTest`: 네이티브 선택·유지 마스크와 Kotlin 기준 비교. 실제 기기에서 실행한다.

기준 데이터는 Ultralytics 8.4.104와 `lap`을 설치한 환경에서 같은 입력으로 `BOTSORT.update`를 실행해 만든다.
테스트는 추론 모델 없이 추적 수학을 검증하며 실시간 방송 성공이나 다인 교차 장면 품질을 검증하지 않는다.
