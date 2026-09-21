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

화면의 `얼굴 등록 관리`에서 이름을 입력하고 `카메라로 등록`을 누른다.
한 명씩 가까이 정면으로 등록한다. YOLO에서 얼굴이 한 개일 때 서로 일관된 embedding
3개를 모아 합산·정규화한다. 30초 안에 완료하지 못하면 재시도한다.
등록은 최대 20명이며, 각 항목의 `삭제`로 해당 등록만 제거한다.

이름·UUID·정규화 embedding을 `Library/Application Support/privacy-lab-faces.json`에 저장한다.
사진·crop은 저장하지 않는다. 저장 파일에 iOS complete file protection과 백업 제외 속성을
적용하며 API 전송을 하지 않는다. 앱 재실행 후 등록 목록을 읽는다.
모델·전처리 계약이 다른 등록 데이터는 거부한다. 저장 실패는 UI에 표시한다.
삭제 저장이 실패하면 얼굴 예외 전체를 중지한다.

### 얼굴 전처리와 비교

- 서버는 YuNet, 이번 iOS 실험은 Apple Vision의 얼굴 landmark를 사용한다.
- YOLO 얼굴 bbox 주변을 crop한 뒤 Vision에서 얼굴 한 개를 확인한다. 눈 중심 2개,
  nose crest 하단 1개, 입술 양 끝 2개를 얻는다. 얼굴 bbox를 정사각형 1.5배로 확장해
  RGB 112×112와 top-left 기준 landmark를 만든다.
- 원본·반전 결합은 모델 안에 포함된다. 신규 모델 학습이나 기존 서버 등록값 가져오기는 하지 않는다.
- 등록 샘플 간 코사인 유사도 하한 0.65, 기존 등록과 0.75 이상이면 중복 후보로 등록을 거부한다.
- 영상 비교의 실험 임계값은 0.60이며, 1·2위 점수 차가 0.08 미만이면 블러를 유지한다.
  이 값은 실제 사용자 데이터로 보정한 운영 기준이 아니다. Vision과 YuNet의 전처리 차이도
  인식 품질 검증 대상이다.

### 카메라 처리와 블러 예외

카메라·YOLO·마스크 처리는 기존 serial queue에서 수행한다. 얼굴 인식은 별도 serial queue와
작업 한 개짜리 mailbox를 사용해 오래된 crop이 쌓이지 않게 한다. 대상마다 최소 250ms
간격으로 오래 대기한 얼굴부터 순서대로 인식한다. 등록 20명 지원은 화면 속 20명의
지속 인식을 검증했다는 뜻이 아니다.

bbox IoU 0.50 이상인 유일한 대응만 같은 track으로 유지한다. 얼굴끼리 IoU가 0.05를
넘어 겹치거나 대응이 모호하면 track을 버린다. 동일 등록자에 두 번 연속 일치해야
블러 예외를 허용하며, 결과의 원본 프레임 시각으로부터 최대 750ms 동안 유지한다.
불일치, 750ms 이상 늦은 결과, 얼굴 사라짐, 200ms 이상 프레임 간격, 중지·카메라 전환,
등록 목록 변경은 예외를 초기화한다. 같은 등록자가 두 track에서 동시에 검출되면 둘 다
블러를 유지한다. 이전 작업의 결과는 generation과 track UUID가 맞아야 반영한다.
번호판은 얼굴 비교 대상에서 제외하고 계속 보호한다.

bbox 연속성은 신원 증명이 아니다. 급격한 장면 전환이나 교차에서 다른 사람에게 캐시가
이어질 가능성과 모델 오인식을 실제 다인 장면에서 확인해야 한다. 보호 정확도를 보장하는
구현으로 해석하지 않는다. 카메라 preview의 처리 시간에는 별도 큐의 얼굴 인식 시간이
합산되지 않으며 UI가 얼굴 인식 시간을 별도로 표시한다.

### 2026-09-21 iPhone 16·iOS 27 실측

서명한 Debug 앱을 Swift `-O`·wholemodule로 빌드하고 실제 iPhone 16에 설치했다.
`--face-model-benchmark` 실행 인자로 카메라 없는 전용 화면에서 실제 모델의 합성 RGB
입력과 고정 landmark를 처리했다. 설정별 10회 중 앞 2회를 제외한 8회 통계다.
원본·반전 두 입력의 fusion까지 포함한다.

| Core ML 설정 | 중앙 추론 시간 | 모델 로드 시간 | 추론 시 앱 physical footprint 최대 |
| --- | ---: | ---: | ---: |
| `.all` | 204.77ms | 4,253.96ms | 1,027.43MB |
| `.cpuAndNeuralEngine` | 317.41ms | 35,414.88ms | 714.49MB |

실험 후 앱 기본값은 `.all`을 사용한다. 연산별 CPU·GPU·Neural Engine 배치를 직접
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
