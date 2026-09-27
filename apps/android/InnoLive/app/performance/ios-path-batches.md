# iOS 대조 온디바이스 AI 경로 최적화

## 검증 방식

43개 후보를 10개 수정 → 보호 결과·회귀·성능 검증 순서로 진행한다.
묶음 성능이 나빠지면 해당 묶음의 개별 비교로 원인을 분리한다.
성능이 좋아지면 다음 묶음으로 진행한다. 지원/정확도 검증 실패 경로는
기본 제품 경로에 넣지 않고 시도한 방법과 결과를 남긴다.

대상은 Android 카메라/로컬 AI/WebRTC 미디어 경로다. iOS는 읽기 비교 대상이며
서버 API·시그널링·얼굴 저장 형식·모델 가중치·보호 정책 변경은 없다.

## 묶음 1 — 채택 (2026-09-27)

후보 번호: 01–06, 13, 14, 26, 32.

| 후보 | 변경 |
|---|---|
| 01 | GPU 출력은 재사용 direct buffer에 복사. 매 프레임 4.55MB Java float 배열과 prototype JNI 재전달 제거 |
| 02 | native decode/NMS, stable tie 순서 유지, 최종 후보만 coefficients 전달 |
| 03 | direct 출력 검증과 native 소비 연결. prediction/prototype 유한값 검사 유지 |
| 04 | native instance mask scratch 두 세트를 보존, 객체별 Java ByteArray 전달 제거 |
| 05 | 동일 temporal 정책의 bilinear/IoU 매칭을 C++로 이동 |
| 06 | ROI union과 NEON max, 0개 객체 경로, union 결과의 nonzero count 재사용 |
| 13 | 얼굴 query 한 번 정규화, 등록 embedding 임시 정규화 배열과 전체 정렬 제거, top2 유지 |
| 14 | library revision별 snapshot 캐시, 삭제 revision에 예외 즉시 취소 |
| 26 | shader uniform location·sampler 이름·texture parameter 캐시 |
| 32 | native union count를 Debug 분석과 GPU 렌더 분기에 재사용 |

GPU→CPU 출력 lock과 direct buffer로의 native memcpy는 남는다. 최종 Java mask는
독립된 소유권을 유지한다. 정상 native GPU 경로에서 출력 전체 Java 배열이 없어졌으며
CPU/legacy 대체 경로에는 해당 배열과 direct buffer 채우기가 남는다.

### 정확도/회귀

- 단위 테스트 180개 통과.
- native 후처리·얼굴 async 정책·GPU 입력·GPU 보호 렌더·하드웨어 인코더 실기기
  회귀 24개 통과. 추가 direct 출력 parity·revision 무효화·카메라 테스트 3개 통과.
- native 후처리의 이동/시간 간격/역전/reset/예외/빈 장면 마스크는 reference와
  byte 단위로 동일. NaN/Inf, 잘못된 bbox, 100개 초과, 다음 프레임에 의한 이전 mask
  변조를 검증했다.
- 같은 GPU 입력에서 기존 float 배열 출력과 direct buffer 출력 일치 확인.
- 고정 입력 묶음 벤치마크의 원본/개선 출력 I420 pixels 동일.

### 묶음 성능

동일 APK, 동일 입력, 번갈아 실행. 고정 입력은 실제 YOLO + 전체 보호 GPU 렌더 +
I420 소비를 사용한다. synthetic/full-mask 부하이며 실제 방송 FPS로 환산하지 않는다.

| 부하 | 기준 중앙값 | 개선 중앙값 | 기준 p95 | 개선 p95 |
|---|---:|---:|---:|---:|
| 후처리 객체 0개 | 1.45ms | 0.60ms | — | — |
| 후처리 객체 1개 | 1.72ms | 0.60ms | — | — |
| 후처리 객체 8개 | 3.39ms | 0.64ms | — | — |
| 고정 1080p 전체 보호, 40표본 | 40.26ms | 30.10ms | 53.97ms | 31.64ms |
| 고정 720p 전체 보호, 40표본 | 32.68ms | 23.18ms | 34.28ms | 24.54ms |

실카메라 25초 renderer 비교에서는 1080p 20.44→20.72fps, 720p 22.08→21.60fps.
보호 프레임이 각각 5→273, 123→187로 달라 실제 장면 비교의 개선/저하를
이 수치만으로 판정할 수 없다. 그래서 같은 입력과 보호 부하의 추가 묶음 비교를
실행했고 중앙값·p95 개선 및 출력 일치로 채택했다. 카메라 실측은 30fps 미달이다.

