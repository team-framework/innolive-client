# 온디바이스 비식별화 실험 기록

기준 기기: 사용자 iPhone 16, iOS 27. 작업 브랜치 `feat/ios-on-device-privacy/#281`.
실행 절차와 범위는 [테스트 안내](ios-on-device-privacy-lab.md)를 참고한다.
사용자 화면 관찰, 기기에서 수집한 수치, 자동 테스트 결과를 구분해 기록한다.

## 커밋 기록

- `2bd8bac`: 처음 설치한 로컬 비식별화 버전, 사용자 관찰 약 90ms / 10 FPS.
- 다음 커밋: 처리 구간 측정과 Swift 최적화 빌드 절차, 사용자 관찰 약 20ms / 30 FPS.

최초 두 버전은 대화 중 실제 실행했던 코드를 이후에 나누어 커밋했다.
커밋 시각과 최초 기기 실행 시각은 다르다. 기존 통합 커밋은 로컬 보관 브랜치
`archive/ios-privacy-before-history-split`에 보존했다.

## 2026-09-21 · 첫 로컬 파이프라인과 Swift 최적화

### 변경

- YOLO26n-seg 체크포인트를 FP16 Core ML로 변환했다.
- 체크포인트 SHA-256: `307e9b5895654d25d264903451abc4acad9ee30f5e2f2bf64af164a9f46bc115`.
- 카메라 → 추론 → class-aware NMS → mask 복원 → 픽셀화·Gaussian blur → 로컬 미리보기를 연결했다.
- 모델 640 입력, 카메라 1080p/30 FPS, `.cpuAndNeuralEngine` 설정을 사용했다.
- 별도 식별자 `com.framework.innolive.privacy-lab`로 기기에 설치했다.
- 초기 Debug 빌드 이후 `SWIFT_OPTIMIZATION_LEVEL=-O`, `SWIFT_COMPILATION_MODE=wholemodule`을 적용했다.
- 구간별 처리 시간 표시와 이미지 없는 숫자 로그를 추가했다.

### 관찰과 측정

| 조건 | 결과 | 근거 |
| --- | --- | --- |
| 초기 Debug | 약 90ms / 10 FPS | 사용자 화면 보고 |
| Swift 최적화 적용 후 | 약 20ms / 30 FPS | 사용자 화면 보고 |
| 최적화 후 초기 약 10초, 얼굴 1개 | 처리 20~22ms, 순간 28~30 FPS | 기기 캐시에서 읽은 첫 6개 표본, 첫 프레임 제외 |
| 최적화 후 약 60초 | 중앙값 21.46ms / 29.54 FPS | 60프레임 간격 31개 표본, 첫 프레임 제외; 탐지 개수는 장면에 따라 변함 |

60초 표본의 구간별 중앙값: 전처리 1.78ms, 추론 6.10ms, 마스크 1.23ms, 합성 12.34ms.
전체 프레임을 기록한 p95나 장시간 발열 시험은 아니다. 구간별 시간은 최적화 빌드에서만
수집했으므로 초기 90ms의 병목을 특정 단계로 확정하지 않는다.

공개 Ultralytics `bus.jpg`와 좌우 반전·crop의 3개 입력에서 FP32 PyTorch one-to-many head와
FP16 Core ML raw 출력을 비교했다. confidence 최대 차이는 0.0344 미만, confidence ≥0.25 후보의
bbox 좌표 최대 차이는 0.669px 미만이었다. 선택한 후보들의 uncropped prototype mask IoU는
약 0.974~0.985였다. 이는 작은 변환 점검 표본이며 GT 기반 정확도나 전체 mask 동등성 평가는 아니다.

### 검증

- 서명된 iOS 기기 빌드, iPhone 설치·실행 성공.
- `PrivacySegmentationTests` 4개 통과.
- 모델 변환 스크립트를 별도 출력 경로에서 다시 실행해 출력 shape와 checkpoint hash 확인.
- `git diff --check` 통과.

### 다음 수정의 근거

사용자가 20ms / 30 FPS를 확인한 뒤 블러가 자글자글해 보인다고 보고했다.
픽셀화 필터와 160×160 이진 마스크 경계를 점검한다. 사용자가 블러 경계의 자글거림이 더 심하다고 추가 확인했다.
