# Android 코드 품질 리팩터링 감사와 구현 결과

기준선 작성일: 2026-09-02

구현 확인일: 2026-09-03

## 문서 상태

`apps/android`의 구현 전 기준선, 리팩터링 계획, 현재 작업 트리와 검증 한계를 기록한다.

2026-09-02 기준선에서 production Kotlin 파일 45개, 4,594줄을 확인했다. JVM unit test와 Android instrumented test는 각각 7개 파일이었고, 두 영역에 `@Test`가 21개 있었다. 이 수치는 구현 전 기준선으로 유지한다.

2026-09-03 현재 작업 트리의 규모는 다음과 같다.

| 구분 | 기준선 | 현재 |
| --- | ---: | ---: |
| production Kotlin | 45개 파일, 4,594줄 | 54개 파일, 5,616줄 |
| JVM unit test | 7개 파일 | 12개 파일, `@Test` 32개 |
| Android instrumented test | 7개 파일 | 7개 파일, `@Test` 11개 |

현재 diff에는 인증 session repository와 Activity-scoped ViewModel, Live 화면의 순수 presentation·validator, permission lifecycle, YouTube operation generation, 오디오 장치 hot-plug 감시, WebRTC 종료 제어와 signaling 검증, 관련 test source가 들어 있다. Android SDK가 없어서 Kotlin source compilation과 test 실행까지 도달하지 못했다.

작업 트리의 `README.md`, iOS, macOS, `.idea/`, `docs/ios-code-quality-refactor.md` 변경은 이 문서의 조사·수정·검증 범위에서 제외했다.

## 대상과 경계

| 항목 | 내용 |
| --- | --- |
| 대상 앱 | `apps/android` |
| 기능 경계 | Compose navigation·화면 상태, Google 인증, Android Keystore, 카메라·마이크, 오디오 입력, HTTP, YouTube 계정 연결·방송 수명주기, WebRTC signaling·media |
| 계약 영향 | 없음. HTTP path·method·header·payload field와 signaling v2 field를 유지했다. |
| background 정책 | 기존 동작을 유지했다. 화면 이탈 시 연결 종료 또는 foreground service 도입은 제품 결정 후 별도 작업에서 다룬다. |
| 검증 경로 | JVM unit test, debug build·lint, Android instrumented test, 실제 기기 수동 검증 |

### 구현 범위

- `AuthenticationSessionRepository`가 현재 session과 refresh 작업을 소유한다.
- repository가 session을 `StateFlow`로 제공하고 동시 refresh를 한 작업으로 합치며 generation으로 이전 응답의 저장을 막는다.
- `AuthenticationSessionViewModel`이 repository를 Activity configuration 재생성 사이에 유지한다.
- Keystore session 저장소가 읽을 수 없는 값을 지우며 backup과 device transfer에서 해당 SharedPreferences를 제외한다.
- Live 화면의 파생 상태와 YouTube 설정 검증을 순수 함수로 옮긴다.
- 카메라·마이크 권한을 lifecycle resume 시점에 다시 읽는다.
- YouTube load·connect에 operation generation을 적용하고 외부 권한 요청 상태를 `rememberSaveable`로 보존한다.
- `AudioInputDeviceMonitor`가 입력 장치 추가·제거를 감시하고 제거된 선택값을 사용 가능한 장치로 바꾼다.
- WebRTC 생성, signaling callback, 방송 명령, timer, teardown을 한 owner executor로 직렬화하고 close 시 대기·HTTP 요청을 중단한다.
- ViewModel generation이 이전 연결 callback을 버리고, 이전 연결 teardown이 끝난 뒤 재시작한다.
- signaling v2 answer·ICE acknowledgement·error의 허용 key, session ID, Boolean type, `details` type과 필수 field를 검사한다.
- 변경한 순수 로직과 인증·signaling 경계에 JVM test를 추가한다.

### 비범위

- HTTP endpoint, method, header, JSON field 변경
- signaling v2 field와 message 의미 변경
- codec 우선순위와 SDP 정책 변경
- background 방송 지원, foreground service 추가, 화면 이탈 시 자동 종료
- 자동 reconnect와 network handoff 정책 추가
- UI 디자인 개편과 새 방송 플랫폼 구현
- iOS, macOS, Windows, Web, 서버 코드 수정

