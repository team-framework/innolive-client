# iOS 온디바이스 BoT-SORT 파이프라인

대상은 iOS의 로컬 미리보기와 온디바이스 방송 비식별화다. HTTP·WebRTC 계약은 변경하지 않는다.
기준 서버는 `team-framework/innolive-ai`의 `3fca09943e277d04bba63adacea1717d6ba9697b`이며,
`service/tracking.py`, `service/runtime.py`, `config/botsort.yaml`과 Ultralytics 8.4.104를 비교했다.

## 처리 순서

1. 기존 Core ML YOLO 모델이 640×640 letterbox 영상에서 얼굴·번호판과 마스크 계수를 추론한다.
2. 검출 신뢰도 0.01, 클래스별 NMS IoU 0.70으로 후보를 추린다.
3. OpenCV sparseOptFlow가 이전 영상과 현재 영상의 카메라 변환을 계산한다.
4. BoT-SORT가 XYWH Kalman 예측에 카메라 변환을 적용하고 높은 신뢰도, 낮은 신뢰도, 미확정 트랙 순으로 매칭한다.
5. 기존 YuNet·AdaFace 등록 얼굴 판정이 확정된 BoT-SORT ID에 연결된다.
6. 현재 탐지 마스크를 합치고, 탐지를 놓친 트랙은 예측 위치에 마스크를 1프레임 유지한다.
7. 기존 iOS 블러 렌더러가 마스크 영역을 처리한다.

`PrivacyModel`을 호출하는 로컬 미리보기와 `PrivacyUplinkProcessor`가 이 경로를 공유한다.

## 서버와 맞춘 설정

| 항목 | 값 |
| --- | --- |
| detector confidence / NMS IoU | 0.01 / 0.70 |
| 새 트랙·높은 신뢰도 기준 | ≥ 0.25 |
| 낮은 신뢰도 연속 탐지 | > 0.05, < 0.25 |
| 높은 신뢰도 매칭 비용 상한 | 0.80, score fusion 사용 |
| 낮은 신뢰도 매칭 비용 상한 | 0.50, score fusion 미사용 |
| 미확정 트랙 매칭 비용 상한 | 0.70 |
| lost track buffer | 처리한 30프레임 |
| Kalman 상태 | cx, cy, w, h, vx, vy, vw, vh |
| Kalman 위치·속도 noise weight | 1/20, 1/160 |
| appearance ReID | 비활성화 |
| GMC | Shi-Tomasi → pyramidal Lucas-Kanade → RANSAC estimateAffinePartial2D |
| GMC 축소 배율 | max(2, ceil(원본 장변 / 640)) |
| GMC 특징점 | 최대 1,000개, quality 0.01, minDistance 1, blockSize 3 |
| 누락 마스크 유지 | 1프레임; 현재 트랙과 IoU ≥ 0.5이면 중복 유지 제외 |

매칭은 비용 상한을 적용한 전역 선형 할당을 사용한다. Swift의 Hungarian 구현과
서버의 LAPJV는 같은 비용 목적함수를 사용하며, 동률에서는 선택 순서가 다를 수 있다.

## iOS 경계와 보호 동작

- 신규 고신뢰도 탐지는 미확정 상태에서도 즉시 가린다. 서버는 첫 프레임 이후 신규 트랙의 출력을 확정까지 보류한다.
- 등록 얼굴 예외는 확정된 트랙의 AdaFace 최초 일치로 승인한다. 기존 유사도 0.60·차순위 점수 차이 0.08은 유지한다.
- 승인된 ID는 겹침·작은 얼굴·인식 모델 일시 사용 불가·lost 복구 중에도 재검사하지 않는다.
  tracker의 전체 lost 수명 동안 승인과 등록 얼굴 ID를 유지하고, 1프레임 예측 마스크도 생략한다.
- 미승인 트랙은 기존 0.25초 재시도와 0.75초 결과 유효시간을 유지한다. 겹친 얼굴의 최초 승인은 보류한다.
- 매칭된 등록 얼굴 삭제·tracker ID 제거·스트림 reset 때 승인 해제. 다른 등록 얼굴 추가·삭제는 기존 승인 유지.
  등록 목록 변경 전 비동기 결과와 제거·재생성된 ID의 결과는 적용하지 않는다.
