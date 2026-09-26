# Android 온디바이스 AI 송출

관련 이슈: client #320. iOS의 YOLO 얼굴·번호판 분할 모델과 동일한 `best.pt` 체크포인트를 Android ONNX로 변환한다.

## 현재 범위

- 기본값은 서버 AI다. 방송 중에는 AI 처리 위치를 바꿀 수 없다. 미리보기가 연결된 동안에는 같은 세션·PeerConnection·카메라를 유지하고 서버 AI와 Android 처리 경로를 전환한다.
- 방송 화면의 설정 버튼을 눌러 설정 화면의 `AI 처리 방식`에서 `서버 AI` 또는 `온디바이스 AI`를 선택한다. 방송 준비·재연결·방송 중에는 선택을 비활성화하고, 전환 실패는 설정 화면에 표시한다.
- 온디바이스 모드에서는 얼굴과 번호판을 모두 비식별화한다. 기기 얼굴 관리에서 등록한 얼굴만 2회 연속 인식 뒤 짧은 시간 동안 블러 예외가 될 수 있다. 번호판은 항상 보호한다. 서버 얼굴 목록과 기기 목록은 분리한다.
- 로컬 비식별화가 켜져 있을 때 모델 준비·추론·프레임 변환에 실패하면 원본 프레임을 보내지 않고 연결을 종료한다. 사용자가 비식별화를 명시적으로 끈 경우에만 원본 송출을 허용한다.
- 서버 AI를 끄기 전에 로컬 보호 경로를 활성화한다. 서버 AI로 돌아갈 때는 서버의 비식별화 응답을 확인한 뒤 로컬 보호 경로를 해제한다. 응답이 불확실하면 현재 로컬 보호 경로를 유지하고 방송 준비와 비식별화 토글을 막는다. 같은 전환을 다시 선택해 재확인할 수 있다.
- 온디바이스 비식별화 중 카메라 원본은 기기 미리보기에만 남기고, WebRTC 송출에는 처리 프레임을 사용한다. 처리 중 모드 변경·카메라 종료로 오래된 결과가 되면 폐기한다.

## 모델 및 출력 계약

`apps/android/InnoLive/app/model-tools/export_detector.py`는 SHA-256 `307e9b5895654d25d264903451abc4acad9ee30f5e2f2bf64af164a9f46bc115`의 `innolive-ai/models/best.pt`만 허용한다. 출력 ONNX의 SHA-256은 `8d111ad2dcb5e5fa9d709f3d11606dcd62ea6f1d4833633864b1e4cf47f13be7`이다. 모델 파일은 Android 앱 asset에 포함한다.

얼굴 검출에는 iOS와 같은 YuNet 2023mar (`8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`), embedding에는 ViT-Base KP-RPE WebFace12M (`04b4bee1de7cefa9e97900f8449fca906d8afbab2029bd39cc5049d33e927ed9`)을 사용한다. ONNX 출력 SHA-256은 각각 `4514febaf280cb408b6bfcd240df4463ba5295713e571c1133d743ff4eb1d737`, `812eeaa58ed70794dd67e1efdb1d9f614b0be18e951d2c6bc2c8876c2c34d712`이다. AdaFace FP16 모델은 Git LFS asset이며 약 227MB다. 앱은 처음 사용 시 no-backup 영역에 검증한 모델을 복사한다.

입력은 RGB 640×640 NCHW float32, 1/255 정규화, 비율 유지 letterbox와 값 114 padding이다. 출력은 `[1,38,8400]`, `[1,32,160,160]`; 얼굴 0, 번호판 1이다. 앱이 confidence 0.25, 클래스별 IoU 0.45 NMS, 2px mask 확장, 빈 mask의 bbox 대체를 수행한다. Android도 픽셀화(24px) → Gaussian 블러(24px) → 마스크 합성을 수행한다. 마스크는 2px 중심을 완전히 불투명하게 유지하고, 4px 확장 후 Gaussian(1.5px)을 적용한 바깥 영역과 최댓값으로 합성한다. Android 12(API 31) 이상에서는 재사용하는 RenderEffect·HardwareRenderer로 GPU 블러를 수행한다. Android 11 또는 GPU 오류 시에는 1/4 크기의 중간 이미지에서 sigma 6의 분리 Gaussian convolution을 수행한다. 이 축소는 필터에만 적용하며 검출 마스크나 송출 크기는 줄이지 않는다. Core Image와 픽셀 단위로 동일한 결과를 보장하지는 않는다. 빈 마스크도 독립된 출력 Bitmap을 반환하며, GPU 이미지·HardwareBuffer를 닫기 전에 CPU 복사본을 확보한다.

