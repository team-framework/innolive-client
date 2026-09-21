# iOS 온디바이스 비식별화 테스트

iPhone 16·iOS 27에서 카메라 영상을 Core ML로 처리하는 Debug 전용 화면이다.
로그인, 서버 API, WebRTC, 방송 송출, 마이크를 사용하지 않는다.
초기 MVP는 얼굴·번호판을 모두 블러 처리했다. 현재 로컬 다중 얼굴 등록·삭제와 등록자 예외를
추가했다. 모델 준비와 검증 범위는 [얼굴 등록 실험](ios-local-face-registration.md)을 따른다.
경계 안정화에는 인접 프레임의 같은 클래스 bbox를 대응시키는 가벼운 추적을 사용한다.
Release 앱은 기존 화면을 사용한다. API·시그널링 계약 변경은 없다.

## 모델 준비

macOS에서 신뢰할 수 있는 `innolive-ai/models/best.pt`를 사용한다.
모델 생성물은 Git에 포함하지 않는다. 앱 빌드 전 아래 명령으로 생성해야 한다.

```sh
uv venv --python 3.12 /tmp/innolive-coreml-venv
uv pip install --python /tmp/innolive-coreml-venv/bin/python -r scripts/requirements-ios-privacy.txt
/tmp/innolive-coreml-venv/bin/python scripts/export-ios-privacy-model.py \
  --checkpoint ../innolive-ai/models/best.pt
```

출력은 `apps/ios/InnoLive/InnoLive/Resources/PrivacyDetector.mlpackage`다.
기존 파일을 덮어쓰지 않으므로 모델 재생성 시 다른 `--output`을 지정해 비교한 뒤 교체한다.
Xcode가 빌드하면서 `.mlmodelc`로 컴파일한다. 모델이 없거나 출력 규격이 다르면 테스트 화면은
원본 영상을 표시하지 않고 오류를 알린다.

변환 규격:

- Ultralytics 8.4.104, Core ML Tools 9.0, FP16, 고정 640×640, batch 1.
- `end2end=False`, `nms=False`: one-to-many 출력에 앱이 class-aware NMS를 적용한다.
- 얼굴 0, 번호판 1. 출력 `[1,38,8400]`, prototype `[1,32,160,160]`.
- RGB, 비율 유지 letterbox, padding 114. 모델에 1/255 스케일이 포함된다.
- confidence 0.25, NMS IoU 0.45, mask logit > 0, mask 크기 160×160.
- 마스크를 2px 확장한 보호 영역을 유지하고, 4px 확장·Gaussian radius 1.5의 부드러운 바깥
  경계를 합친 뒤 원본 좌표로 복원한다. 탐지된 객체의 마스크가 비면 bbox를 사용한다.

Core ML Tools는 설치한 Torch 2.14.0을 공식 테스트한 버전이 아니라는 경고를 출력한다.
변환 성공과 별도로 체크포인트마다 출력 비교를 수행해야 한다. 기준 학습/서버 모델이 사용하는
end-to-end head와 이 실험의 one-to-many head 사이 품질 차이도 별도 검증 대상이다.

## 기기 빌드와 실행

일반 앱과 함께 설치하려면 앱 타깃에만 적용되는 식별자·이름 옵션을 사용한다.
Debug에서 Swift 최적화를 켜야 디버그용 CPU 후처리 비용이 성능 측정을 왜곡하지 않는다.

```sh
xcodebuild -project apps/ios/InnoLive/InnoLive.xcodeproj -scheme InnoLive \
  -configuration Debug -destination 'generic/platform=iOS' \
  -derivedDataPath /tmp/innolive-privacy-build \
  INNOLIVE_BUNDLE_IDENTIFIER=com.framework.innolive.privacy-lab \
  'INNOLIVE_DISPLAY_NAME=InnoLive Local' \
  SWIFT_OPTIMIZATION_LEVEL=-O SWIFT_COMPILATION_MODE=wholemodule \
  -allowProvisioningUpdates build

xcrun devicectl list devices
xcrun devicectl device install app --device '<device-id>' \
  /tmp/innolive-privacy-build/Build/Products/Debug-iphoneos/InnoLive.app
xcrun devicectl device process launch --device '<device-id>' com.framework.innolive.privacy-lab
```

