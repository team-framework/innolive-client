# Android 온디바이스 AI 송출

관련 이슈: client #320. iOS의 YOLO 얼굴·번호판 분할 모델과 동일한 `best.pt` 체크포인트를 Android ONNX로 변환한다.

## 현재 범위

- 기본값은 서버 AI다. 방송 중에는 AI 처리 위치를 바꿀 수 없다. 미리보기가 연결된 동안에는 같은 세션·PeerConnection·카메라를 유지하고 서버 AI와 Android 처리 경로를 전환한다.
- 온디바이스 모드에서는 얼굴과 번호판을 모두 비식별화한다. 등록자 얼굴 예외 및 로컬 얼굴 등록은 아직 제공하지 않는다. 서버 얼굴 관리는 서버 AI 모드에서만 노출한다.
- 로컬 비식별화가 켜져 있을 때 모델 준비·추론·프레임 변환에 실패하면 원본 프레임을 보내지 않고 연결을 종료한다. 사용자가 비식별화를 명시적으로 끈 경우에만 원본 송출을 허용한다.
- 서버 AI를 끄기 전에 로컬 보호 경로를 활성화한다. 서버 AI로 돌아갈 때는 서버의 비식별화 응답을 확인한 뒤 로컬 보호 경로를 해제한다. 응답이 불확실하면 현재 로컬 보호 경로를 유지하고 방송 준비와 비식별화 토글을 막는다. 같은 전환을 다시 선택해 재확인할 수 있다.
- 온디바이스 비식별화 중 카메라 원본은 기기 미리보기에만 남기고, WebRTC 송출에는 처리 프레임을 사용한다. 처리 중 모드 변경·카메라 종료로 오래된 결과가 되면 폐기한다.

## 모델 및 출력 계약

`apps/android/InnoLive/app/model-tools/export_detector.py`는 SHA-256 `307e9b5895654d25d264903451abc4acad9ee30f5e2f2bf64af164a9f46bc115`의 `innolive-ai/models/best.pt`만 허용한다. 출력 ONNX의 SHA-256은 `8d111ad2dcb5e5fa9d709f3d11606dcd62ea6f1d4833633864b1e4cf47f13be7`이다. 모델 파일은 Android 앱 asset에 포함한다.

입력은 RGB 640×640 NCHW float32, 1/255 정규화, 비율 유지 letterbox와 값 114 padding이다. 출력은 `[1,38,8400]`, `[1,32,160,160]`; 얼굴 0, 번호판 1이다. 앱이 confidence 0.25, 클래스별 IoU 0.45 NMS, 2px mask 확장, 빈 mask의 bbox 대체를 수행한다. Android 합성은 mosaic이며 iOS의 Gaussian 경계 합성과 시각적 결과가 다르다.

## 확인 및 남은 검증

- PyTorch와 ONNX의 고정 합성 입력 출력 비교: 최대 절대 오차 `0.0004578`, `0.0000107`.
- Android 에뮬레이터에서 모델 로딩·합성 입력 추론과 WebRTC 프레임의 크기·회전·시각 보존 확인.
- 실제 Android 기기에서 전후면·세로/가로·얼굴 크기·번호판·다인 장면·15분 발열 및 지속 FPS를 확인해야 한다.
- 실제 서버와 YouTube 비공개 방송에서 모드 왕복 전환, 서버 AI 상태, 영상 패킷, 수신 화면, 네트워크 전환을 확인해야 한다.
- 얼굴 등록·YuNet·AdaFace 예외 처리는 별도 변경이 필요하다. iOS 기준은 `docs/ios-local-face-registration.md`다.

API·시그널링 필드 변경은 없다. 세션과 서버 호환 조건은 `contracts/api/ai-processing-v1.md`를 따른다.