계약 변경이 필요하면 별도 작업에서 `contracts/` 문서, schema, fixture, 호환성 설명을 함께 수정한다.

## 구현 전 기준선

### 구조와 규모

| 영역 | 파일 수 | Kotlin 줄 수 | 기준선 역할 |
| --- | ---: | ---: | --- |
| `app` | 1 | 638 | Activity, navigation, 인증 session, YouTube 계정, 기기·방송 설정 조합 |
| `feature/live` | 20 | 2,593 | 방송 화면, CameraX, 오디오 route, WebRTC, 방송 API |
| `feature/login` | 5 | 472 | 로그인 UI, Google Credential Manager, 인증 API, Keystore session |
| `feature/settings` | 9 | 491 | 카메라·오디오·방송 설정 화면과 선택 UI |
| `feature/youtube` | 6 | 245 | YouTube OAuth 권한, 연결 API, 계정 조회 |
| `ui`, `utils` | 4 | 155 | theme, font 유틸리티 |

기준선의 `MainActivity.kt`는 638줄이었고 `AppNavigation`이 500줄을 넘었다. `WebRtcConnection.kt`는 920줄이었으며 HTTP session, signaling, PeerConnection, 오디오 route, 방송 명령, native resource 해제를 한 클래스에서 처리했다. `WebRtcSessionViewModel.kt`는 117줄이었고 화면 상태와 concrete connection을 함께 노출했다.

### 감사 결과와 현재 처리 상태

| 번호 | 기준선 발견 사항 | 현재 상태 |
| ---: | --- | --- |
| 1 | `AppNavigation`이 navigation, session, YouTube 계정, 기기·방송 설정을 함께 소유 | 일부 처리. session과 refresh는 Activity-scoped ViewModel·repository로 옮겼지만 YouTube 표시 상태, 기기 조회, draft는 남아 있다. |
| 2 | 여러 coroutine이 같은 refresh token으로 요청하고 늦은 응답이 새 session을 덮을 수 있음 | 구현. `StateFlow`, active refresh 공유와 session generation 검사를 추가했다. |
| 3 | YouTube account load와 connect가 서로의 결과를 덮을 수 있고 logout이 표시 상태를 남김 | 구현. load·connect operation generation, logout invalidation, saveable 외부 권한 요청 상태와 stale callback 검사를 추가했다. 실제 계정과 Activity 재생성 경로는 실행하지 못했다. |
| 4 | ViewModel이 화면에 concrete `WebRtcConnection`을 노출 | 구현. connection은 private이며 화면은 ViewModel의 media 상태를 읽는다. 단일 immutable UI state 전환은 남아 있다. |
| 5 | start, fail, close가 WebRTC native resource 소유권을 경쟁 | 구현. `CloseSignal`, one-shot shutdown gate, active HTTP cancellation, release guard와 close callback 완료 처리를 추가했다. 실제 SDK race 검증은 실행하지 못했다. |
| 6 | Activity background 연결 정책이 없음 | 유지. 이번 리팩터링은 background 동작을 바꾸지 않았다. |
| 7 | 인증, YouTube, WebRTC가 서로 다른 HTTP 구현을 사용 | 미구현. transport 통합과 endpoint adapter 분리는 남아 있다. |
| 8 | signaling parser가 v2 session 경계와 acknowledgement 필수 field를 버림 | 구현. 허용 key, expected session ID, exact Boolean, error `details` object와 answer·ICE·error 필수 field를 검사한다. |
| 9 | 방송 API 순서는 계약과 맞지만 server `broadcast_phase`를 읽지 않음 | 유지. path와 명령 순서를 바꾸지 않았으며 server phase 동기화는 남아 있다. |
| 10 | Keystore ciphertext 복원 실패 시 손상 값이 남고 backup 제외 규칙이 없음 | 구현. 손상 session을 지우고 cloud backup·device transfer에서 제외했다. |
| 11 | 오디오 기기 목록이 화면 생성 시점에 고정 | 구현. `AudioDeviceCallback` 기반 monitor와 선택 장치 제거 시 fallback을 추가했다. USB·Bluetooth runtime 검증은 남아 있다. |
| 12 | 요청 계약, refresh 경쟁, close 경합, background resource 자동 검증이 부족 | 일부 처리. refresh, presentation, validator, signaling, `CloseSignal`, `OperationGeneration` test를 추가했다. native resource race와 background·실제 기기 검증은 남아 있다. |

