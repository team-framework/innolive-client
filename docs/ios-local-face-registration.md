# iOS 온디바이스 다중 얼굴 등록

기존 로컬 비식별화 MVP 위에 여러 등록자를 저장하고 해당 얼굴의 블러를 해제하는 실험이다.
대상은 iPhone 16·iOS 27, `InnoLive Local` Debug 앱이다. 서버 API·시그널링 계약 변경은 없다.
이슈 #283, 기반 이슈 #281 / Draft PR #282.

## 모델 변환

기존 서버 기본 모델인 ViT-Base KP-RPE WebFace12M을 그대로 사용한다.
학습과 가중치 변경은 없다. 공식 체크포인트의 SHA-256을 확인하고 원본·좌우 반전의
norm 가중 합산 및 L2 정규화까지 하나의 Core ML 모델에 포함한다.

- 파라미터: 115,076,736개. 파라미터만 FP16으로 계산하면 230.15MB.
- 생성한 FP16 ML Program 패키지: 232,905,024바이트, 약 232.9MB.
- 입력: RGB 112×112, 내부 `/127.5 - 1`, top-left 정규화 5개 landmark `[1,5,2]`.
- 출력: 정규화한 512차원 embedding `[1,512]`.
- 원본 backbone Python 소스 SHA-256과 checkpoint SHA-256을 모델 metadata에 기록한다.
- checkpoint SHA-256: `04b4bee1de7cefa9e97900f8449fca906d8afbab2029bd39cc5049d33e927ed9`.

```sh
curl -L --fail -o /tmp/adaface-vit.ckpt \
  https://huggingface.co/minchul/cvlface_adaface_vit_base_kprpe_webface12m/resolve/daefd5012d369588bd214fbaf4cc6b1d286e7066/pretrained_model/model.pt
/tmp/innolive-coreml-venv/bin/python scripts/export-ios-face-model.py \
  --ai-repo ../innolive-ai --checkpoint /tmp/adaface-vit.ckpt
```

환경은 `scripts/requirements-ios-privacy.txt`를 사용한다. 현재 Torch 2.14.0,
Core ML Tools 9.0이다. 변환기는 이 Torch 버전을 공식 테스트하지 않았다는 경고를 낸다.
모델 생성물은 Git에서 제외한다. 기존 YOLO `PrivacyDetector.mlpackage`도 빌드 전에 필요하다.
출력 파일을 덮어쓰지 않으므로 비교 실험은 `--output`으로 새 경로를 지정한다.

### 2026-09-21 변환 검증

3개의 고정 seed 무작위 RGB 입력과 조금씩 다른 landmark로 PyTorch FP32와
Mac CPU Core ML FP16의 출력 벡터를 비교했다.

| 입력 | 코사인 유사도 | 최대 절대 오차 | Mac CPU predict 시간 |
| --- | ---: | ---: | ---: |
| 0 | 0.99991804 | 0.00171995 | 119.81ms |
| 1 | 0.99988973 | 0.00202225 | 85.98ms |
| 2 | 0.99990785 | 0.00170663 | 85.73ms |

이 검사는 변환 수치 검증이다. 실제 얼굴 인식 정확도, 모바일 속도, 지속 발열을 측정하지 않는다.
Mac 값에는 Core ML predict 호출 시간이 포함되며 iPhone 처리 시간으로 해석하지 않는다.

## 구현 범위와 검증 기록

### 등록과 저장

앱이 시작되면 별도 큐에서 AdaFace(`.cpuAndGPU`)·YuNet(`.cpuOnly`)을 준비하고 AdaFace 첫 추론까지 완료한다.
화면에 준비 중임을 표시하고 준비가 끝나기 전에는 등록 버튼을 비활성화한다.
준비가 끝난 뒤 `얼굴 등록 관리`에서 이름을 입력하고 `카메라로 등록`을 누른다.
이 시점부터 30초 등록 시간을 센다. 파란 정사각형 안에 얼굴을 맞춘다.

기존 iOS `FaceDetectionService`의 중앙 500×500 crop, 얼굴 한 명·크기·중앙 위치 검사,
JPEG 품질 0.9 처리 코드를 재사용한다. 기존 등록 ViewModel처럼 350ms 이상 간격으로
준비된 얼굴을 3회 확인한 뒤 사진 한 장의 embedding으로 등록한다.
서버 API 호출은 로컬 특징값 저장으로 대체한다. 등록은 최대 20명이며 각 항목을 개별 삭제한다.