명령: `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest --offline`
focused class와 `privacyBatchOne=false/true`, `privacyOptimized=true` runner arguments로
현재 APK에서 기준 경로와 개선 경로를 선택한다.

## 묶음 2 — 채택 (2026-09-27)

후보 번호: 08, 10, 11, 15, 16, 21, 27, 28, 29, 31, 35. 처음 계획의
10개 외에 카메라 컴포저블 재바인딩(35)도 같은 묶음에서 바로 정리했다.

| 후보 | 변경·한계 |
|---|---|
| 08 | 현재 YOLO 검출 후 현재 얼굴 track에서 비동기 재인식 crop을 고른다. 결과 적용 시 track/위치·revision 검증 유지 |
| 10 | 112 crop/Canvas/Matrix/Paint·float 입력·CPU tensor 저장소를 재사용. alpha가 있는 비트맵은 기존 픽셀 API 유지 |
| 11 | YuNet resize Bitmap·Canvas, BGR direct tensor·출력 배열을 크기별 재사용. native RGBA→BGR NEON; alpha/비표준 색 공간은 정확도 우선 대체 경로 |
| 15 | 얼굴 worker를 `THREAD_PRIORITY_MORE_FAVORABLE`로 조정. 별도 우선순위 격리 비교에서 영상 p95와 얼굴 p95를 함께 측정 |
| 16 | native GPU 검증에 성공하면 기존 LiteRT GPU를 추가 생성하지 않고 CPU reference session을 닫는다. CPU 입력/float scratch는 대체 사용 때만 생성하고 오류 시 현재 프레임으로 CPU session을 다시 연다 |
| 21 | 영상 frame의 준비·추론·마스크·렌더를 GL worker 하나의 호출에서 실행해 queue 왕복 제거; 자원 해제도 GL owner에서 수행 |
| 27 | fence 지원 문자열은 EGL display별 thread-local 캐시. 소비 context마다 wait 자체는 보존 |
| 28 | 방송/카메라 이미지 수용 전에 중립 프레임으로 모델 검증·shader 보호 렌더를 비동기 준비. 준비 중 카메라 이미지는 닫아 실패 시 원본 누출을 막는다 |
| 29 | 제품 시작 경로의 12회 양방향 GPU 벤치마크를 제거하고 3개 패턴 정확도 검증은 유지. benchmark 기기 테스트의 진단 경로는 남긴다 |
| 31 | 얼굴 모델은 등록 화면/방송의 lease가 끝난 뒤 30초 유예 후 해제. 재진입 시에는 캐시 모델 재사용; 실패 상태는 자동 반복 재시도하지 않음 |
| 35 | 연결 상태 변경에 따른 CameraPreview 컴포저블 위치를 고정. main/PiP modifier와 z-order만 변경해 불필요한 카메라 재바인딩 방지 |

### 정확도·회귀

- GPU direct 경로 실패 후 같은 프레임의 CPU 대체 및 검증된 CPU session 재생성을 실기기 확인.
- 얼굴/번호판 추출의 native BGR padding, 112 정규화, 크기 변경, alpha 이미지, 새 crop
  track·소실 및 GPU·인코더 회귀 테스트 통과. 사전 준비 동안 이미지 닫기·준비 이후
  보호 프레임 전달·정지 후 처리 없음 검사 통과.
- 고정 YOLO+전체 마스크+I420 출력 A/B 1080p·720p 모두 byte 단위 동일.
- 단위 테스트 전체 및 사전 준비·GPU·인코더 등 관련 실기기 테스트 통과.

### 동일 입력·묶음 성능

| 조건 | 기준 p50 | 개선 p50 | 기준 p95 | 개선 p95 |
|---|---:|---:|---:|---:|
| 첫 1080p 모델 실행/검증 | 5344ms | 2042ms | — | — |
| 첫 720p 모델 실행/검증 | 5119ms | 2029ms | — | — |
| 1080p 전체 보호, 40표본 | 28.85ms | 28.18ms | 32.53ms | 30.88ms |
| 720p 전체 보호, 40표본 | 22.73ms | 22.23ms | 23.33ms | 23.26ms |

