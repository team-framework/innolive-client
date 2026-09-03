# iOS 코드 품질 리팩터링

작성 기준: 2026-09-03

## 상태

대상은 `apps/ios`다. HTTP와 WebRTC signaling 계약은 변경하지 않았다.

복구 clone의 현재 코드에는 리팩터링 구현과 `InnoLiveTests`가 들어 있다. Auth·reconnect·얼굴 등록 수명주기 경쟁 조건을 포함한 최신 코드에서 테스트 21개, Debug·Release build, analyze가 통과했다.

## 이력과 현재 기준선

### 초기 조사 이력

공유 workspace reset 전 초기 조사에서 production Swift 소스 39개, 7,147줄을 확인했다. 당시 Xcode project에는 `InnoLive` 앱 타깃만 있었고 test 타깃은 없었다. 주요 집중 지점은 다음과 같았다.

- `AuthSession.swift`가 화면 상태, 인증 API, server URL 설정, Keychain 저장을 함께 소유했다.
- `YouTubeIntegration.swift`가 상태 해석, 설정 저장, polling, WebRTC reconnect, 방송 수명주기를 함께 소유했다.
- `FaceManagementView.swift` 한 파일이 관리 화면, capture 화면, WebRTC preview bridge, 날짜 parsing을 포함했다.
- WebRTC signaling URL 생성이 `URLComponents`와 결과 URL을 강제 unwrap했다.

초기 workspace의 실행 기록은 복구 clone의 최종 증거로 사용하지 않는다.

### 복구 clone 현재 상태

| 항목 | 현재 값 |
| --- | ---: |
| production Swift 파일 | 47개 |
| production Swift 줄 수 | 7,625줄 |
| test 파일 | 5개 |
| test 줄 수 | 563줄 |
| test 메서드 | 21개 |
| Xcode target | `InnoLive`, `InnoLiveTests` |
| 공유 scheme | `InnoLive.xcscheme` |

표의 수치는 복구 clone에서 `apps/ios/InnoLive/InnoLive`와 `apps/ios/InnoLive/InnoLiveTests`를 직접 집계한 결과다.

## 범위

- Auth API, 환경 설정, token 저장 책임 분리
- 인증 의존성 주입, refresh 공유, sign-out 경쟁 조건 test
- YouTube 상태 타입과 상태 정책 분리
- YouTube 표시 정보와 방송 설정 저장소 분리
- reconnect 취소, signaling unauthorized refresh, refresh 불가 상태 처리
- 얼굴 등록 task 취소, lifecycle session 격리, stale 결과 차단
- 얼굴 관리 화면 파일 분리와 날짜 formatter 재사용
- WebRTC signaling URL의 안전한 실패 처리
- iOS unit test target과 공유 scheme 추가

## 비범위와 미완료

- `images`와 `image` 중 얼굴 등록 multipart field 결정
- CHZZK·SOOP OAuth, 저장, API, session, 실제 송출 구현
- Release에서 `http` server URL을 허용할지에 대한 정책 결정
- Home·Settings의 camera, audio, resolution preferences 추상화
- YouTube API, Reference Face API, WebSocket의 공통 HTTP transport 도입
- signaling v2의 end-of-candidates와 `ice_candidate_added` 처리 확대
- 실제 기기 OAuth, 카메라·마이크, TURN, cellular handoff, YouTube E2E
- 다른 플랫폼과 서버 코드 수정

## 구현 결과

### 1. 인증 책임 분리와 주입 경계

`apps/ios/InnoLive/InnoLive/Features/Auth/AuthSession.swift`는 화면 상태와 인증 흐름을 유지한다. 다음 구현을 별도 파일로 옮겼다.

- `AuthenticationAPI.swift`: 인증 endpoint, request DTO, response DTO, 오류 변환
- `AuthenticationConfiguration.swift`: process environment와 Info.plist 기반 server URL 생성
- `AuthenticationTokenStore.swift`: Keychain token pair 저장, 조회, 삭제