- 최초 AdaFace 오승인이나 BoT-SORT ID 오연결이 발생하면 승인도 유지될 수 있다.
  이 변경은 오인식률 개선이 아니라 승인 후 반복 검증 생략 정책이다. 번호판 보호는 유지한다.
- 서버와 같이 낮은 신뢰도 탐지만으로 새 ID를 생성하거나 lost 트랙을 복구하지 않는다.
- 누락 마스크의 신뢰도 감쇠 0.90은 서버 메타데이터다. 마스크 불투명도를 낮추지 않는다.
- 카메라 전환 등 기존 reset 호출, 해상도 변경, 1초 이상의 처리 공백, 처리 오류가 추적·마스크·얼굴 예외 상태를 비운다.
- 기존 iOS의 160×160 래스터 마스크, YuNet·AdaFace 예외 판정 조건, 블러 강도는 유지한다.
  서버의 polygon 직렬화나 최종 출력 픽셀과 동일하다는 의미는 아니다.
- 100개를 초과하는 NMS 결과에서는 기존 iOS의 보호 중단 오류를 유지한다.
- 서버의 별도 MobileFaceSwap 렌더러는 이 얼굴·번호판 블러 경로에 포함하지 않는다.

## 파일

- `PrivacyBoTSORT.swift`: 스트림별 ID, 2단계 매칭, 확정·실종·재활성화·중복 제거.
- `PrivacyTrackKalman.swift`: XYWH Kalman과 비용 상한 선형 할당.
- `PrivacyCameraMotion.swift`: 영상 좌표와 detector letterbox 좌표 사이의 GMC 변환.
- `apps/ios/PrivacyTracking`: OpenCV 4.12.0의 sparseOptFlow Objective-C++ bridge.
- `PrivacyMaskStabilizer.swift`: ID별 마스크 캐시와 1프레임 예측 유지.
- `PrivacyFaceTracking.swift`: BoT-SORT ID에 연결한 얼굴 인식 결과 수명 관리.

OpenCV는 SwiftPM 버전 4.12.0과 checksum으로 고정한다. 출처·라이선스는
`THIRD_PARTY_NOTICES.md`와 `apps/ios/PrivacyTracking/LICENSE-AGPL-3.0.txt`를 따른다.

## 검증

2026-10-20 iOS 27 시뮬레이터에서 관련 XCTest 46개를 통과했다.
lost buffer 처리 순서를 서버와 맞춘 뒤 추적 테스트 14개를 재실행해 통과했다.

- 서버의 고정 Ultralytics 버전으로 만든 55프레임 fixture: 확정 트랙 ID·탐지 인덱스 일치,
  bbox 좌표 오차 0.001픽셀 이내. 낮은 신뢰도 연속 탐지, 실종·재활성화, 탐지 순서 변경,
  다중 클래스, 명시적 카메라 이동, lost buffer 만료 경계를 포함한다.
- 실제 OpenCV 영상 테스트: 가로·세로 이동 방향, 무특징 영상 fallback, 해상도 변경 reset,
  portrait letterbox 좌표 변환.
- 마스크 1프레임 유지·만료, ID 변경, 겹친 현재 마스크 중복 제외, 등록 얼굴 예외 취소.

Fixture 재생성:

```sh
python3 -m venv /tmp/innolive-botsort-reference
/tmp/innolive-botsort-reference/bin/pip install ultralytics==8.4.104 'lap>=0.5.12'
/tmp/innolive-botsort-reference/bin/python scripts/prepare-ios-botsort-fixture.py
```

테스트는 `PrivacyBoTSORTTests`, `PrivacyCameraMotionTests`, `PrivacyMaskStabilizerTests`,
`PrivacyFaceTests`, `PrivacySegmentationTests`를 실행한다. Fixture는 실제 추론 모델 없이
추적 수학을 검증하며, 영상 테스트는 Core ML 추론이나 실시간 방송 성공을 검증하지 않는다.
실제 방송의 네트워크 전달과 사람 교차 장면의 비식별화 품질은 별도 검증이 필요하다.

