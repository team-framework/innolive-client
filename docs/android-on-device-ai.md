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

입력은 RGB 640×640 NCHW float32, 1/255 정규화, 비율 유지 letterbox와 값 114 padding이다. 출력은 `[1,38,8400]`, `[1,32,160,160]`; 얼굴 0, 번호판 1이다. 앱이 confidence 0.25, 클래스별 IoU 0.45 NMS, 2px mask 확장, 빈 mask의 bbox 대체를 수행한다. Android도 픽셀화(24px) → Gaussian 블러(24px) → 마스크 합성을 수행한다. 마스크는 2px 중심을 완전히 불투명하게 유지하고, 4px 확장 후 Gaussian(1.5px)을 적용한 바깥 영역과 최댓값으로 합성한다. Android 12(API 31) 이상에서는 재사용하는 RenderEffect·HardwareRenderer로 GPU 블러를 수행한다. Android 11 또는 GPU 오류 시에는 1/4 크기의 중간 이미지에서 sigma 6의 분리 Gaussian convolution을 수행한다. 이 축소는 필터에만 적용하며 검출 마스크나 송출 크기는 줄이지 않는다. Core Image와 픽셀 단위로 동일한 결과를 보장하지는 않는다. 빈 마스크도 독립된 출력 Bitmap을 반환하며, GPU 이미지·HardwareBuffer를 닫기 전에 CPU 복사본을 확보한다.

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
  실제 SM-S931N의 GPU 선택·속도는 이번 변경에서 확인하지 못했다.
- CameraX는 보호 프레임을 한 번에 하나만 별도 작업자에 넘기고 ImageProxy를
  즉시 닫는다. 작업 중 도착한 프레임은 복사 전에 버린다. 송출 모드·카메라
  변경과 종료 시 이전 세대의 결과를 송출하지 않는다. Debug 로그에 5초마다
  보호 프레임 수신·바쁨으로 폐기·송출 건수를 기록한다. WebRTC의 영상 sender
  통계에서도 인코딩·송신 프레임, 패킷, 바이트의 누적값을 5초마다 기록해
  분석 처리량과 인코더·네트워크 구간을 구분할 수 있게 했다.
- iOS `PrivacyMaskStabilizer`의 객체별 IoU 0.30 매칭, 0.12초 경계 감쇠,
  0.20초 이상 간격 초기화, 사라진 객체의 즉시 제거를 Android에 적용했다.
  새 보호 영역은 즉시 적용하며 얼굴 블러 예외 정책에는 영향을 주지 않는다.

Android는 여전히 CameraX YUV→Bitmap→I420 변환과 마스크 CPU 합성을 거친다.
iOS의 Core Image→CVPixelBuffer 경로와 같은 GPU 종단 처리라고 해석하면 안 된다.
또 iOS에는 1080p/720p의 30/24fps capture preset 및 WebRTC 출력 형식 설정이
있지만 Android에는 해상도 선택만 있다. 실제 방송 FPS·인코더·YouTube 수신,
GPU와 얼굴 인식의 동시 부하 및 발열은 실기기에서 확인해야 한다.

검증: Android 단위 테스트 179개 통과. Pixel_10 에뮬레이터에서 LiteRT CPU/ONNX 출력 비교, GPU 미지원 시 제품 ONNX fallback, 프레임 회전·생명주기·반복 처리, 보호 작업 중 CameraX 입력 즉시 해제·바쁜 프레임 폐기 테스트를 통과했다. GPU 실기기 실행 테스트는 에뮬레이터에서 건너뛰며 SM-S931N 재연결 후 실행해야 한다.