`AuthSession`은 `AuthenticationAPIClient`와 `AuthenticationTokenStoring`을 initializer로 받는다. production 기본 구현을 제공하면서 unit test가 fake API와 in-memory token store를 주입할 수 있다.

### 2. Keychain update-or-add

`AuthenticationTokenStore.save`는 기존 item을 먼저 삭제하지 않는다. `SecItemUpdate`를 호출하고 `errSecItemNotFound`일 때만 `SecItemAdd`를 호출한다. update 또는 add가 실패하면 `AuthenticationError.storage`를 반환한다.

이 변경은 새 token pair 저장 실패가 기존 token을 먼저 지우던 위험을 줄인다. 실제 Keychain 실패 주입 test는 아직 없다. `AuthSessionRefreshTests`는 test token store를 사용해 refresh 실패 시 기존 token 보존 정책을 검증한다.

### 3. Auth refresh 특성화 테스트

`apps/ios/InnoLive/InnoLiveTests/AuthSessionRefreshTests.swift`는 다음 동작을 검사한다.

- 동시 refresh 요청이 API 호출 한 번을 공유한다.
- refresh 성공 시 회전된 access token과 refresh token을 저장한다.
- `invalid_refresh_token`은 `.invalid`를 반환한다.
- transport와 token store 실패는 `.unavailable`을 반환하고 기존 token을 보존한다.
- refresh 응답 대기 중 sign-out하면 늦게 도착한 token을 저장하지 않는다.

`AuthSessionTests.swift`는 이메일 정규화, 이메일 형식, 비밀번호 UTF-8 byte 경계를 검사한다.

`AuthSession`은 인증 상태가 바뀔 때 `sessionGeneration`을 올리고 진행 중 refresh task를 취소한다. refresh 완료 시 generation과 취소 상태를 다시 검사해 sign-out이나 session 만료 뒤 token이 복원되는 경쟁 조건을 막는다.

### 4. YouTube 상태 타입과 정책

`YouTubeModels.swift`에 다음 raw-value 타입을 추가했다.

- `YouTubeBroadcastPhase`
- `YouTubeStreamStatus`
- `YouTubeVideoTrackReadyState`

각 타입은 알려진 값을 case로 제공하고 새 서버 값은 `unknown(String)`으로 보존한다. wire model의 `String` 필드와 CodingKeys는 유지해 JSON 계약을 바꾸지 않았다.

`YouTubeBroadcastStatePolicy.swift`는 설정 잠금, active 상태, pause·resume 허용, 상태 문구를 계산한다. `YouTubeIntegration`은 raw string 비교 대신 이 정책을 사용한다. `YouTubeBroadcastStatePolicyTests.swift`는 live, paused, stopped, unknown 상태의 fallback을 검사한다.

### 5. YouTube preferences 저장소

`YouTubePreferencesStore.swift`는 연결 표시 정보, 방송 설정, 시청자층을 주입된 `UserDefaults`에 저장한다. `YouTubeIntegration`은 저장 키와 JSON encode·decode를 직접 다루지 않는다.

저장소는 access token, refresh token, owner token을 보관하지 않는다. `YouTubePreferencesStoreTests.swift`는 독립 suite에서 연결 정보와 방송 설정 round-trip, 기본 제목·시청자층 fallback, 연결 정보 삭제를 검사한다.

Home과 Settings에는 `UserDefaults.standard` 및 `@AppStorage` 직접 접근이 남아 있다. camera, audio, resolution 설정은 후속 범위다.

### 6. WebRTC reconnect 취소와 unauthorized refresh

`YouTubeIntegration.reconnectVideoUsingExistingSession`은 취소된 sleep을 종료 경로로 처리한다. 각 network·media 단계 뒤에 `Task.isCancelled`를 확인하고 취소 후 `markReconnectFailed`를 호출하지 않는다.

