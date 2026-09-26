# 온디바이스 등록 얼굴 예외 캐시

2026-09-26 사용자 승인 정책. Android와 iOS의 미디어·얼굴 인식 동작에 적용한다.
API·시그널링 및 등록 데이터 형식 변경은 없다. 기존 Draft PR #324 / 이슈 #320의 후속 변경이다.

## 공통 기준

| 항목 | 정책 |
| --- | --- |
| 예외 시작 | 새 트랙 또는 등록자 변경 시 같은 등록자를 두 번 연속 확인 |
| 매칭 | cosine 0.60 이상, 1·2위 차이 0.08 이상 |
| 예외 만료 | 마지막 유효 결과의 프레임 시각 + 500ms. 인식 완료 시각으로 계산하지 않음 |
| 재확인 | 대상마다 최소 250ms 간격. 단일 작업자가 비어 있을 때 오래 대기한 대상부터 처리 |
| 일시적인 샘플 없음 | 기존 예외를 기존 기한까지만 유지. 새 예외를 허용하거나 기한을 연장하지 않음 |
| 미등록·다른 등록자 결과 | 기존 예외 즉시 취소. 새 등록자도 다시 두 번 확인 |
| 오래된 결과 | 프레임 시각부터 500ms 이상 늦은 결과로 예외를 갱신하지 않음 |
| 중복·역순 결과 | 확인 횟수 증가나 캐시 연장에 사용하지 않음 |
| 트랙 연속성 | bbox IoU 0.50 이상인 유일한 대응. 얼굴끼리 IoU 0.05 초과 겹침·모호한 대응·소실은 취소 |
| 프레임 시간 | 역행·중복·유한하지 않은 시각 또는 500ms 이상 프레임 간격이면 트랙 초기화 |
| 중지·카메라·처리 세대·등록 목록 변경 | 예외와 이전 작업 결과 무효화 |
| 동일 등록자가 여러 트랙에서 확인됨 | 대응이 모호하므로 모든 해당 트랙을 블러 |
| 번호판 | 얼굴 예외와 관계없이 보호 유지 |

두 플랫폼 모두 얼굴 인식은 별도 작업자로 실행하고 송출이 결과를 기다리지 않는다.
작업과 결과는 각각 하나만 유지하며 입력 프레임이 처리보다 빠르더라도 작업을 쌓지 않는다.
250ms는 최소 재확인 간격이며 실제 인식 속도·여러 얼굴·작업자 사용 상태에 따라 더 늦을 수 있다.
재확인이 끝나기 전에 캐시가 만료되면 등록자도 블러될 수 있다.

Android는 YOLO 전에 직전 위치로 현재 픽셀 crop을 복사해 작업을 시작하며,
실제 인식된 얼굴 bbox와 현재 트랙의 대응도 확인한다. iOS는 해당 프레임 YOLO 결과의
위치에서 crop을 생성한다. 모델·플랫폼 처리 구조는 유지하고 예외 정책을 맞췄다.

## 허용하는 한계

현재 프레임을 매번 확인하는 기존 Android 정책은 이 캐시 정책으로 대체했다.
bbox 연속성은 신원 증명이 아니므로 등록자 자리로 다른 사람이 들어오면 새 결과가
오거나 기존 기한이 만료될 때까지 예외가 이어질 수 있다. 예외 기한은 마지막 유효
프레임 시각부터 최대 500ms이며, 사람 교체 시점부터 새로 500ms를 부여하지 않는다.
미등록자의 한 프레임도 노출되지 않는다는 보장을 제공하지 않는다.

500ms는 실기기 평가를 시작할 설정값이다. 다인 교차·동일 위치 교체·가림·빠른 이동,
CPU 대체 경로·발열 시 등록자 블러 깜빡임과 교체 후 취소 지연을 측정한 뒤 조정한다.
이번 변경으로 실제 방송 FPS 개선이나 개인정보 보호 정확도를 보장하지 않는다.

## 검증

- Android 전체 단위 테스트 176개, Debug 빌드 통과.
- SM-S931N(Android 16) 비동기 coordinator 6개·crop 생명주기 2개 테스트 통과.
- iPhone 17 시뮬레이터(iOS 26.3.1) 앱 빌드 및 얼굴 정책 22개·YuNet 8개 테스트 통과.
  선택한 31개 중 1개는 public-image fixture/생성 Core ML 모델이 없어 건너뛰었다.
- 기존 YuNet 랜드마크 식의 Swift type-check 시간 초과를 동일한 Float 계산의 작은
  식으로 분리했다. 서로 다른 행·열과 다섯 랜드마크 offset을 검증하는 수치 테스트를 추가했다.
- 변경 전 iOS Tracking 소스로 500ms 만료·350ms 프레임 연속성·중복 결과 거부 검사를
  실행하면 3개 모두 실패하며, 변경 후 같은 검사는 모두 통과했다.
- 실제 서버 API 변경이 없어 HTTP 요청은 추가하지 않았다. 실제 모델의 기기 얼굴
  교체 장면·방송 수신·FPS·발열은 이번 상태 로직 테스트에 포함하지 않았다.

테스트는 실제 Tracking 상태 로직과
Android coordinator·Bitmap을 사용해 지연 결과 재사용, 프레임 기준 만료, 250ms 재확인,
인식 불일치·기하학 모호성·reset·오래된 결과·중복 결과를 확인한다.

- Android: `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline`
- Android 실기기: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.framework.innolive.feature.live.privacy.PrivacyFaceAsyncDeviceTest,com.framework.innolive.feature.live.privacy.PrivacyFaceCropDeviceTest --offline`
- iOS: `xcodebuild -project InnoLive.xcodeproj -scheme InnoLive -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:InnoLiveTests/PrivacyFaceTests -only-testing:InnoLiveTests/PrivacyYuNetTests -parallel-testing-enabled NO test`