이름·UUID·정규화 embedding을 `Library/Application Support/privacy-lab-faces-yunet.json`에 저장한다.
사진·crop은 저장하지 않는다. 저장 파일에 iOS complete file protection과 백업 제외 속성을
적용하며 API 전송을 하지 않는다. 앱 재실행 후 등록 목록을 읽는다.
모델·전처리 계약이 다른 등록 데이터는 거부한다. 초기 Vision 실험의 `privacy-lab-faces.json`은
덮어쓰거나 삭제하지 않으며 이번 YuNet 경로에서 읽지 않는다. 기존 사용자는 다시 등록해야 한다.
저장 실패는 UI에 표시한다.
삭제 저장이 실패하면 얼굴 예외 전체를 중지한다.

### 얼굴 전처리와 비교

초기 실험에서 사용한 Vision landmark를 제거하고 서버의 YuNet 2023mar 가중치를 재사용한다.
기존 iOS의 Vision rectangle 검사는 등록 사진 준비 단계에서만 사용한다.

- YuNet은 BGR 0~255 입력에 오른쪽·아래 검정 padding을 추가해 32의 배수로 만든다.
  OpenCV FaceDetectorYN과 같은 stride 8/16/32 decode, `sqrt(cls * obj)` 점수,
  정수 bbox NMS 0.3, top_k 5000을 사용한다. 재학습은 없다.
- 서버처럼 YuNet 탐지 문턱 0.6으로 NMS한 뒤 등록은 0.9, 영상 비교는 0.6 이상을 채택한다.
  얼굴 최소 변 길이는 등록 40px, 영상 비교 24px이다. 추가 yaw 제한은 두지 않는다.
- YuNet의 눈 2개·코·입 양 끝 좌표를 그대로 사용한다. 서버 `_square_face_crop`과 같이
  bbox의 긴 변을 1.5배 확장한 정사각형, 검정 외곽 padding, RGB 112×112,
  top-left 정규화 landmark를 AdaFace에 전달한다. 영상에서 YOLO bbox의 crop margin도
  서버 기본값 0.25로 맞춘다.
- 원본·반전 norm 가중 결합은 기존 Core ML AdaFace 안에 포함된다.
- 기존 등록과 코사인 유사도 0.75 이상이면 중복 후보로 등록을 거부한다.
- 블러 예외 비교는 이번 수정에서도 0.60, 1·2위 점수 차 0.08을 유지한다.
  서버 소스의 기본 코사인 문턱은 0.4다. 운영 서버의 실제 실행 옵션은 확인하지 않았다.
  전처리 수정과 매칭 문턱 완화를 섞어 결과를 판단하지 않는다.

YuNet 생성:

```sh
curl -L --fail -o /tmp/yunet.onnx \
  https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx
/tmp/innolive-coreml-venv/bin/python scripts/export-ios-yunet-model.py --onnx /tmp/yunet.onnx
```

`scripts/requirements-ios-privacy.txt`에 ONNX 1.19.1을 추가했다. 변환 스크립트는
SHA-256 `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`의
그래프에 있는 연산만 PyTorch로 옮긴다. FP32 Core ML 모델은 291,013바이트이며
32~2048px의 가변 입력을 받는다. Swift에서는 32의 배수로 padding한다.
OpenCV의 stride decode/NMS 공식을 Swift로 옮겼으며 코드에 출처를 표시했다.

### 카메라 처리와 블러 예외

카메라·YOLO·마스크 처리는 기존 serial queue에서 수행한다. 얼굴 인식은 별도 serial queue와
작업 한 개짜리 mailbox를 사용해 오래된 crop이 쌓이지 않게 한다. 대상마다 최소 250ms
간격으로 오래 대기한 얼굴부터 순서대로 인식한다. 등록 20명 지원은 화면 속 20명의
지속 인식을 검증했다는 뜻이 아니다.

