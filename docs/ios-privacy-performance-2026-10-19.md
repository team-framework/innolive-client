# iOS 온디바이스 처리 성능 조사

## 범위와 비교 조건

- 대상: iOS 카메라 → 온디바이스 AI → 직접 송출용 영상. Android와 서버 코드는 비교 대상으로 읽었다.
- API·WebRTC signaling 계약 변경 없음. 모델 입력 640×640, FHD 출력, 탐지 임계값, 마스크 보호·시간 안정화 규칙을 유지한다.
- 사용자 확인 후 USB로 연결된 iPhone 16 Pro(iPhone17,1, iOS 26.6.2/23G90)를 주 검증 기기로 정했다. 앞서 Wi-Fi로 연결한 일반 iPhone 16(iPhone17,3, iOS 27.0.1)의 측정은 별도 참고값이다.
- 성능 빌드는 Debug 계측을 유지하면서 Swift `-O`, `wholemodule`을 적용한다. 기본 Debug `-Onone` 수치를 배포 성능과 혼용하지 않는다.
- 고정 얼굴 시험은 공개 astronaut 사진을 FHD 화면에 1개 또는 5개 배치한다. 서로 다른 5명의 인식 정확도 시험이 아니다.
- 연속 시험은 90초·30fps 입력, 실제 `PrivacyUplinkProcessor`, `DirectMediaFanout`, H.264 High 6Mbps 인코더 2개를 사용한다. 카메라 시험은 별도로 수행한다. 이 로컬 시험에는 RTMP 네트워크·플랫폼 재생 지연을 포함하지 않는다. 이후 실제 동시송출 결과는 아래에 별도로 기록했다.

## 확인한 저장소와 서버

2026-10-19 KST에 로컬 서버·AI 저장소를 `git pull --ff-only`로 갱신했다. 서버 저장소의 기존 미추적 인계 문서는 보존했다. iOS 작업 worktree는 client `67099e84`로 fast-forward했다. Android 처리 코드와 기존 iOS 처리 코드는 이 갱신에서 달라지지 않았다.

| 대상 | 확인한 상태 |
| --- | --- |
| innolive-server | `8f9ede337b4723435d3955519279463dcf3ff29b` |
| 배포 서버 컨테이너 | 위 서버 SHA를 태그로 사용 |
| innolive-ai | `bf2e307c87e96c9538066725ffc092146f5911f2` |
| SSH 서버 AI checkout | 위 AI SHA와 일치, 작업 트리 변경 없음 |
| 서버 상태 | API health ready, AI runtime 4개 READY |
| AI GPU | RTX 5070 Ti 2개. 확인 시 사용률 0%, GPU 메모리 각각 2748/2225MiB |

AI worker 0·1의 시작 시각은 2026-10-19 14:45 KST이며 확인한 Python source 수정 시각보다 늦다. 서버 `models/best.pt`의 SHA-256은 `1566707c2c1c43f09a9a8929835ab16a16f9cfdc9f36b6d4d13d35caa5777f2e`다. 모바일 모델의 원본 checkpoint `307e9b5895654d25d264903451abc4acad9ee30f5e2f2bf64af164a9f46bc115`와 다르므로 서버 추론 시간을 모바일과 동일 모델 비교로 제시하지 않는다. Android LiteRT manifest와 iOS 모델 metadata의 모바일 checkpoint는 일치한다.

최신 서버 `internal/server/server.go:directIngestURL`은 온디바이스 모드의 플랫폼 ingest 주소를 소유자에게 반환한다. iOS가 플랫폼에 직접 송출하므로 서버 AI worker의 GMC·JPEG·블러 비용은 이 경로의 기기 내부 처리 시간에 포함되지 않는다. 주소와 스트림 키는 조사 로그에 출력하지 않았다.

## 느려진 구간

### Swift Debug 후처리

프로토타입 텐서 32×160×160, 총 819,200개 값에 대한 `allSatisfy(\.isFinite)`가 기본 Debug 빌드에서 원소마다 key-path 호출을 수행했다. iPhone 16 Pro의 동일 얼굴 없는 FHD 입력 20개 표본에서 마스크 단계 중앙값은 68.895ms였다. 모델 추론은 2.844ms였다.