기기 얼굴 관리는 기존 카메라의 500px 중앙 촬영과 3회 안정 검사를 사용한다. 모델 준비가 끝난 뒤 30초 촬영 시간이 시작된다. YuNet은 BGR 0–255 입력과 32배수 padding, stride 8/16/32 decode와 NMS 0.3을 사용한다. 등록은 점수 0.9 이상·최소 40px, 영상 비교는 0.6 이상·최소 24px을 사용한다. 얼굴의 1.5배 정사각형 RGB 112×112 crop과 정규화 landmark를 AdaFace에 전달한다. 원본·좌우반전 norm 가중 결합은 ONNX 모델에 포함된다.

이름·UUID·512차원 정규화 embedding은 앱 설치 범위의 Android Keystore AES-GCM 암호화 파일에 저장한다. 사진·crop은 저장하지 않고 API로 전송하지 않는다. 계정 삭제의 로컬 정리 단계에서 암호화 파일과 키를 제거하고, 변경 세대로 기존 블러 예외를 무효화한다. 최대 20명이며 코사인 0.75 이상 중복 후보는 거부한다. 방송 중에는 0.60 유사도와 1·2위 0.08 간격, 같은 등록자의 2회 연속 일치, 750ms 만료를 사용한다. 블러 예외 후보도 송출할 현재 프레임에서 다시 인식해 같은 등록자로 확인된 경우에만 예외 처리한다. 현재 프레임 비교가 실패하거나 인식기가 사용 중이면 해당 프레임은 보호하며 기존 예외를 취소한다. 프레임 간격은 1초 이상일 때 트랙을 재설정해 5fps 이하에서도 2회 확인할 수 있다. 얼굴 간 겹침, 대응 모호성, 한 사람이 두 track에 매칭된 경우, 카메라 재설정과 등록 목록 변경 때 예외를 취소한다. 인식 모델이 준비되지 않으면 모든 얼굴을 보호한다. 모델 준비에 실패하면 프레임에서 재시도하지 않고 얼굴 관리 화면의 재시도 버튼을 기다린다. 현재 프레임의 얼굴 인식은 송출 처리 시간을 늘릴 수 있어 실기기 FPS·발열 검증이 필요하다.

## 확인 및 남은 검증

- PyTorch와 ONNX의 고정 합성 입력 출력 비교: 최대 절대 오차 `0.0004578`, `0.0000107`.
- Android 에뮬레이터에서 모델 로딩·합성 입력 추론과 WebRTC 프레임의 크기·회전·시각 보존 확인.
- YuNet의 640px 출력은 원본 ONNX와 동일하며 320px 동적 입력도 실행했다. AdaFace FP16 합성 입력은 원본 FP32 대비 코사인 `0.999998`, 최대 절대 오차 `0.000285`다. Pixel_10 에뮬레이터에서 512차원 정규화 임베딩 출력을 확인했다.
- 실제 Android 기기에서 전후면·세로/가로·얼굴 크기·번호판·다인 장면·15분 발열 및 지속 FPS를 확인해야 한다.
- 실제 서버와 YouTube 비공개 방송에서 모드 왕복 전환, 서버 AI 상태, 영상 패킷, 수신 화면, 네트워크 전환을 확인해야 한다.
- 실제 두 명 이상 등록·동시 등장·교차·재등장·개별 삭제 후 재등록·비행기 모드에서 등록자만 예외 처리되는지 확인해야 한다. iOS 기준은 `docs/ios-local-face-registration.md`다.

API·시그널링 필드 변경은 없다. 세션과 서버 호환 조건은 `contracts/api/ai-processing-v1.md`를 따른다.


## 끊김 원인 계측 (2026-09-26)