bbox IoU 0.50 이상인 유일한 대응만 같은 track으로 유지한다. 얼굴끼리 IoU가 0.05를
넘어 겹치거나 대응이 모호하면 track을 버린다. 동일 등록자에 두 번 연속 일치해야
블러 예외를 허용하며, 결과의 원본 프레임 시각으로부터 최대 500ms 동안 유지한다.
유효한 embedding의 불일치, 500ms 이상 늦은 비교 결과, 얼굴 사라짐, 500ms 이상 프레임 간격, 중지·카메라 전환,
등록 목록 변경은 예외를 초기화한다. 같은 등록자가 두 track에서 동시에 검출되면 둘 다
블러를 유지한다. 이전 작업의 결과는 generation과 track UUID가 맞고 이전에 반영한 결과보다 새 프레임이어야 반영한다. 중복·역순 결과는 확인 횟수나 기한을 늘리지 않는다.
[Android와 공통 얼굴 예외 캐시 정책](on-device-face-exceptions.md)을 사용한다.
번호판은 얼굴 비교 대상에서 제외하고 계속 보호한다. 얼굴 검출·landmark·크기 등의
일시적인 입력 실패는 이전 일치 결과를 즉시 지우지 않는다. 기존 500ms 기한을 연장하지도
않는다. 한 crop에 여러 얼굴이 있거나 런타임 오류가 발생하면 예외를 취소한다.

bbox 연속성은 신원 증명이 아니다. 급격한 장면 전환이나 교차에서 다른 사람에게 캐시가
이어질 가능성과 모델 오인식을 실제 다인 장면에서 확인해야 한다. 보호 정확도를 보장하는
구현으로 해석하지 않는다. 카메라 preview의 처리 시간에는 별도 큐의 얼굴 인식 시간이
합산되지 않으며 UI가 얼굴 인식 시간을 별도로 표시한다.

### 2026-09-21 iPhone 16·iOS 27 실측

초기 Vision 버전에서 서명한 Debug 앱을 Swift `-O`·wholemodule로 빌드하고 실제 iPhone 16에 설치했다.
`--face-model-benchmark` 실행 인자로 카메라 없는 전용 화면에서 실제 모델의 합성 RGB
입력과 고정 landmark를 처리했다. 설정별 10회 중 앞 2회를 제외한 8회 통계다.
원본·반전 두 입력의 fusion까지 포함한다.

| Core ML 설정 | 중앙 추론 시간 | 모델 로드 시간 | 추론 시 앱 physical footprint 최대 |
| --- | ---: | ---: | ---: |
| `.all` | 204.77ms | 4,253.96ms | 1,027.43MB |
| `.cpuAndNeuralEngine` | 317.41ms | 35,414.88ms | 714.49MB |

초기 실험 후 앱 기본값은 `.all`로 설정했다. 현재는 아래 준비 시간 최적화 결과에 따라 `.cpuAndGPU`를 사용한다. 연산별 CPU·GPU·Neural Engine 배치를 직접
검사한 결과는 아니다. 메모리는 가중치만의 크기가 아니라 앱 전체 physical footprint이며,
두 번째 설정은 같은 프로세스에서 첫 번째 설정 측정 후 실행했다.
로드 시간은 최초 설치 직후의 cold compilation 시간으로 일반화할 수 없다.

이 실측은 iPhone에서 모델을 로드하고 실행할 수 있음을 확인한다. Vision landmark,
카메라·YOLO 동시 실행, 실제 다인 등록·식별 정확도, 지속 FPS·발열·배터리는 포함하지 않는다.

```sh
# 기존 docs/ios-on-device-privacy-lab.md의 빌드·설치 명령을 사용한다.
xcrun devicectl device process launch --device '<device-id>' --terminate-existing \
  com.framework.innolive.privacy-lab --face-model-benchmark
xcrun devicectl device copy from --device '<device-id>' \
  --domain-type appDataContainer --domain-identifier com.framework.innolive.privacy-lab \
  --source Library/Caches/privacy-face-benchmark.json --destination /tmp/privacy-face-benchmark.json
# 인자 없이 다시 실행하면 등록 가능한 카메라 화면으로 돌아간다.
```

측정 로그는 `privacy-face-benchmark.json`, 실제 얼굴 작업의 숫자 로그는
`privacy-face-metrics.json`이다. 이름·embedding·얼굴 사진을 이 로그에 넣지 않는다.
카메라 `privacy-lab-metrics.json`에도 등록 수·블러 해제 수·최근 얼굴 인식 시간을 추가했다.

### 카메라 실행 중 관측한 숫자 로그