첫 실행 시간은 기존 경로/새 경로를 같은 프로세스에서 순서대로 실행한 값이며
냉시작 절대값은 아니다. 사전 준비(28)는 실제 첫 프레임 앞에서 이 일을 실행하므로
컴파일 시간을 방송 시작 전체 지연에서 없애는 것으로 해석해서는 안 된다.

합성 얼굴 GPU 인식을 250ms 간격으로 중첩하면 720p 영상 p95가 묶음
기준 161.74ms, 개선 163.01ms로 유사하게 나빴다. 스레드 우선순위 하나만
분리해 나머지 개선을 동일하게 유지한 A/B에서는 영상 p95 164.19→81.28ms,
얼굴 p95 138.84→138.27ms, 얼굴 p50 66.58→63.10ms였다. 얼굴 GPU
경합 자체가 영상에 큰 지연을 주므로 이 묶음을 30fps 달성으로 간주하지 않는다.

사전 준비 직전 실카메라 25초 수치: 1080p 26.48fps/662프레임,
720p 19.60fps/490프레임. 보호 프레임은 각각 173/183이고 오류 0.
장면·기기 온도가 통제된 동일 입력이 아니므로 A/B의 개선 인과 증거로 사용하지 않는다.

사전 준비 적용 후 실제 카메라 25초 재측정도 오류 0: 1080p 621/750=24.84fps,
720p 420/750=16.80fps. 두 번째 장면은 얼굴/보호 프레임이 0이라 보호 부하 비교가
불가능하다. capture는 양쪽 30fps, 추론 중앙값은 각각 17.07ms/34.47ms.
사전 준비·보호 게이트/얼굴 입력/GPU encoder 관련 실기기 10개 테스트 통과.
따라서 고정 입력 개선은 채택하지만 실카메라 30fps 도달 주장은 보류한다.

## 묶음 3 — 10개 후보 검증 (2026-09-27)

후보 번호: 19, 20, 22, 23, 24, 25, 30, 33, 36, 37.

| 후보 | 변경·판정 |
|---|---|
| 19 | 결합된 Preview/ImageAnalysis SessionConfig가 30–30fps를 지원할 때만 고정. 지원하지 않거나 바인딩이 실패하면 기존 UseCaseGroup 사용. SM-S931N 1080p/720p 모두 지원 확인. 이 기기의 캡처는 변경 전에도 약 30fps였음 |
| 20 | 서버 AI/로컬 원본의 전체 crop은 추가 cropAndScale 없이 pool buffer를 retain해 전달. 늦게 반환되는 WebRTC 프레임의 픽셀 보존 검증 |
| 22 | 보호 마스크의 마지막 feather와 core max를 같은 160×160 pass에서 합쳐 draw pass 하나 제거. 출력 해상도에서 max를 합치는 시도는 희소 보호 가장자리 차이가 최대 7단계라 폐기 |
| 23 | GLES3의 mask 중간 FBO를 RGBA8 대신 R8로 변경. GLES2는 기존 RGBA fallback |
| 24 | Gaussian/feather에서 인접 두 샘플을 bilinear 한 샘플로 합친 고정 kernel 사용. 최대 픽셀 차이 1단계 |
| 25 | ROI scissor는 희소 마스크 픽셀은 같지만 단독 A/B에서 1080p 희소 13.04→13.14ms, 가장자리 12.18→13.11ms로 느려져 제품 기본값에서 제외. 실험 스위치만 남김 |
| 30 | image GPU의 일시 오류는 보호 CPU 출력 유지 후 5/10/20/40초 간격으로 재준비. 지원 불가·인수 오류는 반복 재시도하지 않음 |
| 33 | busy 상태에서 CameraX frame을 버리는 대신 최신 1장만 보관, 더 오래된 대기 이미지는 즉시 close. 100ms 이상 묵은 대기 프레임과 mode 전환/종료/얼굴 예외 reset 이미지도 폐기 |
| 36 | CPU fallback의 I420 출력을 consumer 수명에 묶인 단일 idle pool로 재사용; 느린 소비자가 보유한 프레임은 재사용하지 않음 |
| 37 | CPU Gaussian의 1.5·6.0 kernel과 수평 중간 배열을 재사용; 소유권이 필요한 출력 배열은 계속 새로 만듦 |

### 동일 입력·보호 결과