## 구현 결과

### 인증 session과 저장소

`AuthenticationSessionRepository`는 메모리 session, 저장소, active refresh를 소유하고 session을 `StateFlow`로 제공한다. 동시에 들어온 refresh 호출은 같은 `Deferred`를 기다린다. `save`, `reload`, `clear`, `close`는 session generation을 올리고 진행 중인 refresh를 취소한다. refresh API가 취소를 무시하고 늦게 값을 반환해도 generation과 시작 session이 다르면 저장하지 않는다.

`AuthenticationSessionViewModel`은 repository를 Activity scope로 소유한다. configuration 재생성 후에도 같은 ViewModel과 session flow를 사용하고, Activity의 ViewModel store가 사라질 때 repository를 닫는다. `AuthenticatedNavigationTest`는 저장한 session을 ViewModel로 reload한 뒤 `scenario.recreate()`를 실행하고 Live 화면과 logout 이동을 확인하도록 바뀌었다. test source만 갱신했으며 instrumented test는 실행하지 못했다.

`GoogleSessionStore.load()`는 ciphertext 또는 IV 한쪽이 없거나 복호화·JSON 변환이 실패하면 두 값을 지운 뒤 session 없음 상태를 반환한다. `backup_rules.xml`과 `data_extraction_rules.xml`은 `innolive_google_session.xml`을 cloud backup과 device transfer에서 제외한다.

로그아웃은 WebRTC 연결, repository session, YouTube account·status·progress, navigation back stack을 정리한다.

### YouTube 비동기 작업 수명주기

`OperationGeneration`은 load·connect를 시작할 때 generation을 올린다. 완료 callback은 현재 generation과 session을 확인한 뒤 상태를 갱신한다. logout은 generation을 무효화하므로 이전 load, 외부 권한 동의와 connect 결과가 로그아웃 뒤 표시 상태를 복원하지 못한다.

외부 권한 동의 진행 여부, launcher로 보낸 요청의 generation, 계정 표시 상태와 현재 generation은 `rememberSaveable`로 보존한다. Activity 재생성 뒤 launcher 결과는 저장한 generation을 다시 검사한다. launcher를 호출하기 전에 Activity가 재생성된 진행 상태는 취소하고 재시도 문구를 표시한다. 실제 Google·YouTube 계정과 Activity 재생성 조합은 실행하지 못했다.

### Live 화면과 permission lifecycle

`buildLiveScreenPresentation()`이 연결·방송 상태에서 버튼 문구, command, 활성화 여부, 상태 문구와 오류 표시를 계산한다. `validateYouTubeLiveSettings()`는 제목, 설명, 아동용 설정 오류를 계산한다. 두 함수는 Android runtime 없이 입력과 출력을 검사할 수 있다.

`MediaPermissionController`는 카메라와 마이크 권한을 읽고 `Lifecycle.Event.ON_RESUME`에 갱신한다. 사용자가 시스템 설정에서 권한을 바꾸고 화면으로 돌아오는 경로도 같은 상태를 사용한다.

`LiveScreen`은 `WebRtcConnection` 내부 field를 직접 읽지 않는다. ViewModel이 local media analyzer와 EGL context를 callback으로 받아 화면에 전달한다.

`AudioInputDeviceMonitor`는 Compose 화면이 사용하는 동안 `AudioDeviceCallback`을 등록한다. 입력 장치가 추가되거나 제거되면 목록을 다시 읽는다. 선택한 장치가 사라지면 첫 번째 사용 가능 장치로 바꾸고, 입력이 없으면 `-1`을 사용한다. USB·Bluetooth 연결과 제거는 실제 기기에서 확인해야 한다.

### WebRTC resource와 callback

`WebRtcConnection`은 `ownerExecutor`에서 native resource 생성, signaling callback, 방송 명령, audio route 처리와 teardown을 실행한다. `timerExecutor`는 연결 timeout과 audio route 확인을 예약한다. `CloseSignal`은 close가 시작되면 `broadcast_not_ready` retry 대기를 즉시 푼다. one-shot shutdown gate가 close·failure의 teardown 시작을 한 번으로 제한하고, close 시작 시 dispatcher와 추적 중인 HTTP call을 취소한다.