signaling이 `.unauthorized`를 반환하면 기존 session을 유지한 채 인증 refresh를 한 번 시도한다. refresh 성공 후 같은 session ID와 owner token으로 reconnect를 재개한다. 일반 실패만 최대 3회 reconnect 횟수를 소비한다. refresh가 `.unavailable`이면 uplink를 실패 상태로 전환하고 사용자가 다시 시작할 수 있는 메시지를 남긴다.

이 경로를 직접 검증하는 WebRTC test double과 reconnect unit test는 아직 없다. 최종 verifier의 compile과 기존 unit test 통과만으로 실제 network 전환을 입증할 수 없다.

### 7. 얼굴 등록 task 소유와 stale 결과 차단

`FaceRegistrationViewModel`은 `registrationTask`와 화면별 `lifecycleSessionID`를 소유한다. 새 detection, retry, stop, WebRTC frame source 상실 시 `invalidateDetection`이 generation을 올리고 진행 중 등록 task를 취소한다.

카메라 준비, frame delivery 시작, API 등록 전후에 취소, generation, lifecycle session을 확인한다. 이전 화면의 `onDisappear` 정리가 새 등록 화면을 중지하지 못하며 이전 capture나 API 결과도 새 화면 상태를 덮지 못한다. frame source는 비동기 정리를 시작하기 전에 active 상태에서 분리한다.

얼굴 등록 task 전용 unit test는 아직 없다. 실제 카메라와 API 지연을 포함한 취소 검증은 남아 있다.

### 8. 얼굴 관리 UI 분리

기존 509줄 `FaceManagementView.swift`의 책임을 다음 파일로 나눴다.

- `FaceManagementView.swift`, 243줄: 상태, 목록, 삭제, 등록 sheet 진입
- `FaceRegistrationCaptureView.swift`, 194줄: capture flow와 phase UI
- `FaceRegistrationPreview.swift`, 76줄: LiveKit renderer bridge
- `FaceRegistrationDateFormatting.swift`, 25줄: ISO 8601 parsing과 표시 형식

날짜 formatter는 static instance로 재사용하며 `NSLock`으로 접근을 보호한다.

### 9. WebRTC signaling URL 안전성

`WebRTCVideoUplink+Signaling.swift`의 `signalingURL(from:)`은 `URL?`을 반환한다. `URLComponents` 생성이나 결과 URL 생성이 실패하면 nil을 반환한다.

`WebRTCVideoUplink.start`는 nil을 `URLError(.badURL)`로 변환해 기존 오류 경로로 전달한다. signaling payload와 endpoint path는 바꾸지 않았다.

### 10. 테스트 target과 공유 scheme

`apps/ios/InnoLive/InnoLive.xcodeproj/project.pbxproj`에 `InnoLiveTests` target과 앱 target dependency를 추가했다. `apps/ios/InnoLive/InnoLive.xcodeproj/xcshareddata/xcschemes/InnoLive.xcscheme`은 test action에 `InnoLiveTests.xctest`를 포함한다.

현재 test 파일은 다음 5개다.

- `AuthSessionTests.swift`
- `AuthSessionRefreshTests.swift`
- `YouTubeModelsTests.swift`
- `YouTubeBroadcastStatePolicyTests.swift`
- `YouTubePreferencesStoreTests.swift`

## 계약 영향

계약 영향은 없다.

- 인증 endpoint, HTTP method, JSON CodingKeys를 유지했다.
- session request의 `Authorization`과 `X-Session-Owner-Token` 의미를 바꾸지 않았다.
- YouTube의 `PUT /broadcast → POST /stream/prepare → POST /stream/golive` 순서를 유지했다.
- YouTube 상태는 wire에서 `String`으로 decode하고 unknown raw value를 보존한다.
- signaling endpoint와 offer·answer·ICE payload를 유지했다.
- VP8 우선 제안과 Opus 조건을 바꾸지 않았다.