사용자가 끊김을 보고한 모드는 온디바이스 AI다. SM-S931N(Android 16)에서 실제
ONNX 모델과 `PrivacyFrameProcessor`를 호출했다. 얼굴 사진 대신 합성 I420·Bitmap을
사용했고, 각 크기를 4회 처리한 뒤 첫 표본을 제외했다. 아래 범위는 두 실행에서 나온
표본이다. 실제 카메라·인코더·네트워크·YouTube까지 포함한 송출 FPS는 아니다.

| 구간 | 720p | 1080p |
| --- | ---: | ---: |
| 입력 I420→Bitmap 및 회전 | 84~103ms | 189~231ms |
| 출력 Bitmap→I420 및 회전 | 51~62ms | 112~137ms |
| YOLO 추론 | 70~87ms | 70~91ms |
| 검출 decode·마스크 처리 (빈 검출 합성 입력) | 34~41ms | 34~42ms |
| 전체 프레임 처리 (등록 얼굴 확인·보호 영역 없음) | 252~305ms | 422~515ms |
| 픽셀화·Gaussian·전체 보호 마스크 합성, 최종 GPU 경로 | 31~33ms | 49~58ms |

AdaFace 합성 입력 추론은 별도 측정에서 424~456ms였다. 실제 등록 얼굴 확인은
YuNet·crop·매칭도 포함하므로 이 측정이 얼굴 예외 경로 전체의 시간은 아니다.
CPU Gaussian 실험의 전체 보호 영역 렌더 중앙값은 720p 69ms, 1080p 141ms였고,
최종 GPU 경로에서는 각각 31ms, 49ms였다. GPU 경로 테스트는 CPU 대체 경로로
통과하지 못하도록 실제 GPU 사용도 확인했다. 기기 온도·클럭·순서가 통제된 반복
성능 평가가 아니므로 실행 간 전체 지연 차이를 Gaussian 변경의 효과로 보지 않는다.

현재 Android는 기본 CPU ONNX 실행, Kotlin의 프레임 전체 YUV↔RGB 변환,
등록 얼굴 예외 후보의 동기 현재 프레임 재확인을 한 송출 처리 흐름에서 수행한다.
CameraX KEEP_ONLY_LATEST는 밀린 원본의 누적을 막지만 이 처리 비용을 줄이지 않는다.
얼굴 확인이 없어도 720p 처리량은 약 3~4fps, 1080p는 약 2fps로 제한될 수 있다.
따라서 기기 성능만으로 설명하기 전에 이 구현 비용을 개선해야 한다.

iOS는 YOLO에 Core ML CPU·Neural Engine 설정, AdaFace에 CPU·GPU 설정을 사용하고,
Core Image로 필터·회전·픽셀 버퍼 처리를 수행한다. iOS의 얼굴 인식은 별도 비동기
worker에서 실행한다. Android의 동기 현재 프레임 확인은 같은 위치에 다른 사람이
들어올 때 예외가 이어지는 문제를 막기 위한 것이므로, 단순히 예외 캐시를 비동기로
재사용하는 방식으로 되돌리면 안 된다. 같은 장면·조건의 iOS/Android 비교는 아직 없다.

후속 최적화 순서는 YUV 변환의 네이티브/가속 경로, ONNX 실행 제공자 비교,
현재 프레임 확인을 보장하는 얼굴 인식·송출 일정 개선이다. 이번 변경은 GPU 블러와
계측을 추가하며, 기존 변환·추론·인식 일정의 성능 개선은 포함하지 않는다.
실제 방송 중 Debug 로그의 `PrivacyPipeline`은 5초 간격으로 숫자 처리 시간만 출력한다.
이미지·이름·embedding·토큰·서버 주소는 기록하지 않는다.

검증: 단위 테스트 168개, SM-S931N 모델·프레임·블러·계측 테스트 10개 통과.
CPU 대체 경로의 블록 경계 블러, 마스크 중심 불투명성·바깥 경계, 빈 마스크·1px
입력의 원본 생명주기, GPU 반복 프레임·크기 변경을 확인했다. Android 11 실제 기기,
실제 다인 장면, 장시간 발열과 YouTube 수신 화면은 미검증이다.

- [Android 공식 RenderEffect·HardwareRenderer Bitmap 블러 안내](https://developer.android.com/guide/topics/renderscript/migrate#image-blur)
- 재현: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.framework.innolive.feature.live.privacy.PrivacyPerformanceDeviceTest --offline`