`resourcesReleased`는 중복 release를 막는다. teardown은 timer, media track, PeerConnection, audio module과 EGL을 해제한 뒤 session `DELETE`를 best effort로 호출한다. 각 `close(onComplete)` 호출에 등록된 callback은 teardown 뒤 한 번씩 실행하며, teardown이 끝난 뒤 등록한 callback도 즉시 실행한다. executor가 release 작업을 받지 못한 경우에도 callback 완료 처리를 수행한다.

ViewModel은 connection generation을 올려 이전 start와 callback을 무효화한다. 재시작 경로는 이전 connection의 teardown 완료를 기다린 뒤 token refresh와 새 connection 생성을 진행한다. local media ready·clear callback도 generation을 확인한다.

기존 `broadcast_not_ready` retry 횟수와 간격, prepare·golive·stop 상태 전이를 유지했다. retry sleeper 주입과 native resource test double은 구현하지 않았다.

### signaling v2 검증

`SignalingMessageCodec`은 root와 error object의 허용 key를 제한한다. answer의 `session_id`·`sdp`, ICE acknowledgement의 `session_id`·`end_of_candidates`·`queued`·ICE 상태, error의 `code`·`message`를 검사한다. `end_of_candidates`와 `queued`는 exact Boolean이어야 하고, error `details`가 있으면 object여야 한다. answer 또는 ICE acknowledgement가 현재 session과 다른 ID를 보내면 연결 owner가 응답을 거부한다.

outbound offer와 ICE candidate의 `session_id`, `owner_token`, `access_token`, `sdp`, `candidate`, `sdpMid`, `sdpMLineIndex`를 유지했다. ICE 수집 완료도 기존처럼 `candidate: null`을 보낸다.

### 추가한 테스트

- `AuthenticationSessionRepositoryTest`: 동시 refresh 10회 single-flight, refresh 실패 시 session 보존, save·clear와 경쟁하는 늦은 refresh 차단, clear·reload
- `LiveScreenPresentationTest`: 버튼 action·활성화 상태, prepared·busy·failed 표시
- `YouTubeLiveSettingsValidatorTest`: 필수 입력과 유효한 설정
- `WebRtcSignalTest`: answer·ICE·error parsing, 필수 field 누락, 빈 error field, 허용하지 않는 key, exact Boolean, `details` type, session mismatch
- `CloseSignalTest`: close가 retry 대기를 즉시 해제하는 경로와 timeout
- `OperationGenerationTest`: invalidate 뒤 stale 처리와 latest-operation 판정

현재 JVM unit test에는 12개 파일과 `@Test` 32개가 있다. Android instrumented test에는 7개 파일과 `@Test` 11개가 있다. 테스트 source는 현재 작업 트리에 있지만 Android SDK가 없어 Gradle test task는 source compilation 전에 멈췄다. 테스트 통과로 기록하지 않는다.

## 계획 대비 상태

| 단계 | 상태 | 결과 |
| --- | --- | --- |
| 0. 기준선과 characterization test | 일부 완료 | 규모를 다시 측정하고 JVM unit test를 32개로 늘렸다. HTTP path·header·body 전체 characterization test는 남아 있다. |
| 1. 인증 session 경계 | 구현, 실행 검증 대기 | repository `StateFlow`·single-flight·generation guard, Activity-scoped ViewModel, 손상 session 제거와 backup 제외를 구현했다. Activity 재생성 test source도 갱신했다. |
| 2. HTTP와 signaling 경계 | 일부 완료 | signaling allowed key·type·session validation을 구현했다. 공통 HTTP transport와 endpoint adapter는 남아 있다. |
| 3. WebRTC 상태와 resource 소유권 | 일부 완료 | owner·timer executor, `CloseSignal`, one-shot shutdown, HTTP cancellation, serialized teardown, close callback과 generation을 구현했다. immutable UI state, factory 주입, native race test는 남아 있다. |
| 4. YouTube 계정 상태 | 일부 완료, 실행 검증 대기 | logout 초기화, 순수 validator, operation generation과 saveable 외부 권한 요청 상태를 구현했다. account ViewModel 분리는 남아 있다. |
| 5. navigation과 설정 상태 | 일부 완료, 실행 검증 대기 | Live 파생 상태와 오디오 hot-plug inventory·fallback을 구현했다. route rendering과 draft 분리는 남아 있다. |
| 6. 통합 검증과 background 기록 | 미완료 | Gradle help와 정적 검사는 끝냈다. SDK 기반 build·test와 실제 기기 검증은 실행하지 못했다. |

