# Android 온디바이스 AI 송출

관련 이슈: client #320. iOS의 YOLO 얼굴·번호판 분할 모델과 동일한 `best.pt` 체크포인트를 Android ONNX로 변환한다.

## 현재 범위

- 기본값은 서버 AI다. 방송 중에는 AI 처리 위치를 바꿀 수 없다. 미리보기가 연결된 동안에는 같은 세션·PeerConnection·카메라를 유지하고 서버 AI와 Android 처리 경로를 전환한다.
- 방송 화면의 설정 버튼을 눌러 설정 화면의 `AI 처리 방식`에서 `서버 AI` 또는 `온디바이스 AI`를 선택한다. 방송 준비·재연결·방송 중에는 선택을 비활성화하고, 전환 실패는 설정 화면에 표시한다.
- 온디바이스 모드에서는 얼굴과 번호판을 모두 비식별화한다. 기기 얼굴 관리에서 등록한 얼굴은 같은 등록자로 2회 확인한 뒤, 원래 iOS와 같이 최근 인식 결과의 촬영 시각부터 최대 750ms 동안 블러 예외가 될 수 있다. 얼굴별 재확인 간격은 최소 250ms다. 번호판은 항상 보호한다. 서버 얼굴 목록과 기기 목록은 분리한다.
- 로컬 비식별화가 켜져 있을 때 모델 준비·추론·프레임 변환에 실패하면 원본 프레임을 보내지 않고 연결을 종료한다. 사용자가 비식별화를 명시적으로 끈 경우에만 원본 송출을 허용한다.
- 서버 AI를 끄기 전에 로컬 보호 경로를 활성화한다. 서버 AI로 돌아갈 때는 서버의 비식별화 응답을 확인한 뒤 로컬 보호 경로를 해제한다. 응답이 불확실하면 현재 로컬 보호 경로를 유지하고 방송 준비와 비식별화 토글을 막는다. 같은 전환을 다시 선택해 재확인할 수 있다.
- 온디바이스 비식별화 중 카메라 원본은 기기 미리보기에만 남기고, WebRTC 송출에는 처리 프레임을 사용한다. 처리 중 모드 변경·카메라 종료로 오래된 결과가 되면 폐기한다.

## 모델 및 출력 계약

`apps/android/InnoLive/app/model-tools/export_detector.py`는 SHA-256 `307e9b5895654d25d264903451abc4acad9ee30f5e2f2bf64af164a9f46bc115`의 `innolive-ai/models/best.pt`만 허용한다. 출력 ONNX의 SHA-256은 `8d111ad2dcb5e5fa9d709f3d11606dcd62ea6f1d4833633864b1e4cf47f13be7`이다. 모델 파일은 Android 앱 asset에 포함한다.

얼굴 검출에는 iOS와 같은 YuNet 2023mar (`8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`), embedding에는 ViT-Base KP-RPE WebFace12M (`04b4bee1de7cefa9e97900f8449fca906d8afbab2029bd39cc5049d33e927ed9`)을 사용한다. ONNX 출력 SHA-256은 각각 `4514febaf280cb408b6bfcd240df4463ba5295713e571c1133d743ff4eb1d737`, `812eeaa58ed70794dd67e1efdb1d9f614b0be18e951d2c6bc2c8876c2c34d712`이다. AdaFace FP16 모델은 Git LFS asset이며 약 227MB다. 앱은 처음 사용 시 no-backup 영역에 검증한 모델을 복사한다.

입력은 RGB 640×640 NCHW float32, 1/255 정규화, 비율 유지 letterbox와 값 114 padding이다. 출력은 `[1,38,8400]`, `[1,32,160,160]`; 얼굴 0, 번호판 1이다. 앱이 confidence 0.25, 클래스별 IoU 0.45 NMS, 2px mask 확장, 빈 mask의 bbox 대체를 수행한다. Android도 픽셀화(24px) → Gaussian 블러(24px) → 마스크 합성을 수행한다. 마스크는 2px 중심을 완전히 불투명하게 유지하고, 4px 확장 후 Gaussian(1.5px)을 적용한 바깥 영역과 최댓값으로 합성한다. 현재 기본 영상 경로는 전용 EGL 작업자의 OpenGL ES 그래프다. YUV→RGB·정방향 회전·letterbox, 픽셀화·Gaussian 블러·마스크 확장과 feather·합성·센서 방향 복원을 GPU에서 수행하고 WebRTC에 공유 EGL RGB 텍스처를 전달한다. 전면·후면 카메라의 원래 rotation 메타데이터와 송출 크기는 유지한다. 모델 입력은 기기별 검증을 통과하면 GPU SSBO로 LiteRT에 직접 전달한다. 기존 입력 경로에서는 640×640 모델 입력을 읽고, 얼굴 재확인이 필요한 crop은 두 경로 모두 CPU로 읽는다. GPU 영상 경로가 실패하면 보호 처리하는 기존 CPU Bitmap 경로를 사용하며 원본으로 우회하지 않는다. CPU 대체 경로에서 API 31 이상의 RenderEffect 블러를 사용할 수 있다. 블러는 1/4 크기에서 sigma 6으로 처리하며 검출 마스크·출력 해상도를 낮추지 않는다. Core Image와 픽셀 단위로 동일한 결과는 보장하지 않는다. 현재 구현과 최신 계측은 마지막 GPU 영상 경로 절을 따른다.

기기 얼굴 관리는 기존 카메라의 500px 중앙 촬영과 3회 안정 검사를 사용한다. 모델 준비가 끝난 뒤 30초 촬영 시간이 시작된다. YuNet은 BGR 0–255 입력과 32배수 padding, stride 8/16/32 decode와 NMS 0.3을 사용한다. 등록은 점수 0.9 이상·최소 40px, 영상 비교는 0.6 이상·최소 24px을 사용한다. 얼굴의 1.5배 정사각형 RGB 112×112 crop과 정규화 landmark를 AdaFace에 전달한다. 원본·좌우반전 norm 가중 결합은 ONNX 모델에 포함된다.

이름·UUID·512차원 정규화 embedding은 앱 설치 범위의 Android Keystore AES-GCM 암호화 파일에 저장한다. 사진·crop은 저장하지 않고 API로 전송하지 않는다. 계정 삭제의 로컬 정리 단계에서 암호화 파일과 키를 제거하고, 변경 세대로 기존 블러 예외를 무효화한다. 최대 20명이며 코사인 0.75 이상 중복 후보는 거부한다. 방송 중에는 0.60 유사도와 1·2위 0.08 간격, 같은 등록자의 2회 확인, 촬영 시각부터 최대 750ms의 예외 캐시를 사용한다. 확인 간격이 1초 이상이면 확인 횟수를 새로 시작한다. 재확인은 얼굴별 최소 250ms 간격이며, 가장 오래 기다린 얼굴부터 작업자에게 전달한다. 인식 결과가 늦게 도착해도 촬영 시각부터 750ms가 지나지 않았다면 사용할 수 있으며, 송출은 결과를 기다리지 않는다.

다른 등록자나 미등록자 판정은 기존 예외를 취소한다. landmark 등 샘플을 얻지 못한 경우에는 기존 예외의 원래 만료 시각을 유지하며 연장하지 않는다. 프레임 간격이 200ms 이상이거나 시각이 역행하면 원래 iOS와 같이 트랙을 재설정한다. 얼굴 간 겹침, 대응 모호성, 트랙 소실, 같은 등록자가 두 트랙에 매칭된 경우, 카메라 재설정과 등록 목록 변경 때 예외를 취소한다. 인식 모델이 준비되지 않으면 모든 얼굴을 보호한다. 모델 준비에 실패하면 프레임에서 재시도하지 않고 얼굴 관리 화면의 재시도 버튼을 기다린다. 실기기 FPS·발열 검증이 필요하다. 자세한 정책과 한계는 아래의 `원래 iOS 얼굴 예외 정책 적용`을 따른다.

## 확인 및 남은 검증

- PyTorch와 ONNX의 고정 합성 입력 출력 비교: 최대 절대 오차 `0.0004578`, `0.0000107`.
- Android 에뮬레이터에서 모델 로딩·합성 입력 추론과 WebRTC 프레임의 크기·회전·시각 보존 확인.
- YuNet의 640px 출력은 원본 ONNX와 동일하며 320px 동적 입력도 실행했다. AdaFace FP16 합성 입력은 원본 FP32 대비 코사인 `0.999998`, 최대 절대 오차 `0.000285`다. Pixel_10 에뮬레이터에서 512차원 정규화 임베딩 출력을 확인했다.
- 실제 Android 기기에서 전후면·세로/가로·얼굴 크기·번호판·다인 장면·15분 발열 및 지속 FPS를 확인해야 한다.
- 실제 서버와 YouTube 비공개 방송에서 모드 왕복 전환, 서버 AI 상태, 영상 패킷, 수신 화면, 네트워크 전환을 확인해야 한다.
- 실제 두 명 이상 등록·동시 등장·교차·재등장·개별 삭제 후 재등록·비행기 모드에서 등록자만 예외 처리되는지 확인해야 한다. iOS 기준은 `docs/ios-local-face-registration.md`다.