유효성 검사를 포인터 순회로 바꾼 뒤 같은 기본 Debug 조건에서 마스크 단계는 3.098ms, 전체는 79.356 → 12.671ms로 줄었다. NaN·±Inf 검사를 생략하지 않았고 첫 원소·중간·마지막 위치를 시험했다. 이 결과를 얼굴이 있는 실시간 송출 FPS로 환산하지 않는다.

### 출력 영상 복사와 블러

기존 송출 코드는 Core Image 결과를 CGImage로 만든 뒤 매 프레임 새 CVPixelBuffer를 할당해 다시 렌더링했다. 수정 코드는 Core Image 결과를 재사용 가능한 출력 CVPixelBuffer에 직접 렌더링한다. 출력 pool은 최대 6개이며 인코더가 보유한 버퍼를 재사용하지 않는다. 회전 후 원래 센서 크기와 timestamp를 유지한다.

블러용 중간 CVPixelBuffer도 크기가 바뀔 때만 할당한다. 버퍼 할당 실패는 프레임 실패로 처리한다. 이때 원본 프레임을 송출하지 않는다.

Android는 GPU texture 경로와 native C++ 후처리를 사용한다. 네이티브 유효성 검사는 연속 메모리를 순회하며, 마스크 곱셈·합성은 bbox 구간과 NEON을 활용한다. iOS는 vDSP 행렬 연산을 사용한다. Android `PrivacyBroadcastBlur`는 1/4 해상도에서 블러를 수행하고 FHD 기준 최종 sigma 72를 유지한다. iOS도 중간 블러 해상도 축소를 검증한다.

### 발열과 연속 처리

짧은 반복과 90초 부하의 결과가 다르다. 일반 iPhone 16의 초기 짧은 최적화 빌드에서 얼굴 1개 direct 처리 중앙값은 8.001ms, 얼굴 5개는 11.769ms였다. 그러나 90초씩 연속 실행하면 아래처럼 발열과 프레임 누락이 증가했다.

| 초기 직접 출력 구현 | 처리 프레임 / 입력 | FPS | 처리 중앙값 | p95 | 발열 전 → 후 |
| --- | --- | --- | --- | --- | --- |
| 얼굴 1개, BGRA·1/2 블러 | 2698 / 2700 | 29.977 | 20.456ms | 26.594ms | nominal → serious |
| 이어서 얼굴 5개 | 2288 / 2700 | 25.418 | 28.487ms | 39.181ms | serious → serious |

두 H.264 인코더는 전달받은 프레임을 전부 인코딩했고 오류는 0건이었다. 입력 누락은 한 프레임만 처리하는 AI gate에서 발생했다. 큐를 늘려 지연을 감추지 않는다.

NV12 출력과 1/4 블러를 함께 적용한 다음 측정은 시작부터 serious, 이후 critical 상태였으며 23.271/11.531fps였다. 이를 출력 형식의 개선·회귀 증거로 사용하지 않는다. critical 상태에서 BGRA·1/4 블러 재측정도 15.109/12.844fps였으므로 출력 형식만으로 설명할 수 없다. 비교 시험은 시작 발열이 nominal로 돌아오지 않으면 건너뛰며, 이 skip을 성능 통과로 보고하지 않는다.

일반 iPhone 16에서는 다른 앱 검사 프로세스가 동시에 실행 중이었고, 냉각 대기 중 테스트 runner 연결도 끊어졌다. 위 수치는 최종 개선 판정에 사용하지 않는다. 이후 검증은 사용자가 확인한 USB 연결 Pro로 한정했다.

## 코덱과 Android 비교

- iOS 캡처 입력은 raw CVPixelBuffer다. 온디바이스 AI 앞에 H.264 decode 단계가 없다.
- iOS 직접 송출은 H.264 High, FHD 6Mbps, keyframe 2초, frame reordering 비활성화다. Android도 H.264 High와 B-frame 0을 사용한다.
- H.265·AV1 전환은 이번 변경에 포함하지 않는다. 기존 H.264 출력의 변환·처리·연속 부하부터 검증한다.
- Android의 선택 runtime을 NPU로 단정하지 않는다. 기존 S25 기록은 GPU 경로이며, 별도 QNN 실험과 구분한다.
- Android의 과거 29.7~29.9fps 기록은 실방송 조건이다. iOS 로컬 인코더 시험과 동일 조건의 재측정은 아니다.