## 계약 영향

계약 영향은 없다. 리팩터링은 다음 항목을 유지한다.

- `POST /auth/google`의 `id_token`
- `POST /auth/refresh`의 `refresh_token`과 token response key
- `GET /auth/youtube/config`
- `POST /auth/youtube/connect`의 `server_auth_code`, `code_source: native`
- `GET /auth/streaming/accounts`
- `GET /webrtc/config`의 Bearer token
- session 생성·삭제와 `X-Session-Owner-Token`
- `PUT /sessions/{id}/broadcast`
- `POST /sessions/{id}/stream/prepare`
- 사용자가 라이브 시작을 누른 뒤 호출하는 `POST /sessions/{id}/stream/golive`
- `POST /sessions/{id}/stream/stop`
- signaling v2의 `session_id`, `owner_token`, `access_token`, `sdp`, `candidate`, `sdpMid`, `sdpMLineIndex`
- ICE 수집 완료의 `candidate: null`
- Opus audio codec 요구와 현재 video codec 협상 방식

parser는 유효한 v2 payload를 바꾸지 않고 server response를 더 엄격하게 검사한다. 실제 서버가 schema의 필수 field를 보내지 않으면 연결이 실패하므로 서버 응답과 contract를 실제 환경에서 대조해야 한다.

background 정책도 유지했다. 화면 이탈 시 종료, background 방송 유지, foreground service 중 하나를 선택하는 작업은 제품 요구와 Android 버전별 camera·microphone 제한을 확인한 뒤 별도로 진행한다.

## 수용 기준 결과

### 구조와 동작

| 수용 기준 | 상태 | 근거·남은 확인 |
| --- | --- | --- |
| 인증 repository가 현재 session과 active refresh를 소유 | 구현 | `AuthenticationSessionRepository`가 store, session, active `Deferred`를 소유한다. |
| 인증 session이 configuration 재생성 뒤 유지됨 | 구현, 실행 대기 | Activity-scoped `AuthenticationSessionViewModel`과 `StateFlow`를 사용하고 `AuthenticatedNavigationTest`에 `scenario.recreate()` 경로를 추가했다. instrumented test는 실행하지 못했다. |
| 동시 refresh가 한 작업을 공유하고 같은 token pair를 반환 | 구현, 실행 대기 | single-flight 코드와 10-call test를 추가했지만 test task는 실행하지 못했다. |
| 늦은 refresh가 save·clear 뒤 session을 복원하지 않음 | 구현, 실행 대기 | generation guard와 경쟁 test source를 추가했다. |
| logout이 session, YouTube 표시, WebRTC를 정리 | 구현, 기기 확인 대기 | logout command가 세 상태를 함께 초기화한다. |
| 늦은 YouTube load·connect·외부 권한 callback이 최신 상태를 덮지 않음 | 구현, 실행 대기 | `OperationGeneration`, session 검사, logout invalidation과 saveable request generation을 추가했다. `OperationGenerationTest` source는 있으나 계정·Activity runtime 경로는 실행하지 못했다. |
| 화면이 concrete `WebRtcConnection`을 노출받지 않음 | 구현 | ViewModel의 connection은 private이고 media callback 상태를 화면에 전달한다. |
| WebRTC native resource 생성·해제를 한 owner에서 실행 | 구현, 기기 확인 대기 | owner executor, release-once guard와 native resource 우선 teardown을 추가했다. native SDK race는 확인하지 못했다. |
| close가 대기·HTTP 요청을 중단하고 완료 callback을 teardown 뒤 호출 | 구현, 실행 대기 | `CloseSignal`, active call cancellation, one-shot shutdown gate와 callback 목록 처리를 추가했다. `CloseSignalTest`는 source만 추가했다. |
| stop·close 뒤 이전 callback이 새 연결을 덮지 않음 | 구현, 실행 대기 | ViewModel generation과 callback 검사를 추가했다. teardown과 새 연결 경합은 실행하지 못했다. |
| 방송 prepare와 golive를 별도 사용자 command로 유지 | 유지 | 기존 API 순서와 action 분기를 유지했다. |
| `broadcast_not_ready` 뒤 prepared 상태를 유지 | 유지, 실행 대기 | 기존 retry와 state 처리 코드를 유지했다. |
| signaling v2 허용 key, session, Boolean, error field 검사 | 구현, 실행 대기 | strict codec과 관련 JVM test source를 추가했다. |
| 권한 변경을 화면 복귀 시 반영 | 구현, 기기 확인 대기 | `ON_RESUME`에서 permission state를 갱신한다. |
| 선택 오디오 장치 제거 시 fallback 갱신 | 구현, 기기 확인 대기 | `AudioInputDeviceMonitor`가 hot-plug callback 후 목록을 갱신하고 사용 가능한 입력으로 선택값을 바꾼다. USB·Bluetooth runtime 검증은 실행하지 못했다. |
| background 진입·복귀 동작이 기준선과 같음 | 정책 유지, 미검증 | 관련 lifecycle 정책을 바꾸지 않았고 실제 기기 관찰은 실행하지 않았다. |