SM-S931N에서 기존/새 GPU 렌더를 같은 1080p/720p I420와 전체·희소·가장자리·빈 마스크로 번갈아 35회 실행했다. 기본 경로는 ROI를 끈 상태다. 전체/희소 마스크의 출력 차이는 최대 1단계, 가장자리/빈 마스크는 byte 동일. 실제 GPU 렌더·I420 소비를 포함한 시간이다.

| 부하 | 기준 p50 | 개선 p50 | 기준 p95 | 개선 p95 |
|---|---:|---:|---:|---:|
| 1080p 전체 보호 | 17.52ms | 16.82ms | 19.04ms | 18.76ms |
| 1080p 희소 보호 | 14.29ms | 14.00ms | 18.20ms | 17.69ms |
| 720p 전체 보호 | 8.07ms | 7.83ms | 9.36ms | 9.03ms |
| 720p 희소 보호 | 6.62ms | 6.30ms | 8.78ms | 7.31ms |

CPU 대체 경로의 동일 320×180 이미지에서는 Gaussian 출력 배열과 I420 planes가 각각 byte 동일했다. Gaussian p50 64.44→63.62ms, p95 72.09→69.07ms. I420 변환 p50 0.059→0.059ms, p95 0.084→0.063ms. 출력 pool은 할당 감소와 보유 프레임 불변성을 위한 변경이며, 이를 단독 FPS 향상으로 해석하지 않는다.

### 실제 카메라

전면 카메라, 고정 30fps 지원 세션, 보호 GPU + WebRTC texture consumer, 각 25초. 먼저 폐기 방식에서 1080p 688/750=27.52fps, 720p 425/751=17.00fps였다. 최신 대기 방식에서는 1080p 747/748=29.88fps, 720p 676/675=27.04fps였다. 720p 최신 대기 방식은 CameraX 분석 수신도 27fps로 내려갔으므로 실제 30fps 달성으로 보지 않는다. 얼굴 보호 프레임 수가 기준 101/103에서 새 경로 209/657로 달라져 장면·GPU 경합이 같은 A/B가 아니며, 기기 온도를 통제한 장시간 방송 성능은 별도 확인이 필요하다.

분석 도착→전달 지연 p95는 최신 대기에서 1080p 65.9ms, 720p 79.9ms였다. 같은 계측으로 다시 잰 폐기 방식은 각각 42.4ms, 46.3ms이며 처리 FPS는 26.84, 15.72였다. 약 24/34ms 지연을 대가로 끊김을 줄이는 선택이다. 이 A/B는 실카메라 장면·얼굴 빈도·열 조건이 완전히 같지 않다. 지연 급증을 막기 위해 대기 이미지가 100ms를 넘으면 전달하지 않는다.

## 묶음 4 — 코덱 채택, 조건부 경로 실험 (2026-09-27)

후보 번호: 07, 17, 18, 34, 38, 39, 40, 41, 42, 43. 이 묶음에는 성능 또는
정확도 검증에 실패한 실험도 포함한다. 검토한 후보 수를 구현 완료 수로 세지 않는다.