API·시그널링 필드 변경은 없다. 세션과 서버 호환 조건은 `contracts/api/ai-processing-v1.md`를 따른다.


## 끊김 원인 계측 (2026-09-26)

이 절은 GPU 얼굴 인식·비동기 처리·750ms 캐시 적용 전의 계측 기록이다. 현재 정책은 마지막 절을 따른다.

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

계측 당시 Android는 기본 CPU ONNX 실행, Kotlin의 프레임 전체 YUV↔RGB 변환,
등록 얼굴 예외 후보의 동기 현재 프레임 재확인을 한 송출 처리 흐름에서 수행한다.
CameraX KEEP_ONLY_LATEST는 밀린 원본의 누적을 막지만 이 처리 비용을 줄이지 않는다.
얼굴 확인이 없어도 720p 처리량은 약 3~4fps, 1080p는 약 2fps로 제한될 수 있다.
따라서 기기 성능만으로 설명하기 전에 이 구현 비용을 개선해야 한다.

iOS는 YOLO에 Core ML CPU·Neural Engine 설정, AdaFace에 CPU·GPU 설정을 사용하고,
Core Image로 필터·회전·픽셀 버퍼 처리를 수행한다. iOS의 얼굴 인식은 별도 비동기
worker에서 실행한다. 당시 Android는 같은 위치에 다른 사람이 들어올 때 예외가
이어지는 문제를 막기 위해 동기 현재 프레임 확인을 사용했다. 이후 사용자의 요청으로
원래 iOS 캐시 정책을 적용했다. 같은 장면·조건의 iOS/Android 비교는 아직 없다.

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


## 프레임 변환·메모리 최적화 (2026-09-26)

Android 미디어 경로만 변경하며 API·시그널링·모델 가중치·매칭 기준은 유지한다.
iOS의 재사용하는 모델 입력 픽셀 버퍼와 네이티브 이미지 처리 구조를 참고했다.

- I420→RGBA 변환은 NDK C++로 이동했다. ARM에서는 NEON으로 16px씩 처리하고,
  나머지 픽셀과 다른 ABI는 동일한 BT.601 정수 식을 사용한다. stride·버퍼 크기를
  검증한 뒤 Bitmap을 잠근다. RGBA→I420은 기존 WebRTC libyuv의 ABGRToI420을 사용한다.
- 센서·정방향·복원용 Bitmap과 출력 변환용 RGBA 버퍼를 재사용한다. 크기가 바뀌면
  이전 Bitmap을 해제하고 방송 종료 시 모두 해제한다. 반환하는 VideoFrame은 매번
  독립된 I420 버퍼를 소유하므로 다음 프레임의 재사용 픽셀에 영향을 받지 않는다.
- YOLO 입력 Bitmap·Canvas·직접 FloatBuffer·ONNX 입력 Tensor를 모델 수명 동안
  재사용하고 RGB NCHW 입력 채우기와 prototype 유한값 검사를 네이티브로 수행한다.
  NaN·Infinity 검사는 빈 보호 목록에도 유지한다.
- 마스크 확장은 가로·세로 sliding window로 바꾸었다. 기존 사각형 확장과 결과가
  동일하며, Gaussian 경계와 중심 불투명성을 유지한다. 합성도 네이티브로 수행한다.
- CameraX KEEP_ONLY_LATEST와 송출 세대 검사는 유지한다. 모델 오류 시 원본을
  보내지 않고, 현재 프레임의 등록 얼굴 확인도 유지한다. iOS의 비동기 예외 캐시를
  그대로 적용하면 얼굴 교체 시 예외가 이어지는 위험이 있어 해당 방식은 적용하지 않았다.

SM-S931N·Android 16, 기존과 같은 합성 I420, 90° 회전, 실제 모델, 크기별 4회 처리 중
첫 표본을 제외한 결과다. 최적화 전 수치는 앞선 두 실행의 범위다. 온도·클럭을
통제한 장시간 평가가 아니며 카메라·인코더·서버·YouTube 수신 FPS와 구분한다.

| 구간 | 720p 이전 | 720p 최종 | 1080p 이전 | 1080p 최종 |
| --- | ---: | ---: | ---: | ---: |
| 전체 프레임, 얼굴 확인·보호 영역 없음 | 252~305ms | 99~103ms | 422~515ms | 115~117ms |
| 입력 변환·회전 | 84~103ms | 5.3~5.5ms | 189~231ms | 11.5~11.6ms |
| 출력 변환·회전 | 51~62ms | 4.8~5.9ms | 112~137ms | 11.6~13.7ms |

최종 전체 프레임 중앙값은 720p 99.78ms, 1080p 116.14ms였다. 1080p의 전체 보호
영역 렌더는 38~43ms(중앙값 39.00ms)였다. 전체 보호 영역 렌더는 별도 테스트이며
위의 빈 검출 프레임에 단순히 합산한 수치를 실제 송출 FPS로 사용하지 않는다.

### 실행 제공자 비교와 남은 얼굴 인식 비용

아래는 GPU 후속 변경 전 CPU 경로 측정 기록이다. 현재 GPU 경로는 다음 절을 참고한다.

기본 CPU, CPU 2·4·8 스레드, spinning 설정, XNNPACK, NNAPI를 비교했다.
기본 CPU보다 안정적으로 빠른 설정을 확인하지 못해 모델 기본 실행 제공자는 유지한다.
XNNPACK의 기존 FP16 얼굴 모델은 기본 모델과 코사인 0.999 일치 기준을 통과하지
못했다. NNAPI는 CPU 허용 여부와 관계없이 `AddNnapiSplit count [0]` 그래프 분할
오류로 YOLO 세션을 만들지 못했다. 상수·정적 크기를 단순화한 그래프도 같은 오류였다.
따라서 성공적인 NPU/GPU 모델 추론이나 해당 가속 효과를 주장하지 않는다.

얼굴 모델의 가중치를 바꾸지 않고 FP32 연산으로 변환한 별도 합성 입력 실험은 코사인
최솟값 0.99994123을 통과했지만, 기본 CPU 반복 추론은 약 335~341ms였고 모델 파일이
234MB에서 468MB로 늘었다(십진 바이트 기준). 이 모델과 실험용 모델 파일은 제품에
추가하지 않았다. 일반 ViT optimizer로 추가 Attention/Gelu fusion도 얻지 못했다.

최종 기존 AdaFace 모델의 합성 추론은 약 402~422ms다. 현재 프레임 얼굴 재확인을
실행하는 구간에는 이 비용과 YuNet·crop·매칭이 추가된다. 이 제한은 남아 있으며
등록 얼굴이 등장하는 실제 방송의 30fps를 보장하지 않는다. 얼굴 인식 GPU/NPU 경로는
동일 가중치·전처리·현재 프레임 확인과 출력 일치를 검증할 별도 실행 엔진이 필요하다.

검증: 전체 단위 테스트 169개 통과. SM-S931N에서 네이티브 색상·패딩·홀수 크기·
SIMD+tail·버퍼 경계·마스크 합성·NaN 검사·비대칭 회전·실제 모델·프레임 생명주기·
성능 테스트를 실행했다. 최종 변경 후 12개를 통과했고 직전 블러 회귀 테스트 4개도
통과했다. CMake는 arm64-v8a·armeabi-v7a·x86·x86_64를 빌드하며 arm64 라이브러리의
16KB ELF LOAD 정렬을 확인했다. 빌드에는 NDK 27.0.12077973, CMake 3.22.1이 필요하다.
Android 11·다른 ABI의 실기기, 실제 다인 인식·장시간 발열·서버·YouTube는 미검증이다.

- [ONNX Runtime XNNPACK 설정·측정 지침](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html)
- [ONNX Runtime NNAPI 지원 조건](https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html)


## 얼굴 인식 GPU 가속·비동기 처리 (2026-09-26)

대상은 Android 미디어·기기 얼굴 인식이며 API·시그널링 계약 변경은 없다.
iOS의 Core ML `.cpuAndGPU` 및 단일 비동기 작업자를 참고해 Android에서는
LiteRT 2.2.0 `CompiledModel`의 GPU+CPU 경로를 사용한다. Core ML 자체는 Android에서
사용하지 않는다. 얼굴 검출 YuNet은 ONNX CPU 경로다. 보호 영역 YOLO의 후속 LiteRT GPU 선택 경로는 마지막 절을 따른다.

### 같은 모델의 GPU 변환

`model-tools/export_face_litert.py`와 `requirements-face-litert.txt`로 같은 ViT-KP-RPE
체크포인트와 원본·좌우반전 norm 가중 결합을 변환한다. 학습 가중치·매칭 임계값·RGB
정규화·랜드마크 좌우반전 규칙은 유지한다. GPU가 지원하지 않는 높은 rank reshape와
GatherND는 rank 4 이하 attention 및 상수 one-hot lookup의 matmul로 같은 계산을 표현했다.
Conv/FC 가중치를 FP16으로 저장하며 GPU 계산은 `FP16_WITH_FP32_ACCUM`이다.