### 품질과 보안

| 수용 기준 | 상태 | 근거·남은 확인 |
| --- | --- | --- |
| JVM unit test 통과 | 검증 불가 | SDK location 오류로 source compilation 전에 중단됐다. |
| debug build 통과 | 검증 불가 | SDK location 오류로 source compilation 전에 중단됐다. |
| lint 통과 | 검증 불가 | SDK location 오류로 source compilation 전에 중단됐다. |
| instrumented test 통과 | 미실행 | SDK, emulator 또는 실제 기기가 필요하다. |
| resource race test가 release 1회를 검사 | 미구현 | release guard는 있으나 native resource test double과 race test가 없다. |
| 실제 token을 fixture와 로그에 남기지 않음 | 정적 확인 | 추가 test는 임의 문자열만 사용한다. runtime log는 기기 검증이 남아 있다. |
| 인증 SharedPreferences를 backup·transfer에서 제외 | 구현, 복원 검증 대기 | 두 XML rule에 `innolive_google_session.xml` 제외를 추가했다. |
| HTTP·signaling wire contract 유지 | 정적 확인 | path·method·header·payload field를 바꾸지 않았다. 서버 연동은 미검증이다. |
| Android 밖의 사용자 변경을 수정하지 않음 | 충족 | 이 문서 작업은 Android 외 변경을 제외했다. |

## 2026-09-03 검증 결과

검증 환경에는 Android SDK, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `apps/android/InnoLive/local.properties`가 없었다.

| 검증 항목 | 상태 | 결과·근거 |
| --- | --- | --- |
| `./gradlew help` | 통과 | `apps/android/InnoLive`에서 Gradle 구동과 project configuration을 확인했다. |
| `./gradlew testDebugUnitTest` | 환경 차단 | `SDK location not found`로 source compilation 전에 중단됐다. |
| `./gradlew lintDebug` | 환경 차단 | `SDK location not found`로 source compilation 전에 중단됐다. |
| `./gradlew assembleDebug` | 환경 차단 | `SDK location not found`로 source compilation 전에 중단됐다. |
| `git diff --check -- apps/android` | 통과 | Android diff의 whitespace 오류가 없었다. |
| Android XML 14개 `xmllint` | 통과 | `apps/android/InnoLive/app/src/main`의 XML 14개를 검사했다. |
| `connectedDebugAndroidTest` | 미실행 | SDK와 연결 기기가 없다. |
| Google 로그인·refresh | 미실행 | 계정과 실제 기기 검증이 필요하다. |
| YouTube 연결·prepare·golive·stop | 미실행 | 계정, 서버와 실제 기기 검증이 필요하다. |
| WebRTC·TURN·다른 네트워크 E2E | 미실행 | 실제 기기, 서버와 네트워크 검증이 필요하다. |
| 화면 회전·Activity 재생성 | 미실행 | 실제 기기 또는 emulator가 필요하다. |
| background·foreground | 미실행 | 실제 기기에서 camera·microphone·socket 상태를 기록해야 한다. |
| USB·Bluetooth 입력 전환 | 미실행 | 해당 장치와 실제 기기가 필요하다. |
| backup·restore와 손상 session 복구 | 미실행 | 기기·OS별 backup 동작과 Keystore 상태를 확인해야 한다. |