| 후보 | 변경·판정 |
|---|---|
| 07 | LiteRT 출력 지원을 조회하고 GL SSBO 출력/CPU map 소비를 별도 진단 경로로 구현. GPU mask/NMS 전체 이식은 아직 하지 않음. 재측정에서 p50/p95가 악화되어 GL 출력은 기본으로 선택하지 않음 |
| 17 | 실제 CameraX Image에 HardwareBuffer가 존재함을 확인: 1080p, format=35(YUV), usage=131075. EGL import·카메라 buffer 재사용 fence·입력 parity 검증과 구현은 남음. 현재 borrowed planes upload 유지 |
| 18 | YUV→회전→모델 letterbox 직접 통합을 시험했으나 회전 입력에서 RGB 차이 최대 4단계, 0°에서도 입력 준비 p50 7.498→7.570ms. 실험 변경을 되돌림 |
| 34 | SurfaceView PERFORMANCE 실카메라 1080p 28.76fps, 720p 25.52fps. 앞선 COMPATIBLE 29.88/27.04보다 향상되지 않았고 장면/열 조건도 일치하지 않음. PiP clip 제약을 포함해 COMPATIBLE 유지 |
| 38 | R8 Release 빌드와 JNI keep rule 추가. 실기기에서 WorkManager Room 생성 코드 및 WebRTC org.jni_zero.JniInit 제거에 따른 시작 실패를 재현하여 생성 클래스/JNI SDK를 보존. 별도 minified instrumentation이 호출하는 API는 테스트 옵션에서만 보존하되 본문 최적화를 허용한다. 따라서 이 테스트 variant와 일반 Release는 동일한 keep 구성이 아님. minified 테스트 variant의 GPU 입력 4개·인코더 5개·local RTP 2개 및 실제 카메라 1개 테스트를 나누어 실행해 통과. 이후 일반 Release에서 ML Kit registrar 생성자가 제거되어 얼굴 검출 생성에 실패하는 것도 찾아 세 생성자를 보존했다. 해당 minified 회귀 테스트는 수정 전 실패·수정 후 통과했고, 테스트 규칙이 없는 일반 Release 재설치·재시작 로그에서 registrar 오류가 사라졌다. 일반 Release의 얼굴 검출 처리 및 앱 hot path Baseline Profile은 남음 |
| 39 | 첫 두 Conv의 가중치만 FP16, 나머지 계산·출력 FP32인 혼합 저장 실험. 실제 FP16 tensor 선택을 검사하도록 exporter 보강. ONNX 대비 prediction 오차 0.12347, prototype 0.00406으로 host 기준 실패. 원래 FP32 모델 유지 |
| 40 | 기존 같은 기기 QNN/HTP 실험은 NPU p50 약 24ms, GPU FP32 약 17ms이며 prediction 오차 3.51–19.22로 제품 parity 실패. NPU 기본 선택 제외 유지. 추가 연산별 정밀도 튜닝은 수행하지 않았음 |
| 41 | 실기기의 Default factory VP8은 소프트웨어, H264 Baseline은 하드웨어임을 확인. 서버가 지원하고 기기가 하드웨어 제공하는 정확한 42e01f/mode=1만 먼저 협상. 미지원 기기는 기존 순서. 실제 로컬 RTP 송수신에서 c2.qti.avc.encoder, video/H264, 수신 디코드 및 CPU texture→I420 변환 0회 확인 |
| 42 | 같은 가중치 NHWC 입력 export와 native GPU 입력 배치 지원 추가. host parity 및 24 changing input GPU parity 통과. 실제 GPU A/B는 약간 느려 기본 NCHW 유지 |
| 43 | KP-RPE lookup을 rank≤4 constant gather로 변환, 원래 PyTorch/flip fusion과 host cosine≥0.9999997 확인. 실기기 GPU embedding parity 통과했지만 p50/p95 악화로 기존 one-hot 모델 유지 |

### 동일 입력 개별 비교

SM-S931N, 동일 프로세스에서 기준/후보를 번갈아 실행. 모델 준비/컴파일은 시간에서
제외했다. YOLO는 GPU 입력 준비+실행+출력 CPU 읽기, 얼굴은 입력 write 후 실행+출력
읽기다. 이 수치를 방송 FPS로 환산하지 않는다.

| 실험 | 표본 | 기준 p50 | 후보 p50 | 기준 p95 | 후보 p95 |
|---|---:|---:|---:|---:|---:|
| GL 출력 첫 측정 | 24 | 20.34ms | 19.59ms | 25.49ms | 24.91ms |
| GL 출력 재측정 | 24 | 13.68ms | 14.07ms | 15.43ms | 16.19ms |
| YOLO NHWC | 24 | 14.01ms | 14.30ms | 15.78ms | 16.45ms |
| 얼굴 gather | 14 | 62.09ms | 82.71ms | 65.22ms | 101.05ms |

GL 출력은 첫 측정의 개선이 반복되지 않았다. 기준값 자체도 달라져 GPU 클럭/열 조건의
영향을 배제할 수 없으므로 기본 적용하지 않는다. NHWC/gather 모델은 진단용 임시
파일이며 APK asset과 제품 SHA는 변경하지 않았다. exporter 옵션 기본값도 기존 방식이다.

### 검증 상태

- 단위 테스트 전체와 코덱 선택 경계 테스트 통과.
- Android 생성 offer에서 H264 Baseline 첫 payload 확인. 서버의 client-preferred H264
  answer/connected peer 테스트 두 개 통과(서버 코드 변경 없음).
- 실제 local PeerConnection 두 개의 협상/ICE/RTP 송수신과 하드웨어 decode 3프레임 이상,
  송신 packet 80개 및 texture CPU readback 0회 확인. 네트워크 방송/YouTube 수신은 아님.
