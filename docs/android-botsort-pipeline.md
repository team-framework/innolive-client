# Android 온디바이스 BoT-SORT 파이프라인

대상은 Android의 로컬 미리보기와 온디바이스 방송 비식별화다. HTTP·WebRTC 계약은 변경하지 않는다.
기준 서버는 `team-framework/innolive-ai`의 `service/tracking.py`, `service/runtime.py`,
`config/botsort.yaml`과 Ultralytics 8.4.104이며, iOS 적용(#703, [ios-botsort-pipeline.md](ios-botsort-pipeline.md))과
같은 보호 경계를 사용한다.

## 처리 순서

1. 기존 LiteRT·ONNX YOLO 모델이 640×640 letterbox 영상에서 얼굴·번호판과 마스크 계수를 추론한다.
2. Kotlin 또는 네이티브 후처리가 신뢰도 0.05 초과 후보를 클래스별 NMS IoU 0.45로 추린다.
3. `PrivacyBotSort`가 XYWH Kalman 예측으로 높은 신뢰도, 낮은 신뢰도, 미확정 트랙 순으로 매칭한다.
4. `PrivacyObjectTracking`이 보호할 객체를 고른다. 신뢰도 0.25 이상 탐지, 기존 트랙을 이은 낮은 신뢰도
   탐지, 이번 프레임에 놓친 트랙의 1프레임 유지 객체 순서다.
5. 기존 YuNet·AdaFace 등록 얼굴 판정이 확정된 BoT-SORT ID별 승인으로 연결된다.
6. 마스크 안정화가 현재 마스크를 합치고, 유지 객체는 같은 ID의 직전 마스크를 예측 박스로 옮긴다.
7. 기존 GPU 또는 CPU 블러 경로가 마스크 영역을 처리한다.

Kotlin(`PrivacySegmentation`, `PrivacyMaskStabilizer`)과 네이티브(`privacy_postprocess.cpp`) 후처리는 같은
선택과 마스크를 만든다. 네이티브 경로는 `PrivacyNativePostprocessor.select`로 추적 결과를 받는다.

## 서버와 맞춘 설정

| 항목 | 값 |
| --- | --- |
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

서버와 iOS의 NMS IoU는 0.70이지만 Android는 기존 0.45를 유지한다. 같은 얼굴의 중복 박스가 남으면 얼굴 겹침으로
판정되어 등록 얼굴 예외가 해제되기 때문이다. 서버 detector는 신뢰도 0.01부터 후보를 받지만 추적기는 0.05 이하를 쓰지 않으므로 Android는 0.05 초과만
디코딩한다. Kalman 공분산은 좌표와 자기 속도만 결합하므로 축별 2×2 블록으로 같은 식을 계산한다.
매칭은 LAPJV와 같은 비용 목적함수의 Hungarian 구현이며 동률에서는 선택 순서가 다를 수 있다.

## Android 경계와 보호 동작

- GMC는 적용하지 않는다. Android 앱에 OpenCV가 없으며 서버 실시간 WebRTC 경로도 GMC를 끈다.
  iOS는 sparseOptFlow GMC를 사용하므로 카메라가 빠르게 움직이는 장면의 추적 결과는 iOS와 다를 수 있다.
- 신규 고신뢰도 탐지는 미확정 상태에서도 즉시 가린다. 서버는 첫 프레임 이후 신규 트랙의 출력을 확정까지 보류한다.
- 낮은 신뢰도 탐지만으로 새 ID를 만들거나 lost 트랙을 복구하지 않는다.
- 등록 얼굴 판정은 innolive-ai #77의 track별 승인 유지를 따른다. AdaFace를 1회 통과한 트랙은 다시 검사하지 않고,
  BoT-SORT ID가 tracked 또는 lost로 남아 있는 동안 블러 해제를 유지한다. 유지 객체도 승인 상태를 유지한다.
- 승인 해제 조건은 매칭된 등록 얼굴 삭제, tracker의 ID 제거, 1초 이상 처리 공백 또는 reset이다. 다른 등록 얼굴의
  추가·삭제와 얼굴 모델의 일시적 사용 불가는 기존 승인을 유지한다.
- 미통과 트랙은 나타날 때 바로 인식하고 5프레임 간격으로 2회 재시도한 뒤 60프레임(30fps 기준 2초)마다 재검증한다.
  서버의 30프레임보다 길게 두어 GPU에서 탐지와 경합하는 AdaFace 실행을 절반으로 줄인다.
- AdaFace는 화면 전체에서 최근 1초 동안 2회까지만 실행한다. 여러 사람이 있는 장면에서 새 트랙마다 검사가 몰려
  탐지와 GPU를 경합하는 것을 막는다. 처음 나타난 트랙을 먼저 검사한다.
- 원본 영상 기준 짧은 변 40px 미만 얼굴은 인식하지 않고 블러를 유지한다. 서버 실시간 인식 기준(24px)보다 엄격한
  서버 등록 기준(40px)을 써서 배경의 작은 얼굴 검사를 줄이며, 이런 얼굴은 다른 얼굴의 검사 차례를 막지 않는다. 1초 안에 오지 않은
  결과는 다시 인식하며, 미통과 판정은 3프레임 동안 얼굴이 없으면 정리한다.
- 서버와 같이 겹침 해제 규칙과 같은 등록자의 중복 트랙 거부를 두지 않는다. ReID 없는 BoT-SORT가 ID를 다른 사람에게
  잘못 이으면 그 ID의 승인도 유지된다. 이 정책은 오인식을 줄이는 변경이 아니라 통과 후 반복 검증을 생략하는 변경이다.
- 일치 판정 임계값(cosine 0.60, 2위와 0.08 차이)은 기존 Android 값을 유지하며 서버의 0.4와 다르다.
- 기존 Android 마스크의 0.12초 경계 감쇠는 유지한다. 유지 객체의 마스크는 감쇠하지 않는다.
- 0.25 이상 객체가 100개를 넘으면 기존 보호 중단 오류를 유지한다. 낮은 신뢰도 후보는 100개를 넘는 분량만 버린다.
- 카메라 전환·해상도 변경·처리 오류의 기존 reset과 1초 이상의 처리 공백이 추적 상태와 얼굴 판정을 비운다.
  reset 후에도 트랙 ID는 증가만 하여 이전 ID의 얼굴 판정이 새 객체에 연결되지 않는다.

## 파일

- `PrivacyBotSort.kt`: BoT-SORT 추적기, XYWH Kalman, 비용 상한 선형 할당.
- `PrivacyObjectTracking.kt`: 보호 객체 선택과 1프레임 마스크 유지.
- `PrivacyMaskStabilizer.kt`, `privacy_postprocess.cpp`: 유지 객체의 ID별 마스크 이동.
- `PrivacyFaceTracking.kt`: BoT-SORT ID별 등록 얼굴 승인 유지와 미통과 재시도 주기.

## 검증

- `PrivacyBotSortTest`: 서버 고정 Ultralytics 8.4.104 `BOTSORT`(GMC none, ReID 비활성화)로 만든 14프레임 기준과
  확정 트랙 ID·확정 여부·tracked/lost bbox 비교, 오차 0.002 이내. 낮은 신뢰도 연속 탐지, 실종·재활성화,
  2프레임 확정, 1프레임 후보 제거, 다중 클래스를 포함한다.
- `PrivacyObjectTrackingTest`, `PrivacyMaskStabilizerTest`, `PrivacyFaceTrackingTest`: 보호 선택, 마스크 유지·만료,
  승인 후 재검사 생략, tracker ID 수명 동안 승인 유지, 등록 얼굴 삭제 시 해제, 미통과 재시도 주기.
- `PrivacyNativePostprocessorDeviceTest`: 네이티브 선택·유지 마스크와 Kotlin 기준 비교. 실제 기기에서 실행한다.

기준 데이터는 Ultralytics 8.4.104와 `lap`을 설치한 환경에서 같은 입력으로 `BOTSORT.update`를 실행해 만든다.
테스트는 추론 모델 없이 추적 수학을 검증하며 실시간 방송 성공이나 다인 교차 장면 품질을 검증하지 않는다.

## SM-S938N 실측 (2026-10-20)

`PrivacyActualCameraDeviceTest`를 전면 카메라 1080p, 60초로 실행했다. 송출 인코딩은 포함하지 않는다.
등록 얼굴 측정은 `-e privacyRegisteredFaces true`로 앱에 등록한 얼굴 판정을 사용한다. 연속 측정으로 기기가 데워진 같은
시간대에 두 조건을 비교했으며 thermal status는 0이었다.

| 항목 | 얼굴 인식 없음 | 등록 얼굴 인식 |
| --- | ---: | ---: |
| 처리 fps | 29.7 | 26.5 |
| 프레임 처리 p50 / p95 | 31.5ms / 36.5ms | 31.7ms / 46.3ms |
| 추론 p50 | 27.2ms | 27.9ms |
| 얼굴 프레임 / 예외 프레임 | 1,780 / 0 | 1,590 / 1,574 |
| 예외 허용 / 해제 | - | 1 / 1 |
| 얼굴 인식 요청 | - | 5초당 4~6회 |
| 처리 오류 | 0 | 0 |

아래 측정은 innolive-ai #77 기준 적용 전, 30프레임마다 재검증하던 정책의 결과다. 예외 해제 1회는 측정 종료 직전에 새 트랙이 생긴 경우다. 기기가 식은 상태의 같은 측정은 추론 p50 16.9ms,
프레임 p95 26.8ms, 30.0fps였다. 남은 프레임 저하는 재검증마다 GPU에서 60~127ms 실행되는 얼굴 모델과 탐지의 경합이다.