서버의 조사 시점 누적 metrics는 real AI 왕복 284개 평균 17.407ms, decode 2268개 평균 169.456ms, encode 2266개 평균 22.685ms였다. 서로 다른 모드·입력의 누적 표본이므로 합산하거나 이번 iOS 시험과 직접 비교하지 않는다. `internal/media/ffmpeg.go`의 `ffmpeg_decode`는 압축 프레임 입력부터 디코더 응답까지의 왕복이며, 순수 GPU 추론 시간이 아니다. 이 서버 decode 경로는 iOS 직접 송출용 온디바이스 처리 앞에 존재하지 않는다.

## iPhone 16 Pro 연속 처리 결과

FHD raw NV12 입력 → 실제 AI gate → BGRA 직접 렌더 → 실제 fanout → H.264 High 6Mbps 2개, 각 조건 90초. 카메라·RTMP 네트워크를 제외한 고정 사진 부하 시험이다. `VTSessionCopyProperty`가 두 인코더 모두 hardware=true를 반환했다.

| 얼굴 수 | 전달 / 입력 | FPS | 처리 중앙값 | p95 | 두 인코더 완료 수 | 발열 전 → 후 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 2699 / 2700 | 29.985 | 21.234ms | 23.025ms | 각 2699 | nominal → nominal |
| 5, 위 조건에 이어 실행 | 2696 / 2700 | 29.951 | 21.975ms | 24.079ms | 각 2696 | nominal → nominal |

각 조건의 실패·인코더 오류는 0건이다. 5개 얼굴의 단계별 중앙값은 prepare 2.287ms, Core ML prediction 8.246ms, mask 2.709ms, render 8.393ms다. 각 단계의 중앙값을 더한 값은 전체 중앙값과 일치할 필요가 없다. 전체 처리 계측은 pool 획득과 방향 복구를 포함한다.

BGRA/NV12 출력을 번갈아 수행한 40개 표본의 중앙값은 각각 17.455/17.402ms로, NV12의 확실한 이점은 확인하지 못했다. 최종 송출 출력은 BGRA를 유지한다.

1/2 → 1/4 블러 중간 해상도 변경은 FHD landscape·portrait, HD landscape에서 검증했다. 출력 RGB8 바이트 평균 차이는 각각 0.382/0.414/0.180, 최대 차이는 5/6/4였다. 최종 sigma는 FHD 72, HD 48로 유지한다. Core Image의 선형 색상 공간에서 계산한 차이를 다시 sRGB로 변환하면 오차가 과장되므로, 각 결과를 같은 RGB8 형식으로 렌더한 뒤 바이트 차이를 계산했다.

### 실제 카메라

같은 Pro에서 `WebRTCVideoUplink.prepareNativeCamera(.fullHD30)`로 실제 전면 카메라를 실행하고 온디바이스 relay와 두 인코더에 연결했다. 잠깐의 준비 구간 뒤 90.004초 동안 2700프레임을 전달했다.

- 출력 1920×1080, 29.999fps, 처리 중앙값 21.251ms, p95 23.615ms.
- 두 인코더가 각각 2700프레임 완료. AI 실패·색상 처리 실패·인코더 오류 0건.
- 발열 nominal → nominal, 저전력 모드 꺼짐.
- 이 장면의 얼굴 수는 통제하지 않았다. 얼굴 1개·5개 부하는 위의 고정 사진 시험으로 검증했다.
- 네트워크 송출·YouTube/CHZZK 플레이어 재생 지연은 이번 시험에서 검증하지 않았다.

코드 정리 후 실제 카메라를 재측정한 결과도 90.001초·2697프레임·29.966fps, 중앙값 21.322ms, p95 23.765ms였다. 두 인코더는 각 2697프레임을 완료했고 오류는 0건, 발열은 nominal → nominal이었다. 카메라 시험의 캡처 진단 누적 수에는 측정 전 준비 구간도 포함되므로 90초 FPS 분모로 사용하지 않는다.

27개 테스트가 통과했다. 테스트 범위는 출력 회전 4방향, retained buffer 재사용 금지와 pool 상한, NaN·Inf 거부, 마스크 보호·시간 안정화, 입력 경로 전환 시 이전 프레임 폐기, 블러 픽셀 차이, 연속 처리 및 실제 카메라다.