`ReferenceFaceAPI.swift`는 multipart field로 `images`를 사용한다. `contracts/api/reference-face-v1.md`는 자동 단일 등록에 `image`, 다중 append에 `images`를 정의한다. 현재 UI와 서버가 append를 의도했는지 확인하기 전에는 field를 바꾸지 않는다. 이 항목은 계약 불일치 후보이며 이번 리팩터링의 완료 항목이 아니다.

## 수용 기준

### 구현 기준

- `AuthSession`이 API, configuration, Keychain query 구현을 포함하지 않는다.
- token 저장은 delete-before-add를 사용하지 않는다.
- 동시 refresh가 한 요청을 공유하고 실패 시 기존 token 보존 정책을 지킨다.
- sign-out과 session 만료 뒤 늦은 refresh 응답이 token을 다시 저장하지 않는다.
- YouTube의 알려지지 않은 상태를 decode 실패 없이 보존한다.
- YouTube 상태별 버튼과 허용 동작을 state policy가 계산한다.
- YouTube 연결 표시 정보와 방송 설정을 주입 가능한 preferences store가 관리한다.
- reconnect 취소 후 실패 상태를 덮어쓰지 않는다.
- signaling unauthorized refresh는 한 번만 실행하며 같은 session을 재사용한다.
- 얼굴 등록 취소, generation 변경, lifecycle session 교체 뒤 stale task가 상태를 갱신하지 않는다.
- signaling URL 생성이 강제 unwrap을 사용하지 않는다.

### 검증 기준

- `InnoLiveTests`의 21개 테스트가 통과한다.
- Debug simulator build가 통과한다.
- Release generic iOS build가 통과한다.
- analyze가 새 오류를 보고하지 않는다.
- `git diff --check`가 통과한다.
- production과 test 파일에 실제 access token, refresh token, owner token, stream key가 없다.
- 변경한 HTTP와 signaling 필드가 없다. 필드 변경이 발견되면 contract와 fixture를 같은 변경에서 갱신한다.

## 검증 결과

검증 환경은 Xcode 26.6과 iOS 26.5 simulator다.

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| Unit test | 통과 | iPhone 17 simulator, total 21, passed 21, failed·skipped·expected failures 0 |
| Debug simulator build | 통과 | unsigned generic iOS Simulator, exit 0 |
| Release generic iOS build | 통과 | unsigned generic iOS, exit 0 |
| Analyze | 통과 | Debug generic iOS Simulator, exit 0 |
| Project·scheme parse | 통과 | `InnoLive`, `InnoLiveTests` target과 `InnoLive` scheme 확인 |
| plist·JSON·scheme 문법 | 통과 | 정적 문법 검사 완료 |
| Diff whitespace | 통과 | `git diff --check` 완료 |
| Secret scan | 통과 | 변경 production·test 파일에서 실제 credential 미검출 |
| 실제 기기 E2E | 미실행 | OAuth, media, TURN, network handoff 검증 필요 |

최종 테스트 결과 bundle은 `/tmp/innolive-ios-final-v3-test.xcresult`다. `/tmp` 경로는 검증 환경의 일시 파일이므로 장기 보관 증거로 사용하지 않는다.

## 검증 명령

### 상태와 target

```bash
git status --short
find apps/ios/InnoLive/InnoLive -name '*.swift' -type f -print0 | xargs -0 wc -l
find apps/ios/InnoLive/InnoLiveTests -name '*.swift' -type f -print0 | xargs -0 wc -l
xcodebuild -list -project apps/ios/InnoLive/InnoLive.xcodeproj
```

### Debug build

```bash
xcodebuild \
  -project apps/ios/InnoLive/InnoLive.xcodeproj \
  -scheme InnoLive \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/innolive-ios-final-v3-debug-dd \
  -clonedSourcePackagesDirPath /tmp/innolive-ios-test-derived-data/SourcePackages \
  -disableAutomaticPackageResolution \
  CODE_SIGNING_ALLOWED=NO \
  build
```

