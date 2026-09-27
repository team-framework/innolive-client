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

## 다음 묶음

나머지 33개 후보의 구현·실험·판정은 진행 중이다. 이전 audit의 조건부 후보를
실행 성공만으로 완료 처리하지 않는다.