`Release`, generic iOS 대상, `CODE_SIGNING_ALLOWED=NO` 빌드도 통과했다. 기기에 설치해 측정한 것은 Debug 계측 + Swift `-O` 빌드이며, Release 앱을 기기에 설치해 측정한 결과로 표현하지 않는다.

최종 테스트 정리 후 `PrivacySegmentationTests` 13개도 재통과했다. 로컬 모델·사진이 없는 환경에서는 선택적 성능 시험을 skip하도록 했으며, 물리 기기 성능 기준을 simulator에 적용하지 않는다. 위 Pro 검증에서는 모델과 사진을 모두 준비해 skip 없이 실행했다. `git diff --check`와 스크립트 `bash -n` 검사도 통과했다.

## 변경 파일

- `apps/ios/InnoLive/InnoLive/Features/OnDevicePrivacy/PrivacySegmentation.swift`: 전체 유한값 검사를 포인터 순회로 변경.
- `apps/ios/InnoLive/InnoLive/Features/OnDevicePrivacy/PrivacyModel.swift`: CVPixelBuffer 직접 출력, 블러 버퍼 재사용, 1/4 블러 중간 해상도, 할당 실패 시 프레임 실패.
- `apps/ios/InnoLive/InnoLive/Features/OnDevicePrivacy/PrivacyUplinkProcessor.swift`: 출력 pool과 회전 복구, Debug 단계별 계측 observer.
- `apps/ios/InnoLive/InnoLive/InnoLiveApp.swift`: 성능 테스트용 빈 host 화면을 명시적 Debug 인수로 제공.
- `apps/ios/InnoLive/InnoLiveTests/PrivacySegmentationTests.swift`: 픽셀·회전·pool·유한값 검증과 짧은 비교 시험.
- `apps/ios/InnoLive/InnoLiveTests/PrivacySustainedPerformanceTests.swift`: 90초 고정 입력 및 실제 카메라 + 두 하드웨어 인코더 시험.
- `scripts/test-ios-privacy-performance.sh`: 모델·fixture 확인, 최적화 계측 빌드, 테스트 host 설정 및 실행.
- 이 문서와 `docs/ios-privacy-performance-results-2026-10-19.json`: 조건·수치·검증 한계 기록.

변경은 `ios-privacy-performance` worktree에서 성능 수정·테스트·문서 목적별로 커밋했다. PR·배포는 수행하지 않았다.

## 재현