### Unit test

```bash
xcrun simctl list devices available
xcodebuild \
  -project apps/ios/InnoLive/InnoLive.xcodeproj \
  -scheme InnoLive \
  -destination 'platform=iOS Simulator,id=7E0208DD-3EC8-455D-8E9F-45D926289522' \
  -derivedDataPath /tmp/innolive-ios-final-v3-test-dd \
  -resultBundlePath /tmp/innolive-ios-final-v3-test.xcresult \
  -clonedSourcePackagesDirPath /tmp/innolive-ios-test-derived-data/SourcePackages \
  -disableAutomaticPackageResolution \
  CODE_SIGNING_ALLOWED=NO \
  test
```

### Release build

```bash
xcodebuild \
  -project apps/ios/InnoLive/InnoLive.xcodeproj \
  -scheme InnoLive \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -derivedDataPath /tmp/innolive-ios-final-v3-release-dd \
  -clonedSourcePackagesDirPath /tmp/innolive-ios-test-derived-data/SourcePackages \
  -disableAutomaticPackageResolution \
  CODE_SIGNING_ALLOWED=NO \
  build
```

### Analyze와 diff

```bash
xcodebuild \
  -project apps/ios/InnoLive/InnoLive.xcodeproj \
  -scheme InnoLive \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/innolive-ios-final-v3-analyze-dd \
  -clonedSourcePackagesDirPath /tmp/innolive-ios-test-derived-data/SourcePackages \
  -disableAutomaticPackageResolution \
  CODE_SIGNING_ALLOWED=NO \
  analyze
git diff --check
git diff -- apps/ios docs/ios-code-quality-refactor.md
```

## 남은 위험

- iOS와 `contracts/api/youtube-broadcast-v1.md`는 `PUT /broadcast`, `POST /stream/prepare`, `POST /stream/golive`를 사용한다. 2026-09-03에 확인한 `innolive-server`의 `feat/anonymization-toggle/#119` checkout은 `POST /stream/start`만 mount한다. 배포 서버의 실제 계약을 확인하고 client·contract·server를 한 방향으로 맞춰야 한다.
- 얼굴 등록 multipart `images`와 contract의 단일 `image` 의미를 서버 코드와 E2E로 확인하지 않았다.
- CHZZK·SOOP 화면은 local `Set`만 바꾸며 OAuth, 저장, API, 송출에 연결되지 않는다. 제품 지원으로 보고하면 안 된다.
- `AuthenticationConfiguration`은 Debug와 Release 모두 `http`와 `https`를 허용한다. Release에서 평문 URL을 거부할지 정책과 test가 필요하다.
- `HomeView`, `CameraManager`, `CameraAudioSettingsView`가 camera, audio, resolution 값을 `UserDefaults.standard`와 `@AppStorage`로 직접 다룬다.
- `YouTubeIntegration`은 735줄이며 API, OAuth, polling, session, media orchestration을 계속 소유한다. 이번 변경은 상태 정책과 preferences를 분리한 단계다.
- `AuthenticationAPI`, `ReferenceFaceAPI`, `YouTubeAPI`, WebSocket은 `URLSession.shared`를 사용한다. HTTP transport와 endpoint adapter 분리는 남아 있다.
- signaling decode 실패와 알 수 없는 message type은 관찰 가능한 진단으로 전달되지 않는다.
- Auth와 YouTube 상태·저장 test는 추가했지만 face task, WebRTC teardown, reconnect, camera rollback test는 없다.
- `BroadcastControllsView` 파일명과 타입명의 `Controlls` 오탈자가 남아 있다.
- simulator build와 unit test는 카메라, Bluetooth audio, TURN, NAT, cellular handoff를 입증하지 않는다.
- 실제 기기에서 Google·Apple·이메일 인증, YouTube 준비·go-live·pause·resume·stop, 얼굴 등록·삭제를 확인해야 한다.