실제 iPhone 대상의 서명 없는 Release 빌드가 성공했다. 앱에 `PrivacyDetector`,
`PrivacyFaceRecognizer`, `PrivacyYuNet`의 컴파일된 `.mlmodelc` 3개를 확인했다.
위 기록은 최초 시뮬레이터·서명 없는 빌드 검증이다. 이후 최신 main 통합과 실기기 검증은 아래에 기록한다.

## 최신 main 통합 및 iPhone 16 검증

- 통합 기준: `origin/main`의 `43556c7d`.
- 최신 main의 CVPixelBuffer 직접 출력, 재사용 출력 pool, 1/4 해상도 블러 및 성능 probe 유지.
- 기기: 유선 연결한 iPhone 16, iOS 27.0.1.
- 빌드: 자동 서명한 Debug, Swift `-O`, wholemodule.
- 관련 XCTest 63개 통과, 실패 0개, skip 0개. 서버 기준 55프레임 비교 포함.
- `com.framework.innolive` 설치 성공. 탐지·AdaFace·YuNet 모델 3개 포함 확인.
- 탐지 모델 checkpoint SHA-256: `307e9b5895654d25d264903451abc4acad9ee30f5e2f2bf64af164a9f46bc115`.

실제 Core ML 처리 시간은 1920×1080 고정 영상·공개 얼굴 이미지로 측정했다.
각 조건은 워밍업 5회 이후 20개 표본의 중앙값이며, 연속 카메라 FPS와는 다른 지표다.
5개 얼굴은 같은 공개 이미지의 반복 배치다.

| 조건 | 전체 처리 중앙값 | 최대값 |
| --- | ---: | ---: |
| 고정 영상 CGImage 출력 | 16.47ms | 23.29ms |
| 얼굴 1개 CGImage 출력 | 15.79ms | 18.67ms |
| 얼굴 1개 직접 출력 | 20.29ms | 24.80ms |
| 얼굴 5개 CGImage 출력 | 19.92ms | 25.93ms |
| 얼굴 5개 직접 출력 | 29.47ms | 34.66ms |

공개 얼굴 이미지 SHA-256은 `88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5`다.
모델과 이미지는 Git에 포함하지 않는다. 4방향 직접 출력 비교와 기존 블러 강도 비교도 통과했다.

### 실제 카메라와 인코더 2개

`--privacy-performance-test-host`로 홈 카메라 중복 실행을 막고,
`testDirectPublishUsesProtectedLocalPreviewWithoutSignaling`과
`testPhysicalCameraWithTwoEncoders`를 실행했다. 두 테스트가 통과해 실기기 검증은 총 65개다.

| 측정 항목 | 결과 |
| --- | ---: |
| 측정 시간 | 90.004초 |
| 전달 프레임 | 2,700 |
| 출력 크기 | 1920×1080 |
| 평균 FPS | 29.9987 |
| 처리 지연 중앙값 / p95 | 23.26ms / 24.68ms |
| 처리 실패 | 0 |
| H.264 인코더별 완료 프레임 | 각 2,700 |
| 인코더 오류 | 0 |
| thermal state 전후 | nominal → nominal |
| 저전력 모드 | 비활성 |

실시간 카메라 장면의 얼굴 수·이동을 통제하지 않았으므로 다인 교차 정확도 결과로 해석하지 않는다.
이 시험은 로컬 카메라·AI·인코딩 경로이며 외부 방송 서비스로 송출하지 않는다.
시험 종료 후 설치한 앱을 일반 실행 인자로 다시 열었다.

## 승인 유지 변경 검증 (2026-10-21)

- 기준: `innolive-ai` PR #77, 최신 client main `bd6c0fd6`.
- iOS 26.5 시뮬레이터에서 `PrivacyFaceTests` 13개, `PrivacyBoTSORTTests` 14개,
  `PrivacyMaskStabilizerTests` 8개 통과. 최초 승인·재검사 생략·lost 복구·등록 삭제·늦은 결과·번호판 보호 포함.
- 함께 실행한 출력 확대 3개·uplink 10개를 포함해 총 48개 통과, 실패·skip 없음. 시뮬레이터 Debug 빌드 성공.
- 위 테스트는 추적 정책·Core Image 출력 검증이다. 이번 변경의 실제 Core ML 얼굴 추론,
  실기기 카메라 교차 장면 및 외부 방송 송출 검증은 미수행.