- asset: `privacy-face.tflite`, 238,594,592 bytes, Git LFS.
- SHA-256: `4051fa9cac8b11cc9d3949bc66401e796598f3958e4e0e9fafaaddbae5c42776`.
- 원래 PyTorch 구현과 합성 무작위 입력 3종 비교: cosine 0.99999970 이상,
  최대 절대 오차 0.00013233 이하. 비교 전에 원래 attention 구현을 복원하므로
  GPU용으로 바꾼 그래프끼리 비교하는 검사가 아니다.
- 기본 GPU precision의 이전 실험은 cosine 0.99682로 기준을 통과하지 못했다.
  최종 FP16 저장+FP32 누산 설정을 사용한다.

앱은 파일 hash 및 입력 크기를 검증하고, 준비 단계에서 합성 입력 3종의 실제 ONNX
CPU 출력과 GPU 출력을 비교한다. 매 표본 cosine 0.999 이상·최대 절대 오차 0.01 미만,
반복 GPU 시간 합계가 CPU 시간의 80% 미만인 경우에만 GPU를 선택한다. 초기화·출력·
속도 검증 실패 시 기존 CPU 모델을 유지한다. GPU 사용 중 추론 오류도 GPU를 폐기하고
CPU 세션을 다시 만든다. GPU 모델 생성·사용은 같은 얼굴 작업자 스레드에서 수행하고
입출력 버퍼를 재사용한다. GPU 선택 후 초기 CPU 세션을 닫아 두 실행 엔진을 계속
메모리에 유지하지 않는다.

LiteRT 2.2.0 implementation/API AAR의 namespace가 같아 현재 AGP 9.3.1에서
`android.uniquePackageNames=false` 호환 설정이 필요하다. AGP 10으로 올리기 전
upstream의 namespace 수정 버전을 확인하고 이 설정을 제거해야 한다.

### 결과가 늦어도 송출을 기다리지 않는 얼굴 확인

송출 처리기에서 동기 얼굴 추론을 제거했다. 정방향 현재 프레임의 복사 crop을
YOLO 전에 단일 얼굴 작업자에 전달하고 YOLO와 병렬 처리한다. 진행 작업 1개·결과
mailbox 1개만 유지하며 얼굴 인식이 바쁘면 새 작업을 쌓지 않는다.

직전 YOLO 위치는 crop 선택에만 사용한다. 인식 결과를 현재 트랙에 적용하기 전에
crop의 YuNet 얼굴 위치와 현재 YOLO 얼굴 위치의 IoU 0.5 이상을 확인한다. crop은
원본과 독립된 픽셀을 소유하고 작업자가 해제한다. 회전·크기·카메라·모드·등록 목록
세대가 바뀌면 오래된 결과를 무효화한다.

최초 GPU 변경은 같은 capture 시각의 결과만 해당 송출 프레임에서 사용할 수 있었다.
이후 사용자의 요청으로 아래의 원래 iOS 750ms 캐시 정책으로 변경했다. 현재 프레임에
새 인식 결과가 없어도 유효한 예외 캐시를 사용할 수 있다.

### 실기기 측정·검증 범위

SM-S931N(Android 16)의 제품 경로에서 GPU 선택을 확인했다. 단색·그라데이션·체커보드
합성 입력의 반복 추론 3종×6회에서 첫 표본을 제외한 GPU 시간은 59.94~63.17ms였고,
기존 ONNX 출력과 cosine 최솟값은 0.99985284, 최대 절대 오차는 0.00240524였다.
제품 `predict()` 경로 별도 3회는 67.18~69.83ms였다. 준비 단계의 평균은 CPU 471.93ms,
GPU 62.59ms였다. 앞선 기존 CPU 반복 측정은 402~422ms였다. GPU delegate 로그에서
1,690개 중 1,585개 노드가 OpenCL GPU에 배치됐고 나머지는 CPU로 처리됐다.
GPU 첫 compile/load는 약 4.4초로, hash 확인·CPU 기준 추론과 함께 준비 시간을 늘린다.
합성 출력 검증은 실제 인물의 인식 정확도를 증명하지 않는다.

CPU 대체 모델을 보존하므로 앱 모델 asset이 추가로 약 239MB(십진 바이트 기준) 늘어난다.
최초 사용 시 no-backup 파일 복사로 기기 저장 공간도 늘어난다. 실행 모델 메모리는 GPU
선택 후 초기 CPU 세션을 해제하지만 초기 검증 동안에는 두 엔진이 함께 존재한다.

검증: 단위 테스트 171개, 제품 GPU 선택·출력 일치·비동기 얼굴 확인·crop 생명주기
실기기 테스트 8개 통과(최초 GPU 변경 시점). 당시 비동기 테스트는 멈춘 작업이 송출을 기다리게 하거나 작업을
쌓지 않는지, 750ms 안의 늦은 결과·같은 위치의 다른 사람·크기 변경·reset 결과가
예외를 허용하지 않는지를 실제 coordinator와 Bitmap으로 확인한다.

실제 얼굴 장면에서 YOLO와 GPU 추론의 동시 부하, 다인 매칭 정확도, 발열·지속 FPS,
YouTube 수신 FPS, Android 11·다른 GPU의 선택/대체 경로는 미검증이다. 방송 30fps를
보장하는 수치가 아니다. 추가 WebRTC 프레임·실제 모델·픽셀 변환·성능 회귀 테스트 12개도 통과했다. 이 실행의 합성 반복 얼굴 추론은 60.40~60.98ms였다. 전체 프레임(빈 얼굴 검출) 측정은 720p 103.87~107.00ms, 1080p 123.10~145.47ms로 얼굴 장면 동시 부하를 측정한 결과는 아니다.