- 최종 수동 진단: GL 출력/NHWC/얼굴 gather/실카메라 4개 테스트 통과. USB 복귀 후 로그를
  회수했다. 1080p와 720p 모두 25초 동안 capture 약 30.00fps, 처리 30.04fps였다.
  측정 경계에서 프레임 1개가 추가 집계될 수 있으므로 30fps 초과 성능으로 해석하지 않는다.
  보호 프레임은 각각 562/327, p95 총 처리 26.91/24.78ms, 분석 도착→전달 p95
  28.08/26.66ms, 오류 0. 등록자 예외 0건이며 등록자 재인식 부하나 장시간 발열 검증은 아님.
  장면/열 조건이 같지 않아 이전 측정과의 차이를 새 변경의 인과 효과로 단정하지 않는다.
  새 카메라 테스트는 USB 단절에 대비해 수치를 runner 출력에도 기록한다.
- 최종 minified 테스트 variant에서 GPU 입력 4개·GPU 인코더 5개·local RTP 2개 테스트 통과.
  직접 하드웨어 인코더에는 1920×1080 보호 texture 입력도 포함했다. 위 11개와 카메라
  1개는 여러 실행에 걸친 결과이며 한 번의 전체 suite 실행 결과는 아니다.
- 같은 variant의 실제 카메라 25초 재측정: 1080p·720p 각각 750장/약 30fps, 오류 0.
  1080p 보호 대상은 0건이므로 얼굴 보호 부하 검증으로 세지 않는다. 720p에서는
  168프레임을 보호했다. p95 총 처리 24.82/21.85ms, 분석 도착→전달 26.39/23.59ms.
  등록자 재인식·장시간 방송 측정은 아니다. 테스트 variant는 별도 API keep와 성능 계측
  옵션을 포함하므로 일반 Release와 같은 APK라고 주장하지 않는다.

### 코덱 개별 비교

실제 두 PeerConnection의 local ICE/RTP·수신 decode를 사용했다. 1080p 비교에는
YOLO GPU 실행과 고정 전체 보호 mask 렌더를 함께 실행했다. 실제 검출 후처리·얼굴
재인식·네트워크 방송은 포함하지 않았다. 60프레임, 목표 간격 33.33ms이고 모델
컴파일은 타이머 밖, 첫 GPU 실행은 포함했다. 장시간 부하 측정은 아니다.

| 입력 | VP8 인코딩 평균 | H264 인코딩 평균 | VP8/H264 송신 FPS | VP8/H264 CPU texture readback |
|---|---:|---:|---:|---:|
| 320px 고정 보호 texture | 3.44ms | 8.91ms | — | 59 / 0 |
| 1080p YOLO + 전체 보호 | 17.14ms | 15.05ms | 29.50 / 29.99 | 60 / 0 |

작은 입력에서는 H264 평균이 더 느렸다. 1080p 부하에서는 평균과 송신 FPS가 개선됐고
texture CPU 변환이 제거됐다. 모든 해상도에서 인코딩 자체가 더 빠르다고 해석하지 않는다.
최종 APK의 1080p 재실행에서도 로컬 RTP 수신과 7개 인코더 테스트가 통과했다.
VP8/H264 인코딩 평균은 16.69/15.47ms, 송신 FPS는 각각 29.50/29.50,
texture CPU readback은 60/0이었다. 따라서 송신 FPS 향상은 이번 재측정에서
재현되지 않았지만 CPU 변환 제거와 하드웨어 인코더 사용은 재현됐다.

## 묶음 5 — 얼굴 입력·ROI 실험 (2026-09-27)

후보 09와 12 및 얼굴 GPU 우선순위 격리(15의 추가 실험)를 각각 비교했다. 아직
10개 묶음이 끝난 것은 아니며, 아래 결과를 다음 묶음의 완료로 세지 않는다.