벤치마크 후 일반 로컬 카메라 화면으로 다시 실행했다. 기기에서 복사한
`privacy-lab-metrics.json`에는 48개 표본이 있었고, 등록 수 1명인 39개 표본은
76.00초 범위를 포함했다. 해당 구간의 처리 시간 중앙값은 21.91ms, 순간 처리 FPS
중앙값은 28.65였다. 39개 중 18개 표본에 블러 해제 수 1명이 기록됐다.

이 기록으로 실제 등록 저장과 블러 예외 코드가 기기에서 실행된 사실을 확인했다.
촬영 장면·사람의 정답을 관찰하지 않았으므로 18/39를 인식 성공률로 해석하지 않는다.
화면 이탈·자세·겹침·UI 조작을 통제한 성능 비교도 아니다. 실제 다른 사람을
구별하는지와 다인 동시 식별은 별도로 확인해야 한다.

### 자동 검증과 남은 기기 확인

- iOS 기기 빌드 성공, iPhone 16 설치 및 합성 입력 추론 성공.
- iOS 27 iPhone 18 Pro 시뮬레이터에서 23개 테스트 통과.
  신규 12개: 다중 등록 매칭, 모호한 후보·비정상 벡터 거부, 저장·재로드·개별 삭제,
  백업 제외, 등록 한도·모델 계약, 2회 확인, 만료·불일치·교차·사라짐·순서 변경 처리.
  기존 11개: segmentation·경계 안정화 회귀 검사.
- 실제 두 명 이상을 등록한 뒤 동시 등장, 재등장, 교차, 개별 삭제, 앱 재실행,
  비행기 모드, 미등록 인물 유지, 번호판 보호를 사용자 기기에서 확인해야 한다.
- 같은 장면에서 YOLO와 인식의 동시 실행 성능 및 15분 이상 발열을 추가 측정해야 한다.

## 근거