- [LiteRT Android CompiledModel 안내](https://developers.google.com/edge/litert/next/android_kotlin)
- [공식 PyTorch 변환 도구](https://github.com/google-ai-edge/litert-torch)
- GPU 및 비동기 재현: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.framework.innolive.feature.live.privacy.PrivacyFaceGpuDeviceTest,com.framework.innolive.feature.live.privacy.PrivacyFaceAsyncDeviceTest,com.framework.innolive.feature.live.privacy.PrivacyFaceCropDeviceTest --offline`


## 원래 iOS 얼굴 예외 정책 적용 (2026-09-27)

Android 미디어·얼굴 인식 일정만 변경했다. iOS 소스는 변경하지 않았으며 API·시그널링,
저장 데이터 형식, 모델 가중치, LiteRT GPU 및 ONNX CPU 대체 경로는 유지한다.
기준은 iOS의 `PrivacyFaceTracking.swift`와 [iOS 얼굴 등록 명세](ios-local-face-registration.md)다.

| 항목 | Android와 원래 iOS의 정책 |
| --- | --- |
| 최초 허용 | 같은 등록자 2회 확인. 확인 사이가 1초 이상이면 새로 시작 |
| 예외 유효 기간 | 마지막으로 일치한 샘플의 촬영 시각부터 최대 750ms, 만료 시 블러 복귀 |
| 재확인 | 트랙별 최소 250ms 간격. 가장 오래 기다린 트랙 우선 |
| 작업 지연 | 송출을 기다리지 않고 진행 작업 1개·결과 mailbox 1개로 제한 |
| 미등록자·다른 등록자 | 기존 예외 취소. 다른 등록자는 2회 확인부터 다시 시작 |
| 샘플 없음 | 기존 만료 시각 유지. 예외 기간 연장 없음 |
| 겹침·모호한 대응·소실 | 기존 트랙과 예외 폐기 |
| 같은 등록자 중복 트랙 | 두 트랙 모두 예외 거부 |
| 프레임 간격 | 200ms 이상·시각 역행이면 트랙 초기화 |
| 카메라·회전·크기·등록 목록 변경 | 초기화 및 진행 중 결과 무효화 |

Android는 직전 YOLO 위치로 현재 프레임의 crop을 먼저 작업자에게 보낸다.
iOS는 현재 YOLO 결과를 받은 뒤 crop을 만든다. 이 일정 차이 때문에 Android는 결과의
얼굴 위치와 현재 트랙 위치의 IoU도 검증한다. 플랫폼별 이미지 처리와 모델 실행은
각자의 API를 사용하며, iOS Core ML을 Android에서 실행하는 구성은 아니다.

### 허용하는 한계

- 같은 위치에 다른 사람이 들어오더라도 인식 결과가 오기 전에는 기존 예외가 남을 수
  있다. 한도는 마지막 일치 샘플 촬영부터 750ms이며, 교체 시점부터 새로 750ms를
  주는 것은 아니다. 이전의 매 프레임 확인 정책을 완화한 의도된 동작이다.
- 프레임 처리 간격이 200ms 이상인 기기는 트랙이 매번 초기화되어 같은 얼굴도 2회
  확인을 쌓기 어렵다. 원래 iOS에도 있는 조건이며 이번 변경에서 임의로 늘리지 않았다.
- 합성 프레임과 제어된 인식 결과 테스트는 실제 다인 인식 정확도·YouTube FPS·장시간
  발열을 증명하지 않는다. 실제 방송 검증은 별도로 필요하다.

### 검증

Android 빌드·단위 테스트 176개와 실제 coordinator·Bitmap을 사용하는 SM-S931N
계측 테스트 9개가 통과했다. 늦게 도착한 2회 확인 결과, 750ms 만료, 250ms 재확인, 미등록자 판정, 샘플 없음,
다른 위치 결과, 겹침·소실·reset, 200ms 이상 프레임 간격을 검증했다.
iOS의 수정하지 않은 실제 `PrivacyFaceTracking.swift`와 실제 IoU 함수를 호스트 Swift에서
실행해 동일한 정책 시나리오 9개도 통과했다. iOS 전체 앱 빌드는 이번 Android 변경의
검증 범위에 포함하지 않는다.

재현: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.framework.innolive.feature.live.privacy.PrivacyFaceAsyncDeviceTest,com.framework.innolive.feature.live.privacy.PrivacyFaceCropDeviceTest --offline`


## iOS 송출 처리 경로 이식 후속 (2026-09-27)

Android 미디어 처리만 변경하며 서버 API·시그널링·얼굴 등록 저장 형식은 유지한다.

- iOS와 동일한 pinned YOLO 체크포인트를 FP32 LiteRT로 변환했다. Android asset
  `privacy-detector.tflite`의 SHA-256은
  `c8c0c6b4e93a974e748cbd41a0120423180499b563812dc65dcdbc1a54b54895`다.
  두 합성 입력의 호스트 ONNX 대비 detection 최대 절대 오차는 0.000793,
  prototype은 0.000022 이하였다. FP16 가중치 실험은 detection 오차가
  0.56 이상으로 커져 제품에 사용하지 않았다.
- 기기에서 GPU+CPU LiteRT 버퍼 생성, 합성 입력 3종 출력 일치, CPU 대비
  속도 향상을 검사한 뒤에만 GPU를 선택한다. 생성·검사·실행이 실패하면 그
  프레임부터 기존 ONNX CPU 경로를 사용한다. 에뮬레이터의 OpenGL delegate는
  전체 그래프를 위임했지만 GPU 입력 버퍼를 만들지 못했고, 제품 CPU fallback은
  프레임 테스트를 통과했다. 에뮬레이터의 LiteRT CPU 출력은 ONNX와 일치했다.
  SM-S931N의 GPU 선택·속도는 아래 실기기 계측에서 확인했다.
- CameraX는 보호 프레임을 한 번에 하나만 별도 작업자에 넘기고 ImageProxy를
  즉시 닫는다. 작업 중 도착한 프레임은 복사 전에 버린다. 송출 모드·카메라
  변경과 종료 시 이전 세대의 결과를 송출하지 않는다. Debug 로그에 5초마다
  보호 프레임 수신·바쁨으로 폐기·송출 건수를 기록한다. WebRTC의 영상 sender
  통계에서도 인코딩·송신 프레임, 패킷, 바이트의 누적값을 5초마다 기록해
  분석 처리량과 인코더·네트워크 구간을 구분할 수 있게 했다. 실제 카메라
  crop 크기에 맞춰 WebRTC 출력 형식을 최대 30fps로 설정한다.
- iOS `PrivacyMaskStabilizer`의 객체별 IoU 0.30 매칭, 0.12초 경계 감쇠,
  0.20초 이상 간격 초기화, 사라진 객체의 즉시 제거를 Android에 적용했다.
  새 보호 영역은 즉시 적용하며 얼굴 블러 예외 정책에는 영향을 주지 않는다.

Android는 여전히 CameraX YUV→Bitmap→I420 변환과 마스크 CPU 합성을 거친다.
iOS의 Core Image→CVPixelBuffer 경로와 같은 GPU 종단 처리라고 해석하면 안 된다.
iOS에는 1080p/720p의 30/24fps 실제 capture preset이 있지만 Android에는
해상도 선택과 WebRTC 30fps 출력 상한만 있다. 실제 방송 FPS·인코더·YouTube 수신,
GPU와 얼굴 인식의 동시 부하 및 발열은 실기기에서 확인해야 한다.

검증: Android 단위 테스트 179개 통과. Pixel_10 에뮬레이터에서 LiteRT CPU/ONNX 출력 비교, GPU 미지원 시 제품 ONNX fallback, 프레임 회전·생명주기·반복 처리, 보호 작업 중 CameraX 입력 즉시 해제·바쁜 프레임 폐기 테스트를 통과했다. GPU 실기기 실행 테스트는 에뮬레이터에서 건너뛰며 SM-S931N에서 아래와 같이 실행했다.


## SM-S931N 송출 병목 및 GPU 정밀도 수정 (2026-09-27)

온디바이스 AI 실제 방송 로그에서 1920×1080 프레임의 YOLO 추론은 89~151ms,
전체 처리 시간은 135~224ms였다. 5초 동안 들어온 프레임 106~157개 중 12~22개만
보호 처리 후 송출했고 WebRTC 인코딩·송신 프레임 수도 같은 속도로 증가했다.
이 장면의 주 병목은 인코더나 네트워크가 아니라 보호 프레임 처리였다.

실기기 GPU delegate는 465/465 노드를 위임했지만 기존
`FP16_WITH_FP32_ACCUM` 설정은 고정 입력에서 ONNX CPU 대비 detection 최대 절대
오차 3.7686, prototype 0.0225로 정확도 검사를 실패했다. 따라서 제품은 계속 CPU
추론을 썼다. 같은 FP32 모델을 GPU `FP32` 정밀도로 실행하면 오차가 각각 최대
0.00177, 0.000014였고 3종 고정 입력의 반복 추론은 약 12ms였다. 같은 입력의
ONNX CPU 추론은 약 79~89ms였다. 제품 검증 경로에서도 GPU 선택 로그를 확인했다.
정확도 또는 속도 검사에 실패하는 다른 기기는 기존 ONNX CPU로 안전하게 대체한다.

1080p 합성 프레임의 전체 제품 처리 시간은 GPU 적용 후 보호 영역이 없는 경우
반복 표본에서 약 40~50ms였다. 전체 보호 마스크 렌더는 GPU 블러만 약 8ms,
기존 합성 포함 약 31~35ms였다. 네이티브 합성에서 마스크 좌표를 재사용하고
완전 불투명·투명 픽셀은 복사하도록 바꾼 뒤 전체 보호 렌더가 약 18~19ms가 됐다.
CPU 블러 경로의 동일 1080p 전체 보호 렌더는 약 143~161ms여서 GPU 블러를 유지한다.
이 수치는 합성 입력 단기 측정이며 실제 얼굴·번호판 장면과 장시간 방송 FPS를
보장하지 않는다.

실기기 검증: YOLO GPU/ONNX 출력 비교 2개, 제품 프레임/렌더 계측 1개,
네이티브 픽셀 변환·합성 테스트 6개가 SM-S931N에서 통과했다. 최초 GPU
정밀도 비교 테스트는 의도대로 실패해 CPU fallback 원인을 드러냈으며 FP32로
바꾼 뒤 통과했다. 실제 방송에서 개선된 송출 FPS, YouTube 수신 품질, 동시 얼굴
인식 부하·발열은 추가 확인이 필요하다.


## GPU 적용 후 실제 방송 지연 추가 분석 (2026-09-27)

SM-S931N에서 온디바이스 모드 전환 후 제품 `gpu_validated`를 확인했다.
첫 보호 프레임은 GPU 준비·CPU 출력 비교 때문에 약 4.06초 걸렸다. 이후 실제
1080p 방송의 보호 처리·송신은 약 5~6fps였으며 모델 처리 로그는 약 66~129ms였다.
GPU 추론은 초기 약 15~18ms에서 이후 약 33~41ms로 늘었다. 앞서 서버 AI 상태에서
보인 화면 렌더 약 17fps는 이 온디바이스 성능 결과에 포함하지 않는다.

### 확인한 복사 병목과 수정

기존 CameraFrameAnalyzer는 YUV 색차 평면의 pixelStride가 2이면 각 픽셀을
Kotlin `ByteBuffer.get/put`으로 복사했다. 이 구간은 PrivacyFrameProcessor의
타이밍에 포함되지 않았다. 실기기에서 960×540 색차 평면 하나의 기준 복사
시간은 약 20.09ms, 네이티브 복사는 약 0.26ms였다. U/V 두 평면은 각각 복사한다.

네이티브 복사에 행별 memcpy와 ARM NEON deinterleave를 적용했다. 버퍼 시작
위치·limit, 행 여백, pixelStride 1/2/3, 홀수 폭, 마지막 행 여백이 없는 경우를
검증했다. 실제 CameraFrameAnalyzer를 통해 1080p 합성 프레임을 복사하고 모델로
보호 출력한 반복 11개 표본은 카메라 복사 1.43~1.90ms, 전달까지 62.65~79.61ms였다.
카메라 HAL·실제 WebRTC 인코더·서버·YouTube는 이 합성 테스트에 포함되지 않는다.
카메라 복사·실제 sender callback 시간은 제품 Debug 로그에 별도로 추가했다.

### iOS와 다른 비용 및 검증 수준

| 구간 | iOS 코드 | Android 코드와 확인 결과 |
| --- | --- | --- |
| 캡처 입력 | CVPixelBuffer를 작업자에게 전달 | CameraX YUV 평면을 I420로 복사. 위 병목을 수정 |
| 변환·회전 | Core Image의 CIImage 변환 | I420→ARGB Bitmap, Canvas 회전, 처리 뒤 역회전·I420 변환. 실방송에서 입력·출력 합계가 약 20~77ms인 표본 존재 |
| YOLO 실행 | Core ML `.cpuAndNeuralEngine` 허용 | LiteRT GPU FP32. 같은 체크포인트여도 실행 엔진과 허용 가속기가 다름 |
| 블러·합성 | Core Image 필터·마스크 합성 | GPU 블러 결과를 CPU Bitmap으로 복사한 뒤 네이티브 CPU 합성. 현재 전체 GPU 경로가 아님 |
| 얼굴 모델과 YOLO 동시 실행 | AdaFace는 CPU/GPU, YOLO는 CPU/Neural Engine 허용 | 두 모델이 GPU를 공유. 아래 순간 지연을 재현 |
| 처음 준비 | 모드 전환 전 prepareLocalProcessing으로 모델 준비 | 첫 보호 프레임에서 GPU 검증·컴파일. 시작 시 멈춤을 설명하지만 지속 저속의 전부를 설명하지 않음 |
| 캡처 주기 | 선택한 24/30fps를 실제 capturer에 지정 | 해상도 선택 및 WebRTC 30fps 상한. CameraX 캡처 FPS를 명시하지 않음. 추가 부하 가능성은 있으나 5~6fps의 직접 원인으로 확정하지 않음 |

Core ML computeUnits는 허용하는 실행 장치이며 모든 연산의 실제 Neural Engine
배치를 보장하지 않는다. iOS도 CGImage 생성·CVPixelBuffer 출력이 있으므로 완전한
zero-copy라고 단정하지 않는다.

### GPU 경합 재현

같은 기기·같은 프로세스에서 실제 얼굴 GPU 모델과 YOLO GPU 모델을 준비했다.
YOLO만 실행 → 얼굴 임베딩을 250ms 간격으로 동시 실행 → YOLO만 실행 순서로
각 2초 계측했다. 원본 얼굴 대신 합성 112×112 입력을 사용했다.

| 상태 | YOLO 중앙값 | YOLO p95 |
| --- | ---: | ---: |
| 얼굴 GPU 작업 없음 (앞) | 23.56ms | 24.65ms |
| 얼굴 GPU 작업 동시 실행 | 23.70ms | 78.11ms |
| 얼굴 GPU 작업 없음 (뒤) | 23.91ms | 26.29ms |

이 결과는 등록 얼굴을 실제로 재확인하는 상황에서 순간 지연이 늘 수 있음을
보인다. 실제 사용자의 방송에서 얼굴 인식이 수행된 빈도까지 입증한 것은 아니다.
GPU·블러·카메라·화면 렌더·인코더의 장시간 동시 부하와 발열 기여도도 분리하지 못했다.

### 원인으로 확정하지 않은 항목

- GPU 선택 후 보존하는 ONNX CPU 세션: thread spinning을 끄는 비교를 실행 순서
  true/false/false/true로 반복하고 준비 후 1초 기다렸다. 마지막 기본 설정의
  GPU 평균 추론 18.75ms는 spinning 비활성의 19.00~19.82ms와 비슷했고 2초 유휴
  CPU 사용도 0ms였다. 지속 저속의 주원인으로 일관되게 재현되지 않았다.
- GC: 실제 방송 로그에서 일부 약 5~7ms 정지와 40MB 이상의 객체 회수가 보였다.
  프레임마다 큰 출력 배열·비트맵을 생성하는 부담은 존재하지만 GC만으로
  전체 5~6fps를 설명하지 않는다.
- 네트워크·인코더: 송신 프레임이 보호 처리 완료 수를 따라갔다. 별도의 인코더
  처리 지연·YouTube 수신 지연을 배제한 것은 아니다. 새 전달 구간 계측으로
  후속 실방송에서 확인해야 한다.

검증: 카메라·네이티브 픽셀 테스트 9개, 1080p 전체 보호 경로 계측 1개,
CPU 세션 비교 1개, GPU 경합 비교 1개가 실기기에서 통과했다. Android API·
시그널링·저장 데이터 계약 변경은 없다. 카메라 복사 수정 후 실방송 FPS는
아직 재측정하지 않았으므로 끊김 해결 완료로 보고하지 않는다.


## 방송 연결 없는 실제 카메라 AI 측정 (2026-09-27)

`PrivacyActualCameraDeviceTest`가 SM-S931N의 실제 전면 카메라를 CameraX
Preview·ImageAnalysis로 열고 제품 CameraFrameAnalyzer를 거쳐 로컬 WebRTC
SurfaceViewRenderer에 처리 결과를 표시한다. 별도의 빈 Debug Activity를 사용하므로
서버 세션·PeerConnection·방송·YouTube를 만들지 않는다. 원본 미리보기와 처리
결과는 화면에만 표시하며 이미지·영상·얼굴 이름·embedding은 저장하지 않는다.
카메라 권한이 없다면 테스트가 CAMERA 권한을 부여한다.

요청한 1920×1080·1280×720 센서 출력 크기를 검사했다. 첫 보호 프레임과 추가
3초의 준비 구간을 제외하고 각 해상도를 25초씩 측정했다. 다음은 등록 얼굴·
번호판이 검출되지 않은 실제 장면의 결과다.

| 구간 | 1080p | 720p |
| --- | ---: | ---: |
| 실제 카메라 입력 | 30.00fps (750프레임) | 30.00fps (750프레임) |
| AI 처리 완료·로컬 표시 전달 | 9.28fps (232프레임) | 13.32fps (333프레임) |
| 제품 보호 처리 p50 / p95 | 86.32 / 112.82ms | 54.27 / 81.26ms |
| 카메라 평면 복사 p50 | 2.38ms | 0.32ms |
| 입력 변환·정방향 회전 p50 | 29.07ms | 14.28ms |
| YOLO 추론 p50 | 16.75ms | 17.55ms |
| 렌더 p50 (빈 보호 마스크) | 5.79ms | 2.59ms |
| 역회전·출력 I420 변환 p50 | 23.16ms | 10.45ms |
| 로컬 renderer 전달 p50 | 0.061ms | 0.066ms |
| YOLO GPU 사용 프레임 | 232/232 | 333/333 |
| 처리 오류 | 0 | 0 |

카메라는 30fps로 들어왔으며 GPU 추론도 실행됐다. 현재 장면에서는 CPU를
거치는 입력·출력 변환과 회전의 중앙값 합계가 1080p에서 약 52ms다. 이 값은
카메라 복사 최적화 후에도 남은 큰 비용이며 구간별 중앙값의 합이다. 따라서
현재 구현은 GPU 모델을 사용하면서도 처리량이 24~30fps에 미치지 못할 수 있다.

이 실행에서 얼굴·번호판·보호 영역은 두 해상도 모두 0프레임이었다. 실제 객체의
양성 검출·블러 적용이나 등록자 예외의 정확도를 증명하지 않는다. 제품 Debug 숫자 진단에 검출·예외·보호 마스크·GPU 블러 적용 건수를 추가했다.
후속 얼굴 장면 측정은 아래에 별도로 기록한다. 인코더·네트워크·YouTube 수신 및 15분 지속 발열도 이 테스트
범위에 포함되지 않는다.

재현: `./gradlew :app:connectedDebugAndroidTest --offline -Pandroid.testInstrumentationRunnerArguments.class=com.framework.innolive.feature.live.PrivacyActualCameraDeviceTest`


### 얼굴이 보이는 장면의 후속 실기기 측정

사용자가 전면 카메라에 공개된 얼굴을 비춘 상태로 같은 테스트를 다시 실행했다.
모델 예열 후 각 해상도를 25초 측정했으며, 수치는 화면 표시 전달까지의 AI
처리량이다. SurfaceViewRenderer의 실제 표시 FPS를 별도 집계한 값은 아니다.

| 구간 | 1080p | 720p |
| --- | ---: | ---: |
| 실제 카메라 입력 | 29.92fps (748프레임) | 30.00fps (750프레임) |
| AI 처리 완료·로컬 표시 전달 | 7.68fps (192프레임) | 9.60fps (240프레임) |
| 제품 보호 처리 p50 / p95 | 108.68 / 152.24ms | 82.16 / 116.95ms |
| 카메라 평면 복사 p50 | 2.08ms | 0.36ms |
| 입력 변환·정방향 회전 p50 | 25.90ms | 14.79ms |
| YOLO 추론 p50 | 16.50ms | 17.57ms |
| 블러·마스크 합성을 포함한 렌더 p50 | 36.34ms | 28.25ms |
| 역회전·출력 I420 변환 p50 | 19.48ms | 8.69ms |
| YOLO GPU 사용 프레임 | 192/192 | 240/240 |
| 얼굴 검출 / 보호 마스크 / GPU 블러 프레임 | 165 / 165 / 165 | 240 / 240 / 240 |
| 등록자 예외 / 번호판 검출 프레임 | 0 / 0 | 0 / 0 |
| 처리 오류 | 0 | 0 |

얼굴 검출과 GPU 블러 실행은 실제 카메라 입력에서 확인했다. 1080p의 27프레임은
얼굴 검출이 없었으므로 모든 프레임의 얼굴 검출을 주장하지 않는다. 보호 출력은
테스트 화면에서 표시했으며, 블러의 픽셀 정확도·보호 마스크 유지·프레임 회전과
수명은 별도 실기기 회귀 테스트 10개가 통과했다. 등록자 예외 정확도·번호판
검출·방송 인코더·YouTube 수신·장시간 발열은 이번 실행에서 검증하지 않았다.

방송 연결 없이도 약 8~10fps가 재현돼 앱 내부 영상 처리만으로 느려지는 경로가
확인됐다. YOLO GPU 적용 여부와는 별개로 CPU 입력·출력 변환과 회전,
GPU 블러 결과 readback 및 CPU 마스크 합성이 남아 있다. 렌더 구간 수치만으로
GPU 블러와 CPU 합성 각각의 비용을 분리했다고 보고하지 않는다.


## GPU 영상 그래프와 WebRTC 텍스처 출력 (2026-09-27)

대상은 Android 카메라·로컬 AI·미디어 경로이며 HTTP·시그널링·저장 계약 변경은 없다.
기존 이슈 #320 / Draft PR #324에서 이어 구현했다.

### 제거한 전체 영상 CPU 처리

- `PrivacyGpuFramePipeline`: 전용 HandlerThread/EGL context에서 카메라 Y/U/V를 업로드하고
  YUV→RGB·정방향 회전·640px letterbox를 GPU에서 처리한다. CPU 입력·출력용 전체
  센서 Bitmap 및 Canvas 회전은 기본 GPU 경로에서 사용하지 않는다.
- 픽셀화(24px)·분리 Gaussian(sigma 24px)·마스크의 2px opaque core·4px dilation·
  sigma 1.5 feather·원본과의 mask 합성을 GPU shader로 연결한다. 블러 결과를 CPU
  Bitmap으로 가져와 네이티브 합성하던 왕복은 제거했다.
- GPU에서 센서 방향으로 복원한 RGB 텍스처를 WebRTC TextureBufferImpl로 전달한다.
  영상용 EGL context를 인코더/renderer와 공유하고 쓰기가 끝난 뒤 전달한다.
  제품 경로의 CPU Bitmap→I420 출력 변환은 제거했다. 소프트웨어 소비자가 I420을
  요청하면 WebRTC YuvConverter가 GL 작업자에서 변환한다.
- 640×640 모델 입력의 readback과 RGB→NCHW float tensor 채우기는 남는다. 현재
  LiteRT Kotlin 2.2 API의 float tensor 입출력 때문에 전체 zero-copy 추론을 주장하지 않는다.
  YOLO decode/instance mask/stabilizer도 CPU이며 기존 iOS의 정책·수학을 유지한다.
- 얼굴 coordinator는 Bitmap 전체를 요구하지 않고 좌표와 crop 공급자를 받는다.
  등록 얼굴과 재확인할 트랙이 있고 얼굴 worker가 받을 수 있을 때만 GPU에서 그
  crop을 읽는다. crop 크기·랜드마크 좌표·등록자 캐시 정책은 유지했다.
- 텍스처 출력 pool은 최대 6개의 보유 프레임으로 제한한다. renderer/encoder가
  놓기 전에는 재사용하지 않으며 모두 보유 중이면 모델 입력 준비·추론 전에 새 입력을 버린다. 종료 후에도
  보유 프레임의 변환·읽기가 가능하고 마지막 참조 해제 뒤 EGL/converter를 닫는다.
  입력 plane 텍스처·모델 입력·마스크 업로드 버퍼·crop readback 버퍼는 재사용한다.
  카메라/모드/회전/해상도/얼굴 목록 변경은 기존 세대 검사를 따른다.

### 추론 가속 차이와 제약

iOS YOLO는 Core ML `.cpuAndNeuralEngine`, AdaFace는 `.cpuAndGPU`를 허용한다.
Android의 기존 LiteRT FP32 GPU 검증은 유지하며, 실제 NNAPI neural accelerator가
노출되는 기기에 한해 동일 SHA의 FP32 ONNX 모델을 NNAPI CPU_DISABLED로 비교한다.
CPU 기준 출력과 detection 최대 오차 <0.05·prototype <0.01, 기존 가장 빠른 경로보다
반복 시간이 10% 이상 짧은 경우에만 선택한다. 실행 오류는 검증된 GPU/CPU로 돌아간다.
NNAPI의 부분 그래프가 CPU에서 실행될 수 있으므로 NPU 전체 실행이라고 부르지 않는다.

현재 SM-S931N(Android 16)은 `nnapi_neural_accelerators=0`을 보고했다. NNAPI 선택은
실행되지 않았고 GPU 추론이 사용됐다. NNAPI 장치 0개는 물리적 NPU가 없다는 의미가 아니다.
장치가 노출되는 경우의 NNAPI 선택·속도는 이 실기기에서 검증하지 못했다.

[공식 LiteRT Qualcomm 안내](https://developers.google.com/edge/litert/next/qualcomm)와
[NPU 배포 안내](https://developers.google.com/edge/litert/next/npu)에 따라 LiteRT v2.2.0
JIT compiler/dispatch와 QAIRT 2.47.0.260601의 v79 HTP 런타임으로 직접 실행도 시험했다.
공식 SDK 다운로드 HEAD 요청은 403이었으나 GET 다운로드는 성공했다. 라이브러리는
진단용 앱 비공개 임시 폴더에만 배치했고 APK·Git에는 넣지 않았다.

`PrivacyNpuProbeDeviceTest`에서 같은 FP32 TFLite asset과 세 합성 입력을 사용해
NPU 실행 및 유한 출력을 확인했다. QNN 로그에서 HTP 그래프 실행 성공과 FP16
convolution 연산을 확인했다. 출력 읽기까지 포함한 반복 중앙값은 NPU
23.94/24.06/24.03ms, GPU FP32 16.97/16.87/16.79ms였다. ONNX CPU 대비 NPU의
detection 최대 절대 오차는 16.57/19.22/3.51, prototype은 0.0882/0.0541/0.0237이었다.
세 입력 모두 제품의 출력 일치 기준을 통과하지 못했다. 합성 입력의 최대 오차만으로
실제 얼굴·번호판 검출 정확도가 저하됐다고 단정하지 않는다. 이 경로의 실제 보호
정확도는 검증되지 않았으며 속도 개선도 확인되지 않아 제품 추론으로 선택하지 않았다.
이 진단 테스트의 통과는 실행·유한 출력 확인을 뜻하고 `parity_pass=false`를 기록한다.
공식 HTP의 float16 계산 제약은 [Qualcomm 안내](https://workbench.aihub.qualcomm.com/docs/hub/api.html)도 설명한다.

### 검증과 측정 해석

실기기에서 방향 0/90/180/270의 비대칭 사분면, 정방향 crop의 위치·독립 수명,
블러 보호 중심과 보호 밖 영역, 보유 텍스처의 후속 프레임/해상도 변경/종료 후
수명, 6프레임 상한과 해제 후 재개, padded plane·홀수 크기, 픽셀화 경계의 Gaussian 효과를 검증했다. H.264 HardwareVideoEncoderFactory에
실제 보호 텍스처를 넣고 정상 encoded callback을 받았다. 네트워크·방송 서버·
YouTube는 만들지 않았다. 기존 모델/프레임/얼굴 비동기·crop과 합쳐 실기기 회귀 테스트 총 24개,
단위 테스트 179개가 통과했다. 최종 GPU/인코더 8개도 재실행해 통과했다.
Qualcomm NPU 선택적 진단 1개는 별도이며 제품 정확도 검증 통과로 세지 않는다.

실제 CameraX 카메라에서도 입력 약 30fps, GPU 모델 및 texture output, 검출된
보호 영역의 GPU 블러, 오류 0을 확인했다. 버퍼 재사용을 포함한 마지막 측정은 다음과 같다.

| 항목 | 1080p | 720p |
| --- | ---: | ---: |
| 측정 시간 | 25초 | 25초 |
| 카메라 입력 / 처리 프레임 | 750 / 389 | 750 / 438 |
| 처리 FPS | 15.56 | 17.52 |
| 전체 프레임 처리 p50 / p95 | 39.63 / 53.79ms | 39.39 / 54.62ms |
| 입력 변환·회전 p50 | 3.11ms | 2.47ms |
| YOLO 추론 p50 | 23.53ms | 24.60ms |
| 보호 프레임 수 | 92 | 56 |
| 보호 프레임 render p50 | 8.69ms | 10.69ms |
| 보호 프레임 전체 처리 p50 | 48.72ms | 53.44ms |
| GPU 추론 / texture 출력 | 389 / 389 | 438 / 438 |
| 처리 오류 | 0 | 0 |

제품 GPU 경로의 CPU 출력 변환 구간은 0ms다. 출력의 GPU 복원과 동기화 비용은
render 구간에 포함되므로 출력 자체가 무료라는 뜻이 아니다. CameraX plane 복사는
별도 copy 구간이며 모델 입력 readback, float tensor 입출력과 CPU 후처리도 남아 있다.
이 기기에서 30fps 처리 또는 장시간 방송·발열 개선이 검증됐다고 주장하지 않는다.

앞선 얼굴 장면의 7.68/9.60fps와 다른 시간·장면이며 열/클럭·보호 대상 개수를
통제한 비교가 아니다. 특히 빈 마스크와 보호 프레임이 섞인 전체 render 중앙값을
보호 프레임만의 블러 비용으로 쓰지 않는다. 실기기 진단은 보호 프레임의 render·
전체 처리 중앙값을 따로 출력한다. 숫자 count와 timing만 보관하고 얼굴 사진·
이름·embedding·토큰은 저장하거나 로그에 출력하지 않는다. 번호판 분류 count는
모델 출력이며 번호판 ground truth 검출 정확도 검증으로 사용하지 않는다.

재현: `PrivacyGpuFramePipelineDeviceTest`, `PrivacyGpuEncoderDeviceTest`,
`PrivacyActualCameraDeviceTest`를 `connectedDebugAndroidTest`의 class 필터로 실행한다.
실제 카메라 테스트는 잠금이 해제된 foreground 화면이 필요하며 측정 중 KEEP_SCREEN_ON을 적용한다.

Qualcomm 직접 진단은 공식 LiteRT v2.2.0 JIT zip의 v79 compiler/dispatch와 QAIRT
2.47.0.260601의 `libQnnHtp`, `libQnnIr`, `libQnnSaver`, `libQnnSystem`,
`libQnnHtpPrepare`, `libQnnHtpV79Stub`, `libQnnHtpV79Skel`을 앱의
`files/privacy-npu-probe/`에 임시 배치한 뒤 `PrivacyNpuProbeDeviceTest`를 실행한다.
라이브러리가 없으면 이 선택적 진단은 skip되며 제품 동작에 영향이 없다. 테스트 후
진단용 폴더를 삭제한다. SDK 바이너리를 저장소에 재배포하지 않는다.


## 단계별 추가 최적화와 실제 카메라 비교 (2026-09-27)

이 절은 카메라 직접 입력 후속 적용 전의 측정 기록이다. 현재 구현과 최종 비교는
다음 절의 “카메라 직접 입력과 LiteRT 입력 동기화”를 따른다.

Android의 카메라·로컬 AI·WebRTC 영상 경로만 변경한다. 서버 API·시그널링·모델
가중치·보호 정책에는 변경이 없다. iOS의 Accelerate 행렬 계산, GPU 모델 입력,
픽셀 버퍼 재사용, GPU 완료 동기화, 단일 채널 마스크를 참고했다.

각 후보는 동일 입력을 번갈아 처리해 출력과 중앙값을 비교했다. 아래 시간은 구간별
측정이며 합산해 전체 프레임 지연이나 FPS로 환산하지 않는다.

| 순서 | 후보와 판단 | 동일 입력의 변경 전 → 후 중앙값 |
| --- | --- | --- |
| 1 | prototype × coefficients를 C++/NEON으로 이동. 채널 누적 순서와 >0 경계를 유지하고 채택 | 객체 1개 1.01 → 0.53ms, 8개 4.37 → 1.04ms |
| 2 | 공식 LiteRT C API의 GL SSBO 입력. 출력·속도 검증 통과 시 선택 | 모델 전처리·입력·추론 25.02~26.12 → 18.51~19.32ms |
| 3 | 카메라 I420 버퍼 풀. 마지막 crop/view 해제 후 재사용, idle 상한 1개. 1080p 이득으로 채택 | 모든 plane 복사 포함 1080p 1.10 → 0.15ms. 720p 0.051 → 0.051ms로 개선 없음 |
| 4 | 출력 EGL fence + glFlush. 소비자 GL 큐가 기다리고 CPU 변환은 완료를 기다림. 지원 없으면 glFinish | 1080p 보호 영상 인계 14.19 → 4.87ms. GPU 완료까지의 시간 감소를 뜻하지 않음 |
| 5 | 검사한 prototype만 별도 타입으로 전달해 두 번째 전체 검사를 제거. GPU/CPU 오류 시 보호 fallback 유지 | 객체 0/1/8개: 1.42/1.47/1.83 → 0.64/0.69/1.04ms |
| 6 | iOS의 8bit grayscale mask처럼 단일 채널 업로드. RGBA 4회 쓰기를 bulk copy로 대체 | 불투명/감쇠 mask render 2.70/2.64 → 0.56/0.52ms |

마스크·출력 I420 픽셀은 비교 입력에서 byte 단위로 동일했다. SIMD tail, 영점 누적,
빈 instance의 bbox 보호, NaN/Infinity/overflow, crop·회전·보유 프레임 수명,
6프레임 상한, shared EGL H.264 하드웨어 인코딩을 검증했다. 단위 테스트 179개와
단계별 실기기 테스트가 통과했다. 단일 채널 최종 GPU/입력/인코더 회귀 11개도 통과했다.

LiteRT JNI는 Maven 2.2.0에 포함된 런타임의 공개 C API를 사용한다. 같은 FP32 asset,
출력 shape와 유한값 검사를 유지한다. Kotlin 비공개 native handle을 사용하지 않는다.
동봉한 C 헤더의 출처는 `src/main/cpp/vendor/litert/README.md`에 기록했다.
별도 런타임 바이너리는 추가하지 않았다. GPU 직접 입력 실패 시 현재 모델 텍스처를
읽어서 보호 경로로 처리하고 이후 프레임마다 재준비하지 않는다.

### 실제 입력 경로 선택의 차이

초기 속도 판정은 이미 GPU 처리가 끝난 texture의 readback을 비교하고 있었다.
수정 후 실제 카메라의 YUV·회전·letterbox 작업도 다시 제출하고 3회 warmup 후
12회 순서를 교대로 비교한다. 정확도 검증과 중앙값 5% 이상 개선,
12회 중 9회 이상 개선을 모두 통과해야 직접 입력을 선택한다.

독립 벤치마크에서는 직접 입력이 개선됐지만 실제 카메라에서 항상 선택되지는 않았다.
최종 1080p 판정은 21.57 → 19.94ms이나 6/12회 개선, 720p는
19.23 → 18.42ms와 8/12회 개선으로 보수적인 반복 기준을 통과하지 못했다.
두 해상도 모두 기존 **GPU FP32** 입력 경로를 선택했다. CPU 추론으로 돌아간 것이 아니다.
앞선 별도 카메라 실행에서는 720p 직접 입력이 선택되어 22.64fps를 기록했으나
보호 대상 검출이 없었던 실행이므로 보호 장면의 성능으로 사용하지 않는다.

### 최적화 전후 카메라 측정

SM-S931N·Android 16, 입력 약 30fps, foreground CameraX + GPU 처리 + 로컬 renderer,
해상도마다 초기 준비 후 25초 수집했다. 이전 커밋 `76cf5ce`의 앱을 임시 폴더에서
빌드해 먼저 측정하고 최종 앱 `e6af9cc`로 복원해 측정했다. 방송·서버·YouTube는
사용하지 않았으며 영상·얼굴 이름·embedding은 저장하지 않았다.

| 항목 | 1080p 이전 → 최종 | 720p 이전 → 최종 |
| --- | ---: | ---: |
| 처리 FPS | 15.92 → 17.72 | 17.12 → 15.96 |
| 전체 처리 p50 / p95 | 39.84 / 55.78 → 36.72 / 42.65ms | 39.58 / 46.57 → 43.26 / 58.12ms |
| 보호 프레임 처리 p50 | 49.17 → 37.87ms | 52.31 → 40.34ms |
| 보호 render p50 | 8.77 → 1.68ms | 8.82 → 1.54ms |
| YOLO inference p50 | 23.48 → 24.70ms | 25.30 → 29.16ms |
| 카메라 plane 복사 p50 | 3.40 → 1.11ms | 0.59 → 0.49ms |
| 처리 / 보호 프레임 수 | 398 / 134 → 443 / 51 | 428 / 15 → 399 / 134 |
| GPU 추론·texture 출력 | 전체 처리 프레임 | 전체 처리 프레임 |
| 처리 오류 | 0 → 0 | 0 → 0 |

보호 처리 지연은 두 해상도에서 감소했지만 실제 FPS 개선은 일관되지 않았다.
특히 720p는 보호 대상 빈도와 추론 시간이 달랐다. 온도·GPU 클럭·얼굴 위치/개수를
통제하지 않은 연속 실행이므로 720p FPS 차이의 원인을 최적화 또는 발열로 단정하지
않는다. 동일 입력 구간별 개선과 실제 카메라 결과를 구분한다. 30fps 유지,
장시간 방송·발열·YouTube 수신 FPS 개선은 이번 결과로 주장하지 않는다.

이 측정 시점에는 카메라의 원본 plane 복사와 GPU 출력의 CPU 후처리, 조건부 모델 입력 readback이
남아 있었다. 전체 카메라 texture 입력으로의 전환과 GPU 추론의 장시간 일정은
별도 측정이 필요하다. Core ML·Core Image API 자체를 Android에 복사한 것은 아니다.

재현: `PrivacyNativeSegmentationDeviceTest`, `PrivacyGpuInputDeviceTest`,
`PrivacyCameraBufferPoolDeviceTest`, `CameraFrameAnalyzerDeviceTest`,
`PrivacyGpuFenceDeviceTest`, `PrivacyCompactMaskDeviceTest`,
`PrivacyGpuFramePipelineDeviceTest`, `PrivacyGpuEncoderDeviceTest`,
`PrivacyOnnxModelDeviceTest`, `PrivacyActualCameraDeviceTest`.
EGL 동기화는 [Khronos EGL_KHR_wait_sync](https://registry.khronos.org/EGL/extensions/KHR/EGL_KHR_wait_sync.txt)를 따른다.


## 카메라 직접 입력과 LiteRT 입력 동기화 (2026-09-27)

Android 카메라·로컬 AI·WebRTC 영상 처리의 후속 변경이다. HTTP·시그널링·모델
가중치·얼굴 보호 정책의 계약 변경은 없다. 아래 네 항목을 제품 경로에 적용했다.

| 항목 | 현재 구현과 지원 조건 |
| --- | --- |
| mask 행렬 계산 | C++/ARM NEON. Kotlin과 누적 순서·영점 경계·출력 일치 유지 |
| GPU 모델 입력 | GPU letterbox texture → compute shader → GL SSBO → LiteRT FP32. 지원·출력 검증 후 선택. 초기 12회 속도 비교는 진단이며 더 이상 영구 선택 거부 조건이 아님 |
| 카메라 입력 | ImageProxy의 Y/UV를 직접 GPU texture에 업로드. GLES3 row length와 NV12/NV21 공유 평면 지원. 검증한 SM-S931N에서 전체 평면 복사·I420 입력 할당 0회 |
| GPU 동기화 | 출력은 공유 EGL fence, 입력은 LiteRT GL/CL interop 내부 동기화. 정상 제품 경로의 입력 glFinish 제거 |

CameraX 평면의 buffer position, crop, 홀수 크기, row/pixel stride, NV12/NV21 순서를
검증한다. 실제 SM-S931N은 UV base view의 마지막 불필요한 바이트를 노출하지 않았다.
전체 UV를 재포장하는 대신 범위 안의 행과 마지막 행 일부를 업로드하고 마지막 2byte만
각 평면의 유효한 view에서 조합한다. 이 처리는 전체 plane 복사가 아니다.
클라이언트 메모리의 GPU 업로드 자체를 없앤 AHardwareBuffer zero-copy 구현은 아니다.
별도의 stride-2 UV 저장소나 미지원 GL 형식은 필요한 평면만 재포장하며, GPU 영상 오류는
현재 프레임의 보호 CPU fallback 또는 송출 실패로 처리한다. 원본을 우회 송출하지 않는다.

카메라 이미지는 GPU 업로드가 픽셀을 소유한 직후 반환한다. 모델 검증·추론·인코딩이
ImageProxy를 빌리지 않는다. CPU fallback도 owned I420을 만든 뒤 이미지를 반환한다.
방송 종료·모드 변경·카메라 변경으로 무효화된 작업은 입력을 닫고 결과를 전달하지 않는다.
출력 texture와 얼굴 crop은 독립된 수명을 가진다.

입력 동기화는 공식 [LiteRT v2.2.0 OpenCL backend](https://github.com/google-ai-edge/LiteRT/blob/v2.2.0/ml_drift_delegate/delegate/gpu_backend_opencl_litert.cc)의
`GlInteropFabricLiteRt::Start`를 사용한다. 런타임이 EGL fence를 만들고, 지원 시
CL queue의 dependency로 바꾸며, 미지원 시 해당 fence를 CPU에서 기다린다.
외부 EGLSyncFence tensor event는 이 backend에서 거부한다. Android bridge는
GL 작업을 flush한 뒤 동기 Run을 호출하고 출력 완료 후 SSBO를 재사용한다.
Adreno에서 지원되지 않는 async Run이나 비공개 CL context에 의존하지 않는다.
`inputInteropSync` 진단 값은 이 런타임 인계 경로가 선택됐다는 뜻이며 모든 기기에서
CPU 대기가 전혀 없다는 뜻은 아니다.

### 동일 입력 단계별 벤치마크

SM-S931N·Android 16, 합성 입력을 번갈아 처리해 비교했다. 구간별 시간은 합산하지 않는다.
GPU 모델 출력 최대 차이는 prediction 0.00055, prototype 0.000009 이하였다.

| 구간 | 이전 → 후속 p50 | 판단 |
| --- | ---: | --- |
| mask, 객체 1개 / 8개 | 1.01 / 4.36 → 0.70 / 1.21ms | 개선, 마스크 동일 |
| 입력·모델 추론, 3개 패턴 | 22.56~25.75 → 18.54~19.14ms | 개선, 같은 FP32 출력 |
| 입력 glFinish → 런타임 interop sync | 20.71 → 19.79ms | 작은 개선, 24회 교대 입력 출력 비교 |
| 잘린 NV21, 1080p 카메라 복사·업로드 제출 | 2.62 → 2.52ms | 작은 개선 |
| 잘린 NV21, 720p 카메라 복사·업로드 제출 | 1.11 → 1.17ms | 단독 속도 개선 없음. 전체 평면 CPU 복사·입력 할당은 제거 |

카메라 구간은 GPU 작업 제출 시간이며 GPU 완료 시간이나 전체 프레임 지연이 아니다.
단일 채널 마스크와 출력 fence의 기존 측정·출력 일치 검증도 유지했다.

### 실제 카메라 비교

같은 앱의 `privacyOptimized=false`는 이전 pooled I420 + Bitmap/float GPU 입력을,
`true`는 새 카메라 직접 입력 + GL SSBO + 런타임 입력 동기화를 사용한다.
양쪽 모두 기존 NEON mask·단일 채널 mask·출력 fence를 사용하므로 이번 비교는
마지막 세 경로의 후속 적용 효과다. 각 해상도에서 준비와 초기 노출을 제외하고
25초씩 foreground CameraX + 제품 AI 처리 + 로컬 renderer를 측정했다.
방송·서버·YouTube·영상 저장은 사용하지 않았다.

| 항목 | 1080p 이전 → 후속 | 720p 이전 → 후속 |
| --- | ---: | ---: |
| 캡처 FPS | 약 30 → 약 30 | 약 30 → 약 30 |
| 처리 FPS | 17.32 → 17.72 | 17.80 → 18.68 |
| 전체 처리 p50 / p95 | 35.83 / 46.88 → 36.60 / 42.25ms | 35.79 / 43.26 → 35.39 / 41.59ms |
| 보호 프레임 처리 p50 | 42.69 → 36.60ms | 37.92 → 35.39ms |
| 보호 render p50 | 1.60 → 1.59ms | 1.65 → 1.55ms |
| YOLO inference p50 | 23.64 → 25.23ms | 24.24 → 23.73ms |
| 별도 I420 camera 복사 p50 | 0.98 → 0ms | 0.58 → 0ms |
| 처리 / 보호 프레임 | 433 / 71 → 443 / 443 | 445 / 175 → 467 / 467 |
| 직접 모델 입력·평면 전체 복사 0·입력 interop sync | 0 → 443프레임 전부 | 0 → 467프레임 전부 |
| GPU 추론·texture 출력 / 오류 | 전체 프레임 / 0 → 0 | 전체 프레임 / 0 → 0 |

후속 경로가 모든 측정 프레임에서 활성화됐고 두 해상도의 보호 프레임 지연은 줄었다.
FPS 개선은 작았으며 얼굴 위치·검출 빈도·온도·GPU 클럭을 통제하지 않은 순차 실행이다.
이 차이를 각 최적화의 인과 효과나 30fps 유지의 증거로 사용하지 않는다.
검출 출력·prototype은 CPU에서 해석하고 mask·얼굴 인식 일부도 CPU를 사용한다.
이번 네 항목 적용이 모든 Android 병목을 제거했다거나 Core ML과 같은 실행 시간을
보장한다는 뜻은 아니다. 실제 서버 송출·YouTube 수신·장시간 발열은 별도 검증 범위다.

재현은 앱과 androidTest APK를 `adb install -r -t`로 설치하고
`am instrument --user 0 -w -r -e privacyOptimized false/true -e class
com.framework.innolive.feature.live.PrivacyActualCameraDeviceTest
com.framework.innolive.test/androidx.test.runner.AndroidJUnitRunner`를 각각 실행한다.
`false/true`는 실행별 한 값을 선택한다. 카메라 검증은 잠금 해제와 foreground 화면이
필요하며 KEEP_SCREEN_ON을 적용한다. 수치 count·timing만 기록한다.
최종 검증: Debug 앱·androidTest 빌드 성공, 단위 테스트 179개 통과,
실기기 회귀 26개 통과, 이전/후속 실제 카메라 비교 각 1개 통과.
추가 회귀: `PrivacyCameraInputDeviceTest`의 잘린 UV·crop·회전·원본 해제 이후 출력,
`PrivacyGpuInputDeviceTest`의 GPU 출력 일치·현재 프레임 fallback·24회 입력 동기화,
`PrivacyGpuEncoderDeviceTest`의 카메라 버퍼 해제 후 shared EGL H.264 인코딩.
