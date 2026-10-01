# Android 세션 상태 동기화

Android는 `SessionSnapshot`으로 서버가 확인한 송출 대상, 알림, 남은 시간을 보관한다.
요청·signaling 계약은 바꾸지 않는다. 응답 형태는
[broadcast-session-state-v1.md](../contracts/api/broadcast-session-state-v1.md)를 따른다.
기준 서버 소스는 `eed7bcfa963ea9c673de0621db31ee0fa2f7af87`이다.

## 응답 처리

- 세션 생성, 방송 설정·준비·시작·일시정지·재개·종료, 비식별화 변경 응답은 공통 파서를 거친다.
- `targets[]`가 있으면 최상위 `stream`보다 우선한다. 명시적 빈 배열은 대상 없음이다.
- `targets`가 없는 전체 응답은 기존 `stream`을 기본 대상의 상태로 읽는다.
- 대상별 부분 응답은 기본 대상만 갱신하고 다른 대상, 알림, 남은 시간을 보존한다.
- `visibleTargets`는 `broadcast_phase == idle`인 대상을 숨긴다. 원본 대상에는 종료 사유를 유지한다.
- 알 수 없는 플랫폼·상태·알림 코드는 원문을 보존한다. 잘못된 응답과 다른 세션의 응답은 적용하지 않는다.
- 남은 시간은 미확인, 무제한 또는 비방송(`null`), 잔여 초를 구분한다. 누락과 잘못된 타입은 마지막 확인값을 보존한다.
- `notices`의 서버 `null`은 빈 목록이다. 누락은 마지막 확인값을 보존한다.

## 조회 수명주기와 요청 순서

`WebRtcConnection`은 초기 연결 설정 이후 세션을 조회한다. HTTP 조회 완료 뒤 2초를
기다리며, 조회 요청은 중첩하지 않는다. 각 조회의 HTTP 제한 시간은 3초이다.
방송 제어 요청 중에는 새 조회를 시작하지 않는다. 제어 요청 수락·세션 변경 요청마다
revision을 증가시켜, 그 전에 시작한 GET과 지연된 화면 콜백이 최신 결과를 덮어쓰지 않게 한다.

성공한 변경 API의 확인 상태를 기존 방송 상태보다 우선한다. 알 수 없는 상태가 실린
응답은 준비·시작 완료로 단정하지 않는다. 방송 상태를 주지 않는 이전 서버의 성공
응답은 기존 클라이언트 전이를 유지한다. 같은 조회 결과를 다시 전달할 수 있으므로
ViewModel은 상태가 그대로인 조회로 기존 오류 안내를 지우지 않는다.

일시적인 HTTP 오류는 마지막 확인값을 보존하고 다음 주기에 재조회한다. 401은 기존
토큰 갱신 콜백으로 한 번 갱신·재시도한다. 갱신 실패, 재시도 401, 403, 404, 410은 기존
연결 실패·리소스 정리 경로로 종료한다. 새 로그인 화면이나 인증 흐름은 추가하지 않는다.
세션 종료와 실패 시 조회 Job과 HTTP 요청을 취소한다. ViewModel은 기존 generation
검증으로 이전 연결 콜백을 무시하고 새 연결·종료·실패에서 snapshot을 비운다.

## 기존 화면과의 연결

현재 방송 버튼은 기본 대상만 제어한다. 기본 대상의 `broadcast_phase`와 `stream.status`로
기존 `BroadcastState`를 갱신한다. 라이브 중 `paused_reconnecting`과 `paused_reconfiguring`은
일시정지 의도를 유지한다. 서버 종료는 기존 화면 방향 잠금·방송 경과 시간 해제에도 반영한다.
다른 대상의 상태를 합산하여 기존 단일 대상 버튼을 동시 송출 버튼으로 바꾸지 않는다.

이번 범위에는 대상 목록 UI, 알림 배너·중복 표시 방지, 남은 시간 표시,
치지직 연결·동시 송출 제어, 해상도 전환·업그레이드 제안 UI를 포함하지 않는다.
후속 화면은 `WebRtcSessionViewModel.sessionSnapshot`을 사용한다.

## 검증 범위

서버 공통 fixture를 이용한 파싱 테스트, Android 단위 테스트, 에뮬레이터의
`BroadcastApiFlowTest`를 사용한다. API 테스트는 외부 네트워크·계정·미디어를 사용하지 않는다.
늦은 GET, 부분 응답, 서버 종료, 일시 오류, 인증 재시도, 세션 소멸, 종료 후 취소를 확인한다.

2026-09-30 초기 검증에서는 NDK·CMake가 없어 임시 Gradle init script로 CMake 경로를
제외했다. 이후 NDK 27.0.12077973과 CMake 3.22.1이 설치된 환경에서 init script 없이
`assembleDebug`와 `assembleDebugAndroidTest`를 성공했다. 프로젝트 C++ 라이브러리를
포함한 APK로 Samsung SM-G981N 실기기 검증을 수행했다.

- `BroadcastLiveDeviceTest`: 실제 로그인·카메라와 비공개 YouTube 방송을 사용했다.
  준비 → 시작 → 서버 API에서 직접 일시정지 → 앱 주기 조회로 PAUSED 반영 → 앱 재개 →
  비식별화 On/Off → 종료 → 재연결을 통과했다. 준비·라이브·일시정지·종료에서 앱 snapshot의
  대상 상태·종료 사유·재접속 횟수·알림·남은 시간을 실제 GET 응답과 대조했다.
- `ProductionVideoReturnDeviceTest`: 서버 AI 처리 모드에서 실제 카메라의 H.264 업링크·반환
  코덱과 반환 프레임 수신을 통과했다. 약 30초에 879프레임, 평균 29.26fps였으며
  250ms 초과 프레임 간격은 1회(최대 약 359ms)였다. 성능 보장이나 장시간 안정성 검증은 아니다.

위 테스트는 실제 서버를 사용하는 실기기 instrumentation이다. 앱의 전체 화면 버튼 탐색,
외부 YouTube 시청자 화면 재생, 한도 알림 발생, 실제 잔여 한도 소진·자동 종료는 검증하지 않았다.
인증 실패·경합·알 수 없는 상태 등은 별도 mock API 및 단위 테스트 근거를 사용한다.

재현할 때 기존 앱 로그인과 연결된 YouTube 계정이 필요하다. 로컬 설정을 준비하고 APK를
데이터 보존 방식으로 설치한 후 다음 명령을 실행한다. 첫 테스트는 실제 비공개 방송을
생성·시작·종료하므로 테스트 계정에서만 명시적으로 실행한다.

```sh
adb shell am instrument -w -r \
  -e class com.framework.innolive.feature.live.BroadcastLiveDeviceTest \
  -e liveBroadcastLifecycle true \
  com.framework.innolive.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r \
  -e class com.framework.innolive.feature.live.ProductionVideoReturnDeviceTest \
  -e productionProbe true -e onDevice false -e expectedCodec H264 -e durationSeconds 30 \
  com.framework.innolive.test/androidx.test.runner.AndroidJUnitRunner
```