- [공식 모델](https://huggingface.co/minchul/cvlface_adaface_vit_base_kprpe_webface12m)
- [기존 서버 전처리](https://github.com/team-framework/innolive-ai/blob/main/service/adaface_model.py)
- [Apple Core ML 변환 출력 비교](https://apple.github.io/coremltools/docs-guides/source/model-prediction.html)
- 초기 Core ML 변환 기록: `07bd8b3`.

- 다중 등록·실기기 검증 코드: `bd3be09`.

## 등록 얼굴 재블러 진단 (2026-09-21)

사용자가 서버보다 등록자 재블러가 잦다고 보고했다. 수정 전 기기 얼굴 작업 로그 751건 중
embedding 생성 성공은 289건, 실패는 462건이었다. 실패 시간 중앙값은 6.02ms,
성공은 156.23ms로 전처리 탈락이 의심됐다. 기존 로그만으로 실패 원인을 구분할 수 없어
`privacy-face-metrics.json`에 숫자 `failure_code`를 추가했다. 1=얼굴 수, 2=검출 신뢰도,
3=각도, 4=landmark, 5=크기, 9=그 외 오류다.

`privacy-face-decisions.json`에는 각 비교의 1·2위 유사도, 입력부터 결과 반영까지 시간,
현재 track 존재 여부, 매칭 여부를 기록한다. 이름·UUID·embedding·이미지는 기록하지 않는다.
등록 파일이나 인식 조건은 이 진단 단계에서 바꾸지 않았다.

### 등록 실패 원인과 기존 경로 복원

진단 빌드를 설치한 뒤 사용자가 등록 실패를 보고했다. 숫자 로그 한 건에서 AdaFace
모델 로드가 54,832.11ms, 얼굴 작업 전체가 55,441.64ms였고 embedding 생성은 성공했다.
기존 30초 등록 제한이 모델 준비 중 먼저 만료된 것이 이 시도의 직접 원인이었다.
기존 코드는 2초보다 오래된 등록 결과도 버렸으므로 초기화 시간과 등록 입력 수명을 분리할 필요가 있었다.

현재는 앱 시작 시 모델 로드와 첫 AdaFace 추론을 끝낸 뒤 등록을 허용한다. 등록은 기존
사진 준비 코드를 통과한 한 장의 embedding으로 끝내며, 임의의 3개 embedding 일치 조건을
제거했다. 업데이트한 iPhone에서 준비 완료 `state=1`, 준비 시간 62,851.28ms를 확인했다.
이 준비 시간 동안 등록 요청을 받지 않으며 등록 제한 시간도 시작하지 않는다.

YuNet의 전처리로 바꾸면서 영상 입력에 추가했던 Vision 신뢰도·yaw·landmark 조건도 제거했다.
직전 일치 상태는 일시적인 입력 실패 때 원래 기한까지만 유지하며, 실제 비교 불일치와
기하학적 모호성에서는 이전처럼 해제한다. 원래 블러 예외의 기한을 늘리거나 매칭 문턱을
낮추는 수정은 포함하지 않았다.

검증:

- YuNet FP32 Core ML 출력 12종과 OpenCV DNN 출력을 320×320, 512×512, 384×640
  무작위 입력에서 비교했다. 최대 절대 오차는 0.00000477이었다.
- OpenCV 공개 샘플 `lena.jpg` 한 장에서 YuNet decode/NMS 결과의 좌표·점수 최대 절대
  차이는 0.00001526이었다. 서버 Python 전처리·PyTorch AdaFace와 Core ML 경로의
  최종 embedding 코사인 유사도는 0.99986982, 최대 절대 오차는 0.00265833이었다.
  Mac CPU 결과이며 Swift Core Image 보간 차이와 실제 인식 정확도를 검증한 값은 아니다.
- iOS 기기 빌드 성공, iPhone 16 설치·모델 사전 준비 완료 확인.
- iOS 27 시뮬레이터 집중 테스트 32개 통과. 기존 23개에 입력 실패의 제한된 유지 4개,
  YuNet stride/landmark·NMS·잘못된 출력·서버 crop 좌표·기존 촬영 준비 코드 5개를 추가했다.
- 새 전처리로 다시 등록한 뒤 실제 사용자 인식과 재블러 빈도를 확인해야 한다.

진단 로그의 최신 코드: 1=얼굴 없음, 2=검출 신뢰도, 3=각도, 4=landmark,
5=크기, 6=여러 얼굴, 9=기타 오류·등록 준비 메시지. 2~4는 초기 Vision 진단과의 호환용이다.
모델 준비 상태는 `privacy-face-preparation.json`의 0=준비 중, 1=완료, 2=실패로 기록한다.

- [OpenCV FaceDetectorYN 구현](https://github.com/opencv/opencv/blob/4.x/modules/objdetect/src/face_detect.cpp)

새 YuNet 빌드를 기기에서 실행한 뒤 숫자 로그에 등록 1명이 기록돼 등록 완료를 확인했다.
후속 비교 61건에서는 embedding 생성 28건, 얼굴 없음 10건, 여러 얼굴 판정 23건이었다.
유효 비교의 최고 유사도 중앙값은 0.66072였고 21건이 로컬 문턱을 넘었다.
촬영 대상과 장면을 통제하지 않았으므로 재블러 감소율이나 인식 정확도로 해석하지 않는다.

### YuNet 실행 장치 조정

가변 크기 YuNet을 CPU+GPU로 실행한 첫 기기 로그에서 앱 전체 physical footprint가
약 2,326MB까지 관측됐다. 이 숫자만으로 GPU나 특정 모델의 할당량을 분리할 수는 없다.
가변 입력의 GPU 실행·캐시 비용을 줄이는 방향으로 YuNet은 `.cpuOnly`, AdaFace는 `.all`로
설정했다. 이후 AdaFace만 아래 준비 시간 최적화에서 `.cpuAndGPU`로 변경했다.
모델 가중치·FP32 YuNet 계산 정밀도·전처리·매칭 문턱은 유지한다.
서명한 iOS 빌드가 통과했으며 같은 앱 식별자로 업데이트해 새 YuNet 등록 데이터를 보존한다.

CPU YuNet 설정으로 업데이트한 기기에서 준비 완료 `state=1`, 준비 시간 45,750.28ms를
확인했다. 최종 확인 시점에는 이 실행의 새 얼굴 비교 표본이 없었으므로 CPU 설정의
실제 얼굴 처리 시간·메모리 개선 수치는 아직 보고하지 않는다. 직전 YuNet 빌드에서
완료된 등록 파일을 그대로 유지하며 재설치하지 않고 앱 업데이트로 적용했다.

### 등록 이후 재검출 실패와 입력 크기 제한

등록자가 계속 블러 처리된다는 보고 후 CPU YuNet 빌드의 숫자 로그를 확인했다.
40.07초 동안 비교 88회 중 얼굴 없음 62회, 여러 얼굴 12회, embedding 생성 14회였다.
14개 유효 입력 중 5개가 매칭 문턱을 넘었다. 현재 track은 80회 존재했으며,
유효 결과 반영 지연 중앙값은 164.49ms였다. 비교 전 YuNet 재검출에서 대부분 탈락했다.
별도 카메라 로그 89개에서 블러 해제는 관측하지 못했다. 두 로그의 표본 간격은 다르다.
앱 전체 physical footprint 최댓값은 약 1,162MB였다.

실시간 인식은 고해상도 얼굴 ROI를 YuNet에 그대로 전달하고 있었다. 이제 긴 변이
320px를 넘는 query만 축소하고, 검출한 bbox와 5개 landmark를 원본 ROI 좌표로 복원한다.
AdaFace에는 원본 ROI에서 자른 얼굴을 전달한다. 등록의 기존 500×500 입력, 저장된
당시에는 YuNet embedding, 유사도 0.60, 2회 확인 및 750ms 유효 기간을 유지했다. 현재 유효 기간은 공통 정책에 따라 500ms다.
로그에 등록/비교 구분, 원본 입력 크기와 YuNet 입력 크기를 추가했다.
이 변경은 큰 얼굴 입력의 검출 불안정을 줄이기 위한 수정이며, 위 로그만으로
입력 크기가 실제 실패의 유일한 원인이라고 확정할 수는 없다.

검증:

- 공개 OpenCV 샘플을 동일한 320×320 PNG 픽셀로 준비하고 Swift의
  CGImage → Core Image → CVPixelBuffer → Core ML → decode/NMS 경로를 실행했다.
  Mac Swift와 OpenCV의 bbox·landmark·점수 최대 절대 차이는 약 0.00001526이었다.
  JPEG를 각각 디코딩한 초기 비교에서는 좌표 차이가 약 1.74px였으므로,
  픽셀 입력을 맞추기 위해 lossless PNG로 검사했다.
- 같은 fixture의 iOS 27 시뮬레이터 native parity 테스트와 크기·좌표 복원 회귀 검사를
  포함해 35개 테스트가 통과했다. 실패 0개, 생략 0개다. iOS 기기 서명 빌드도 통과했다.
- 실제 등록자의 재블러 감소와 미등록자 보호는 수정본의 기기 로그와 화면에서 별도로 확인한다.
  공개 이미지 한 장의 수치 일치를 인식 정확도로 해석하지 않는다.

선택적인 native parity fixture는 아래 명령으로 생성한다. 이미지와 fixture 생성물은
Git에서 제외한다. fixture나 Core ML 모델이 없으면 해당 검사만 `XCTSkip`으로 표시된다.

```bash
python scripts/prepare-ios-yunet-fixture.py \
  --image /path/to/public-test-image.jpg \
  --onnx /path/to/face_detection_yunet_2023mar.onnx
```

수정 커밋 `f0310b3`을 같은 앱 식별자로 iPhone 16에 업데이트했다. 기기에서 모델 준비
완료 `state=1`, 준비 시간 68,719.39ms를 확인했다. 최종 카메라 로그에는 등록 0명,
검출 0개가 기록돼 이 실행의 새 얼굴 비교는 없었다. 앱 컨테이너의 YuNet 등록 파일은
존재하지만 59바이트이며, 이전 실행의 얼굴 비교 로그를 수정본 결과로 해석하지 않았다.
등록 데이터 초기화·삭제 코드는 이번 변경에 포함하지 않았다. 등록 후 기기 검증이 필요하다.

### 사용자 확인 및 준비 시간 최적화 착수

사용자가 입력 크기 수정 후 등록자 블러 해제가 잘 동작한다고 확인했다. 이어 앱 재실행,
등록 유지, 카메라 전환·백그라운드 복귀를 포함한 앞서 제안한 3번 검증도 완료했다고 알렸다.
이는 사용자 확인이며 자동화한 다인 정확도 평가 결과는 아니다. 등록자·미등록자 동시 등장과
교차 검증은 사용자가 추후 진행한다.

준비 시간을 비교하기 위해 `f0310b3` 코드가 설치된 앱을 재설치 없이 종료·재실행했다.
준비 시간은 9,559.86ms였다. 직전 설치 직후의 68,719.39ms와 구분해 기록한다.
모델 로드 구간을 AdaFace·YuNet으로 나누고 첫 추론 시간도 별도로 측정한다.

### AdaFace CPU·GPU 실행으로 준비 시간 단축

iPhone 16·iOS 27에서 AdaFace 기본 실행 설정을 `.all`에서 `.cpuAndGPU`로 변경했다.
동일한 FP16 모델, 512차원 embedding, 원본·반전 결합, 등록 저장소와 매칭 기준을 유지한다.
YuNet은 `.cpuOnly`다. `.all`의 연산별 실제 배치를 Instruments로 추적하지는 않았으므로
지연 전체를 특정 하드웨어의 컴파일 시간으로 단정하지 않는다.

카메라 없이 같은 프로세스에서 `.all` 다음 `.cpuAndGPU`를 각각 10회 실행한 첫 비교:

| 설정 | 전체 모델 로드 | AdaFace 로드 | YuNet 로드 | 첫 합성 추론 | 이후 8회 추론 중앙값 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `.all` | 44,071.26ms | 43,962.73ms | 108.09ms | 216.32ms | 213.63ms |
| `.cpuAndGPU` | 644.82ms | 596.72ms | 47.95ms | 1,309.69ms | 59.68ms |

이 첫 비교의 단색 입력에서 두 설정의 embedding 코사인 유사도는 0.99970126이었다.
두 번째 설정은 첫 번째 설정 이후 같은 프로세스에서 실행했으므로 load 수치만으로
새 설치의 전체 준비 시간을 추정하지 않았다.

최종 설정을 앱에 적용한 뒤 일반 카메라 화면에서 측정했다. 준비 시간에는 모델 로드와
첫 AdaFace 추론을 모두 포함한다. 앱 아이콘을 누른 시점부터 화면이 보일 때까지의 시간은 아니다.

| 실행 조건 | 기존 `.all` | 변경 `.cpuAndGPU` |
| --- | ---: | ---: |
| 앱 업데이트 직후 관측 | 68,719.39ms | 1,526.76ms |
| 재설치 없이 종료 후 재실행 | 9,559.86ms | 305.55ms |

업데이트 직후 새 설정의 AdaFace 로드는 460.02ms, YuNet 로드는 838.99ms,
첫 추론은 214.19ms였다. 재실행에서는 각각 52.25ms, 18.93ms, 220.91ms였다.
단일 기기의 각 1회 관측이며 OS 캐시·온도·동시 작업을 통제한 반복 벤치마크는 아니다.
카메라 로그에서 기존 등록 1명이 유지된 것도 확인했다.

`privacy-face-preparation.json`에 `compute_mode=2`(CPU·GPU), `recognizer_load_ms`,
`detector_load_ms`, `warmup_ms`, 시작 `uptime`을 기록한다. 등록 가능 상태는 이전처럼
첫 추론까지 성공한 뒤에만 켠다.

- [Apple: 모델 로드·실행 장치별 specialization·캐시 설명](https://developer.apple.com/videos/play/wwdc2023/10049/)

추가 검증에서는 단색·그라데이션·체크무늬 3개 합성 입력을 설정마다 12회 실행하고
처음 3회를 준비 표본으로 제외했다. 두 설정의 embedding 유사도 최솟값은
0.99970126으로 기준 0.999를 통과했다. 반복 추론 중앙값은 `.all` 203.34ms,
`.cpuAndGPU` 61.10ms였다. 이 수치 비교는 실제 다인 인식 정확도 검증을 대신하지 않는다.
벤치마크는 얼굴 사진을 촬영하거나 저장하지 않는다.

```sh
xcrun devicectl device process launch --device '<device-id>' --terminate-existing \
  com.framework.innolive.privacy-lab --face-model-benchmark --face-model-compare-gpu
```

iOS 서명 빌드와 시뮬레이터 집중 테스트 35개를 통과했다(실패 0개, 생략 0개).
최종 설정을 iPhone 16에 설치했고, 비교 후 실행 인자 없이 일반 카메라 화면으로 복귀했다.
현재 결과는 iPhone 16·iOS 27 범위이며 다른 기기와 장시간 발열·배터리는 별도 검증이 필요하다.