또는 일반 Debug 앱에 `--on-device-privacy` 실행 인자를 전달한다. 테스트 진입 시
`ContentView`, 인증 복원, YouTube 연결, WebRTC 캡처를 생성하지 않는다.
카메라 권한을 허용하면 전면 카메라부터 시작한다. 화면에서 중지·재시작·전후면 전환을 할 수 있다.
백그라운드로 이동하면 중지하며 돌아온 뒤 시작 버튼을 눌러 재개한다.

## 처리와 성능 기록

AVFoundation → `CVPixelBuffer` → Core ML → mask 복원 → Core Image 블러 → 처리 이미지 순서다.
캡처와 추론은 한 serial queue에서 실행하고 늦은 입력 프레임은 버린다. 각 결과는 동일한 입력
프레임에 적용한다. 직전 객체 mask를 현재 bbox에 맞춰 정렬하고 시간 상수 120ms로 감쇠시킨
영역을 추가해 경계 변화를 완화한다. 현재 프레임의 보호 영역은 줄이지 않는다.
200ms 이상 처리 간격, 대응하지 못한 객체, 카메라 재시작은 과거 상태를 버린다.
프레임 재사용이나 탐지를 생략하는 추적 보간으로 화면 FPS를 늘리지 않는다.
테스트 화면의 방향은 센서 영상을 세로 기준으로 회전한 것으로, 방송 방향 정책과 분리되어 있다.

화면은 직전 처리 시간과 완료 간격으로 계산한 순간 처리 FPS를 표시한다. 처리 시간에는
전처리·추론·마스크·합성이 포함되며 카메라 센서 지연과 화면 표시 지연은 포함하지 않는다.
Core ML 가속 설정은 `.cpuAndNeuralEngine`이며 실제 연산별 배치를 보장하는 표시는 아니다.

영상·얼굴 crop은 저장하거나 전송하지 않는다. 등록한 이름과 embedding은 기기에만 저장하며,
개별 삭제할 수 있다. 저장 위치와 보호 속성은 얼굴 등록 실험 문서를 따른다. 첫 프레임과 이후 60프레임마다
처리 구간 시간과 탐지 개수 등 숫자만 `Library/Caches/privacy-lab-metrics.json`에 기록한다.
최대 900개를 유지하며 카메라를 시작할 때 기록을 새로 시작한다.

```sh
xcrun devicectl device copy from --device '<device-id>' \
  --domain-type appDataContainer --domain-identifier com.framework.innolive.privacy-lab \
  --source Library/Caches/privacy-lab-metrics.json --destination /tmp/privacy-lab-metrics.json
```

## 검증 범위

- `PrivacySegmentationTests`: 클래스별 NMS, 잘못된 출력 거부, 빈 마스크의 bbox 보호,
  세로·가로 letterbox 좌표, 경계 완화 후 보호 영역 유지, Core Image 상하 좌표.
- `PrivacyMaskStabilizerTests`: 새 영역 즉시 반영, 이동 정렬, 클래스·시간 간격·카메라 재시작
  경계의 상태 초기화, 과거 영역의 완전한 감쇠.
- 같은 실제 장면에서 전면·후면, 가까운 얼굴·작은 얼굴, 여러 얼굴, 번호판을 확인한다.
- 비행기 모드에서도 로컬 처리가 지속되는지 확인한다.
- 15분 실행 후 FPS와 발열을 측정한다. 순간 FPS를 지속 성능으로 보고하지 않는다.
- 미탐지 영역은 원본으로 남을 수 있다. 이 실험은 비식별화 정확도나 보호 수준을 보장하지 않는다.
- 선택적 얼굴 식별은 얼굴 등록 실험에서 추가했다. optical flow 기반 정밀 추적, 장시간 발열,
  배터리, 영상 인코딩·송출은 별도 작업이다.
