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

다중 등록·삭제, 로컬 저장, 비동기 얼굴 비교와 기기 검증 결과를 이어서 기록한다.