세 Core ML package를 `apps/ios/InnoLive/InnoLive/Resources/`에 준비한다. 사진은 [scikit-image v0.22.0 astronaut.png](https://raw.githubusercontent.com/scikit-image/scikit-image/v0.22.0/skimage/data/astronaut.png)를 `apps/ios/InnoLive/InnoLiveTests/PrivacyFaceFixtures/privacy-performance-face.png`에 둔다. 파일 SHA-256은 `88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5`다. 모델과 사진은 Git에 추가하지 않는다.

```sh
scripts/test-ios-privacy-performance.sh <physical-device-udid> /tmp/innolive-privacy-perf
```

스크립트는 `build-for-testing` 뒤 생성된 xctestrun에 `--privacy-performance-test-host` 인수를 넣는다. 이 Debug 전용 화면에서는 홈 미리보기 카메라를 실행하지 않아 시험용 카메라와 충돌하지 않는다. 실제 앱 실행에는 영향을 주지 않는다. `INNOLIVE_PERF_TEST_FILTER`로 XCTest suite 또는 method 하나를 선택할 수 있다.

## 근거 문서

- [성능·트러블슈팅 자료 모음](https://www.notion.so/3f8ba097c65980929a67e8d618ecc490)
- [기존 iOS 온디바이스 실험](https://www.notion.so/3ebba097c6598034ac03f9da1735e3dc): Debug 최적화 옵션에 따라 처리 시간이 달라진 기록.
- [S25 온디바이스 성능 기록](https://www.notion.so/3faba097c659807c91d8ebef28f6a279): 기존 Android 실방송 비교 기준.
- [현재 직접 송출 구조](https://www.notion.so/3f7ba097c65980ffa210e2ecba76df18): 이전 미구현 상태 문서와 구분.
- 저장소 `docs/android-on-device-ai.md`, `docs/ios-on-device-privacy-experiments.md`, `docs/ios-on-device-privacy-lab.md`.


## 실제 YouTube·치지직 동시송출

2026-10-19 17:43:51.691~17:45:21.691 KST의 90초를 선택했다. iPhone 16 Pro, 같은 최적화 빌드, 실제 카메라·마이크, 온디바이스 AI, 두 플랫폼 직접 송출 조건이다. 얼굴 수는 통제하지 않았다. 서버에 저장된 약 15초 간격 하트비트 6개를 집계했다. 각 보고 구간이 측정 경계와 일부 겹치므로 아래 FPS는 90초의 모든 프레임을 정확히 센 값이 아니라 구간 보고값의 평균이다.

| 지표 | 결과 |
| --- | --- |
| 직접 송출 입력 해상도 | 1080×1920 FHD |
| 처리 후 fanout 전달 FPS | 평균 28.05, 구간 24.6~30.0 |
| AI 전체 처리 p50 | 구간별 17.4~29.4ms |
| AI 전체 처리 p95 | 구간별 28.1~38.5ms |
| YouTube 비트레이트 | 평균 6.058Mbps, 6개 표본 |
| 치지직 비트레이트 | 평균 6.057Mbps, 4개 표본 |
| YouTube 연결 | 6/6 보고 연결됨, 재연결 0회 |
| 치지직 연결 | 5/6 보고 연결됨, 재연결 1회 후 복구 |
| 보고된 변환 드롭 / AI 처리 예외 | 0 / 0 |
| 앱 CPU | 평균 89.51%, 최대 116.94%, 86개 표본 |
| 시스템 CPU | 평균 54.28%, 최대 78.45%, 85개 표본 |
| physical footprint | 평균 982.34MB, 최대 1001.82MB |
| 발열 | fair → serious |

하트비트 필드 `inference_ms_*`는 현재 iOS 코드에서 Core ML prediction만이 아니라 AI 처리 진입부터 출력 렌더까지를 잰다. 구간별 p50의 평균을 전체 프레임 중앙값으로 해석하지 않는다. FPS는 두 RTMP 출력에 전달하기 전 fanout 프레임 수이며, 플랫폼 플레이어의 실제 디코딩 FPS는 측정하지 않았다. `dropped_frames`는 변환 실패 등의 보고값으로 AI busy gate에서 생략한 입력을 포함하지 않는다. 따라서 드롭 0으로 전체 프레임 무손실을 주장하지 않는다. CPU와 메모리는 이전 측정과 같은 코어 합산·physical footprint 기준이다.

첫 구간은 30fps에 도달했지만 후반에는 24.6fps로 낮아졌다. 종료 조작 중에도 송출은 이어졌고, 선택한 90초 이후에는 최소 16.5fps와 누적 변환 드롭 27개가 기록됐다. 발열은 serious였지만 인과관계는 이번 시험만으로 확정하지 않는다. 실제 앱은 미리보기·보조 WebRTC·녹화 경로도 실행하므로, 로컬 두 인코더 시험과 부하가 다르다. 실제 동시송출에서 지속적인 30fps 달성은 아직 검증되지 않았다.

화면에는 `360p·29fps`, 이후 `180p·9fps`와 네트워크 경고가 표시됐다. 소스를 확인하면 직접 송출 중 서버 WebRTC 영상을 의도적으로 낮추며, 화면은 이 보조 업링크 품질을 표시한다. 플랫폼 직접 송출 fanout의 FHD 값과 다르다. UI 경고를 플랫폼 송출 해상도 저하의 증거로 사용하지 않는다.

로컬 녹화는 음성 timestamp 중복 오류로 저장에 실패했다. 기존 측정에서도 발생한 오류이며 이번 최적화에서 수정하지 않았다. 플레이어 재생 및 카메라→플랫폼 E2E는 이번 재시험에서 확인하지 않았다.

전체 방송 종료 후 앱에서 두 플랫폼 송출 표시가 사라진 것을 확인했고, 준비 취소 후 서버가 해당 세션 종료를 17:47:15.704 KST에 기록했다. 다른 사용자의 활성 세션 수는 종료 판단에 사용하지 않았다.

원자료는 로컬 `ios-benchmark-kit/measurement-optimized-dual/`의 하트비트 JSONL·기기 표본·집계 스크립트에 보존했다. 저장소에는 계정 정보와 원본 로그 대신 요약 수치만 추가했다.