세 명령은 Android SDK 경로를 찾지 못해 Kotlin source를 compile하지 않았다. 현재 결과로는 코드 상태를 판정할 수 없다. SDK를 설정한 환경에서 `testDebugUnitTest`, `lintDebug`, `assembleDebug`를 다시 실행해야 한다.

## 재검증 명령

```bash
cd apps/android/InnoLive
./gradlew help
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
adb devices
./gradlew connectedDebugAndroidTest
```

정적 검사는 저장소 root에서 실행한다.

```bash
git diff --check -- apps/android
find apps/android/InnoLive/app/src/main -name '*.xml' -type f -print0 \
  | xargs -0 -n1 xmllint --noout
```

## 실제 기기 검증 항목

1. 카메라와 마이크 권한을 허용하고 비식별화 WebRTC 연결을 시작·종료한다.
2. 연결 시작 직후 종료를 반복하고 camera, microphone, Bluetooth route가 남지 않는지 확인한다.
3. 화면을 회전한 뒤 local preview, remote preview, 방송 상태를 확인한다.
4. USB와 Bluetooth 오디오 장치를 연결·제거하고 option과 실제 입력 route를 확인한다.
5. Google 로그인 후 여러 기능이 access token을 요구하게 만들고 refresh 요청 수와 token rotation을 서버 로그에서 확인한다.
6. YouTube 계정을 연결하고 앱의 channel 표시와 reconnect 상태를 확인한다.
7. 방송 설정 저장, prepare, golive, stop을 실행한다. 민감값을 가린 서버 로그에서 session ID와 owner token 사용을 확인한다.
8. Wi-Fi, 다른 Wi-Fi, mobile hotspot에서 WebRTC offer·answer·ICE, TURN relay와 remote processed video를 확인한다.
9. 앱을 홈으로 보내고 복귀해 camera, microphone, WebSocket의 기준선 동작을 기록한다.
10. logout 뒤 session, YouTube channel 표시, camera, audio, WebRTC 연결이 남지 않는지 확인한다.

## 남은 위험

- Android SDK가 없어 Kotlin compile, JVM test, lint, APK build를 확인하지 못했다. source-level 오류와 Android API 호환성은 남아 있다.
- `WebRtcConnection`은 owner executor, one-shot shutdown gate와 release guard를 사용하지만 native resource race를 test double로 검사하지 않았다. start·fail·close 반복과 release 횟수를 실제 기기에서 확인해야 한다.
- timer callback은 owner executor로 다시 들어가지만 timer executor 종료와 callback enqueue가 겹치는 경로를 실행 검증하지 않았다.
- signaling parser는 v2 필수 field를 엄격하게 검사한다. 실제 서버가 `session_id`, acknowledgement 상태 또는 error field를 빠뜨리면 연결이 실패한다.
- YouTube generation과 saveable 외부 권한 요청 상태는 source에 들어 있지만 실제 계정, logout 직전 callback과 Activity 재생성 경합을 실행하지 않았다.
- 오디오 hot-plug과 fallback은 source에 들어 있지만 USB·Bluetooth 장치 추가·제거 뒤 option, 선택값과 실제 입력 route를 기기에서 확인하지 않았다.
- 공통 HTTP transport와 endpoint adapter를 만들지 않았다. timeout, redirect, cancellation과 wire contract를 endpoint별 test로 고정해야 한다.
- session cleanup `DELETE`는 native resource 해제 뒤 실행하는 best-effort 요청이다. 네트워크 오류나 close 경합이 발생하면 서버 session 정리를 보장하지 않는다.
- 서버 `broadcast_phase`를 읽지 않아 앱 상태가 서버의 최종 방송 단계와 달라질 수 있다.
- 화면 상태를 하나의 immutable UI state로 통합하지 않았다. `AppNavigation`과 ViewModel에 분산된 상태 조합은 남아 있다.
- background 정책이 없다. 이번 구현은 기존 동작을 유지했으며 background 방송 지원을 완료하지 않았다.
- 실제 Google·YouTube 계정, WebRTC 서버, TURN relay, Bluetooth와 다른 네트워크를 사용하기 전에는 방송 E2E를 완료로 처리할 수 없다.