- **09 GPU 얼굴 이미지 입력:** 기존 LiteRT 2.2 얼굴 모델은 1,690 연산 중
  1,585개를 GPU, 105개를 CPU에서 실행한다. 원본 얼굴 모델에 GL 입력 SSBO를
  연결하면 C API가 `run status=3`을 반환하고 로그에 CPU partition의
  `args_0` 입력 lock 실패가 나타났다. 랜드마크 `GatherND`를 같은 값을 내는
  `Gather` 또는 5개 `Slice+Concat`으로 바꾼 임시 모델은 host 출력 4개가
  원본과 완전히 일치했지만, SM-S931N의 GPU delegate 컴파일에서 각각
  `INVALID_ARGUMENT`와 `compile status=3`으로 거부됐다. `GatherND`를
  `Gather`로 바꾸고 100개 FP16 상수 변환까지 접은 후보도 host parity는
  통과했으나 기기 컴파일은 실패했다. 따라서 GL 얼굴 모델과 가중치 변경은
  제품 코드·APK asset에 적용하지 않았다. GPU로 모델 입력을 직접 연결하는
  경로가 완성됐다고 표시하지 않는다.
- **15 GPU 우선순위 추가:** 얼굴 LiteRT GPU 옵션 `LOW`는 컴파일/임베딩
  parity를 통과했지만 기기 검증의 CPU 대비 속도 조건을 통과하지 못했다.
  제품 기본 우선순위는 유지한다.
- **12 비동기 얼굴 ROI 읽기:** GL PBO·fence와 재사용 공유 context로 원본
  해상도의 crop을 얼굴 worker가 읽는 후보를 만들었다. 원본과 픽셀 일치,
  다음 영상 프레임이 덮인 뒤의 snapshot, producer graph 종료 후 읽기,
  보류한 샘플의 해제/영상 context 복귀 등 기기 테스트 3개가 통과했다.
  24개 동일 입력 A/B의 영상 worker 제출 p50은 전체 1080p ROI
  7.45→3.07ms, 500×650 ROI 4.20→3.99ms, 96×96 ROI 2.71→0.80ms.
  얼굴 worker까지 포함한 전체 완료 p50은 각각 11.72/6.51/2.82ms로,
  기존 동기 경로의 7.45/4.20/2.71ms보다 길거나 비슷했다. 진단 스위치
  `PrivacyFrameProcessor.asyncFaceReadback` 기본값은 `false`다.
- **12 영상·얼굴 동시 경합:** 720p YOLO GPU와 얼굴 GPU를 함께 실행한
  ABBA 8초×4단계에서 영상 p50 25.22→23.01ms, p95
  132.69→126.83ms였고, 얼굴 p50 70.54→72.04ms, p95
  122.45→130.10ms였다. 얼굴 임베딩 parity와 테스트 실행은 통과했다.
  영상 p95 이득이 작은 반면 얼굴 지연이 증가했고, 각 단계의 처리 프레임 수가
  194/202/165/146으로 달라 기기 열/클럭 영향을 배제할 수 없다. 기본
  제품 경로는 기존 동기 crop으로 둔다. 실얼굴이 등록된 장시간 방송 검증은 남는다.

  최종 APK 재측정(같은 ABBA 8초×4단계)에서는 영상 p50
  24.88→25.76ms, p95 108.09→111.40ms, 얼굴 p50
  91.01→98.17ms, p95 106.42→110.44ms로 모두 악화됐다.
  단계별 처리 프레임도 194/189/168/166으로 변화했다. 이 결과까지
  고려해 비동기 경로를 기본값으로 채택하지 않는다.

### 검증 제한

실패한 얼굴 GPU 모델 실험을 제품 코드에서 제거한 뒤 Debug 앱과 androidTest APK를
다시 빌드했고 전체 Debug 단위 테스트와 `assembleRelease`가 통과했다. 최종 APK로 ROI 픽셀·수명
회귀 테스트 3개와 GPU/얼굴 경합 테스트 1개가 통과했다. 실얼굴 등록자의 장시간
방송과 발열은 이 진단으로 입증하지 못한다.

## 남은 작업

09 얼굴 GPU 이미지 입력은 SDK 입력 호환성과 GPU delegate 연산 지원을 해결한 뒤 재검증해야 한다. 12 얼굴 ROI readback은 기본값을 유지한 채 실얼굴·장시간 경합 A/B가 남는다. 07의 GPU 후처리,
17의 HardwareBuffer 직접 입력, 38의 일반 Release 얼굴 검출 처리 검증/Baseline Profile도 완료 전이다.
40의 추가 QNN 정밀도 튜닝은 기존 실패 경로와 별도 실험이 필요하다. 모든 43개 경로가
구현/채택됐다고 표시하지 않으며, 실방송 장시간 발열과 지속 30fps도 아직 입증되지 않았다.
