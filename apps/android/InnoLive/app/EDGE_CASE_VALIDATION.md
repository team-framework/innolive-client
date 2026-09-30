# Android 엣지 케이스 검증 기록

검증일: 2026-09-30. 대상: `apps/android/InnoLive/app`의 인증, 방송 준비·복구, UI 상태 전이. 연결 이슈: [#348](https://github.com/team-framework/innolive-client/issues/348). HTTP·signaling 계약 변경 없음.

## 근거와 선택

- 저장소 `AGENTS.md`, `docs/architecture.md`, `docs/platform-matrix.md`, `docs/android-broadcast-preparation.md`, `docs/android-email-login.md`, `contracts/api/youtube-broadcast-v1.md`를 확인했다. `minSdk=30`, `targetSdk=36`이며 Manifest는 카메라·마이크·인터넷 권한과 런처 Activity를 선언한다.
- 방송은 설정 저장 → 준비 → 사용자의 별도 라이브 시작 순서이며, 복구 중에는 **새 영상 패킷**과 서버 영상 상태를 확인해야 한다. 인증 응답이 유효하지 않거나 저장에 실패하면 현재 세션을 바꾸지 않아야 한다. 제목·설명은 입력 화면에서 각각 100자·5,000자로 제한된다.
- 고위험 조합은 방송 복구(영상 정지 + 오디오만 진행), 로그인(성공·401·잘못된 200 본문), 인증 저장 실패(네트워크 성공 + 디스크 실패)에 집중했다. 정상·경계·오류 등가 분할과 상태 전이를 사용했고, 네트워크 상태×권한×화면 크기×API 수준의 전체 곱은 기기·테스트 계정 제약으로 축소했다.
- `.github/workflows`에는 Android 테스트 CI가 없고, `deploy-web.yml` 및 이슈 할당 작업만 있다. 테스트는 로컬에서 실행했다.
- Android [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config#localhost-configuration)은 API 37부터 명시적 localhost 설정이 없을 때 cleartext 예외를 제공한다. API 36 이하의 loopback HTTP 테스트에는 별도 설정이 필요하다.

## 추적 표

`통과`는 이 검증에서 실제 실행한 테스트만 뜻한다. 기존 테스트의 통과도 테스트 이름만이 아니라 결과 XML 또는 계기 runner 출력으로 확인했다.

| ID | 기능 | 조건·이벤트 순서 | 기대 동작·근거 | 위험도 | 테스트 계층 | 테스트 파일·이름 | 실행 상태 | 남은 공백 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EC-01 | WebRTC 영상 복구 | 복구 중 영상 패킷 0, 오디오 패킷만 증가 | 오디오를 영상 진척으로 세지 않음; 복구 영상 검증 불변조건 | P1 | 단위 | `WebRtcRecoveryPolicyTest.audioPacketsCannotSatisfyRecoveredVideoProgress` | 수정 전 assertion 실패 → 수정 후 통과 | 실제 WebRTC 통계·서버 응답 동시 검증 미실행 |
| EC-02 | 방송 제목 | 정확히 100자 → 101자 | 100자는 허용, 초과 입력은 준비 전 거부; UI 최대 길이 정책 | P2 | 단위 | `YouTubeLiveSettingsValidatorTest.maximumInputLengthsRemainValid`, `titleAndDescriptionBeyondTheirMaximumAreRejected` | 수정 전 초과 assertion 실패 → 수정 후 통과 | 실제 YouTube 제한과 Unicode 코드포인트 단위는 명세 확인 필요 |
| EC-03 | 방송 설명 | 정확히 5,000자 → 5,001자 | 5,000자는 허용, 초과 입력은 준비 전 거부; UI 최대 길이 정책 | P2 | 단위 | 위 두 테스트 | 수정 전 초과 assertion 실패 → 수정 후 통과 | 붙여넣기·IME 조합 중 UI 입력은 미검증 |
| EC-04 | 인증 세션 | refresh 성공 → 암호화 저장 실패 → 다시 refresh | 이전 세션을 유지하고 명시적 재시도 가능; 인증 원자성 | P1 | 단위 | `AuthenticationSessionRepositoryTest.failedPersistenceKeepsOldSessionAndAllowsExplicitRetry` | 통과 | 실제 Android Keystore 저장 공간 부족 미주입 |
| EC-05 | 이메일 로그인 API | 공백·대문자 이메일, 공백 포함 비밀번호 → HTTP 200 | 이메일만 정규화, 비밀번호 보존, 필수 토큰 확인 후 1회 저장 | P1 | 에뮬레이터 loopback HTTP | `EmailSignInLoopbackApiTest.successRequestNormalizesEmailAndPersistsOnlyTheValidatedResponse` | API 36·37 통과 | 외부 테스트 서버·실계정 미검증 |
| EC-06 | 이메일 로그인 API | 동일 요청 → HTTP 401 | 세션 저장 금지, 서버 본문 대신 자격 증명 오류 표시 | P1 | 에뮬레이터 loopback HTTP | `EmailSignInLoopbackApiTest.unauthorizedResponseCannotSaveOrExposeServerBody` | API 36·37 통과 | 실제 서버 오류 코드 미검증 |
| EC-07 | 이메일 로그인 API | HTTP 200 + `{}` | 성공 상태여도 필수 토큰 누락 시 세션 저장 금지; 타임아웃은 본문 오류로 인정하지 않음 | P1 | 에뮬레이터 loopback HTTP | `EmailSignInLoopbackApiTest.malformedSuccessBodyCannotCreateASession`, `malformedBodyAssertionRejectsCancellation` | API 36·37 통과; assertion 제거 시 후자 실패 | 추가·null 필드의 모든 조합은 미검증 |
| EC-08 | 로그인 UI | pending 중 연속 제출 | 네트워크 작업 중 중복 제출 차단 | P2 | 에뮬레이터 UI | `EmailAuthScreenTest.pendingLoginPreventsDuplicateSubmissionAndNavigatesAfterSuccess` | 통과 | 느린 실제 서버의 중복 요청은 미검증 |
| EC-09 | 로그인 UI | 요청 중 화면 이탈 | 뒤늦은 응답이 이탈한 화면을 로그인 완료로 바꾸지 않음 | P1 | 에뮬레이터 UI | `EmailAuthScreenTest.leavingEmailScreenCancelsPendingLogin` | 통과 | 시스템 프로세스 종료 후 복귀는 별개로 미검증 |
| EC-10 | WebRTC 세션 | 중복 start, close 이후 오래된 refresh 완료 | 종료 세션이 되살아나지 않음; 세대 번호 정책 | P1 | 에뮬레이터 상태 | `WebRtcSessionStateLifecycleTest.duplicateStartAndLateRefreshCannotReviveClosedSession` | 통과 | 실제 signaling과 카메라 재연결 미검증 |
| EC-11 | WebRTC 세션 | 인증 실패 → 재시도, 이전 실패가 뒤늦게 완료 | 최신 요청 상태 유지 | P1 | 에뮬레이터 상태 | `WebRtcSessionStateLifecycleTest.failedRefreshCanRetryAndOldFailureCannotOverwriteRetry` | 통과 | 네트워크 전환 중 ICE 재시작 미검증 |
| EC-12 | 복구 시간·시도 | 오프라인 반복, 제한 횟수 및 기한 경계 | 오프라인은 시도 횟수를 쓰지 않고 elapsed time의 창을 넘기지 않음 | P1 | 단위 | `WebRtcRecoveryPolicyTest.offlineTimeDoesNotConsumeRecoveryAttempts`, `serverWindowAndAttemptLimitBoundRetries` | 통과 | 실제 비행기 모드 전환 미검증 |
| EC-13 | 방송 API | 저장 실패 후 재시도, 준비/라이브 분리, 중복 작업 | 저장 성공 후에만 준비, 별도 동작으로 공개 전환; 방송 계약 | P1 | 기존 계기 테스트 | `BroadcastApiFlowTest` | 이번 실행 미검증 | 실제 API·로그에는 승인된 테스트 계정과 테스트 서버 필요 |
| EC-14 | 계정 분리 | A의 삭제 단계 저장 → B로 로그인 | B에게 A의 삭제 단계가 이어지지 않음; 계정별 저장소 | P1 | 기존 계기 테스트 | `AccountDeletionCleanupStoreTest.deletionPhaseSurvivesRecreationAndRemainsAccountScoped` | 이번 실행 미검증 | 테스트가 삭제 단계 preference를 조작하므로 별도 초기화된 기기에서 실행 필요 |
| EC-15 | 프로세스 종료 | 백그라운드 프로세스 종료 후 방송 세션·삭제 단계 복원 | 저장된 자격증명으로 정리 후 새 세션; 문서의 복구 규칙 | P1 | 기기 E2E | 없음 | 미검증 | `recreate()`, force-stop, 일반 재실행과 구분한 실제 process death 시나리오 필요 |
| EC-16 | 권한 | 카메라·마이크 최초 거부, 영구 거부, 설정에서 회수 | 방송 준비 전 차단·안내, 복귀 시 재평가 | P1 | 기기 UI | 일부 기존 권한 테스트 | 이번 실행 미검증 | API 30/36 기기와 권한 조작 필요 |
| EC-17 | 저장소 손상 | 암호화 recovery 항목 한쪽 누락 또는 손상 | 손상 값을 지우고 타 계정 데이터는 유지 | P1 | 기기 저장소 | `SessionRecoveryApiTest` 일부 | 이번 실행 미검증 | Keystore 손상 및 디스크 부족 오류 주입 필요 |
| EC-18 | 입력·접근성 | Unicode/emoji, RTL, 큰 글꼴, 작은 화면, focus | 의미 있는 라벨·레이아웃과 입력 경계 유지 | P2 | UI | 기존 localization·layout 테스트 일부 | 이번 실행 미검증 | 에뮬레이터 설정별 별도 실행 필요 |
| EC-19 | 시간·지역 | 기기 시계/타임존 변경, 방송 시간 표시 | 방송 경과 시간은 `elapsedRealtime` 기준으로 계속 진행 | P2 | 단위 | `BroadcastDurationTest`, `BroadcastOrientationTest` | 전체 단위 테스트에서 통과 | DST·locale 변경 UI는 미검증 |
| EC-20 | 외부 진입 | deep link·누락 Intent 값 | 외부 딥링크 경로가 없음; 런처만 노출 | 해당 없음 | Manifest 조사 | `AndroidManifest.xml` | 해당 없음 | 향후 intent-filter 추가 시 재검토 |
| EC-21 | DB·migration | DB 스키마 업그레이드 | Room/SQLite 의존성·DB 구현 없음 | 해당 없음 | 소스·Gradle 조사 | `app/build.gradle.kts` 및 `src/main` 검색 | 해당 없음 | 저장소는 SharedPreferences/Keystore로 EC-04·17에 포함 |
| EC-22 | 백그라운드·알림 | WorkManager/알림 재시도·진입 | 작업 스케줄러와 알림 권한/컴포넌트가 없음 | 해당 없음 | 소스·Manifest 조사 | `AndroidManifest.xml`, Gradle 의존성 | 해당 없음 | 백그라운드 WebRTC 중단·복귀는 EC-11·15에서 별도 검증 필요 |
| EC-23 | 설치·API 수준 | 신규 설치, API 30 하한, API 36 target, 메모리 압박 | 지원 범위에서 앱 시작·인증·미디어가 동작 | P2 | 기기 | 일부 UI/계기 테스트 | API 36·37 에뮬레이터에서 로그인 loopback 통과 | API 30·실기기, 업데이트·메모리 압박 미검증 |
| EC-24 | 실제 방송 | 준비 → 라이브 → 중단·재시도 | 상태와 서버 결과가 일치하고 중복 공개 전환 없음 | P1 | 테스트 서버 E2E | 기존 `BroadcastLiveDeviceTest` | 미검증 | 승인된 테스트 계정·서버·카메라/마이크 필요; 라이브 시작은 이번에 실행하지 않음 |
| EC-25 | API pagination | 첫·마지막·빈 페이지, 중복 데이터 | 현재 Android API 호출에 페이지·커서 요청 필드가 없음 | 해당 없음 | API 호출 코드 조사 | `YouTubeApi.listAccounts` 등 API 호출부 | 해당 없음 | 서버 계약에 pagination 추가 시 재검토 |
| EC-26 | 네트워크 장애 | 로그인 응답 지연·timeout·연결 중단 후 재시도 | 취소된 로그인 결과는 저장하지 않고 오류 후 재시도 가능 | P1 | 기존 단위·UI + 미완료 기기 | `EmailSignInTest.lateResultAfterCancellationCannotPersistSession`, `EmailAuthScreenTest.failureAllowsRetryWithoutNavigating` | 단위 테스트만 통과, 후자는 이번 실행 미검증 | 실제 소켓 지연·timeout 오류 주입과 앱 로그 상관관계 미검증 |
| EC-27 | API 수준별 loopback 정책 | API 36 debug 앱에서 `127.0.0.1` HTTP 요청, 외부 도메인 cleartext 정책 확인 | loopback만 허용하고 외부 도메인 기본 차단 유지; 테스트 서버 접근 보장 | P2 | 에뮬레이터 정책·HTTP | `EmailSignInLoopbackApiTest.debugAppPermitsOnlyLoopbackCleartext` 및 EC-05~07 | 설정 전 API 36 4/4 실패 → 설정 후 API 36·37 5/5 통과 | API 30~35별 실행은 미검증; release 병합 Manifest에 예외 없음 |

## 실행 증거

- 기준: `./gradlew :app:testDebugUnitTest --offline --no-daemon --console=plain` → 174/174 통과.
- 수정 전: 두 집중 테스트 클래스 19개 중 `audioPacketsCannotSatisfyRecoveredVideoProgress`, `titleAndDescriptionBeyondTheirMaximumAreRejected`가 assertion 실패. 컴파일·환경 오류가 아니었다.
- 수정 후: 같은 집중 테스트 19/19 통과. 전체 `./gradlew :app:testDebugUnitTest :app:assembleDebugAndroidTest --offline --no-daemon --console=plain` → JVM 177개 발견·실행·통과, 실패 0, skip 0; 계기 APK 빌드 성공.
- 에뮬레이터 `Pixel_10`, Android API 37.1: `adb shell am instrument -w -r -e class com.framework.innolive.feature.login.EmailSignInLoopbackApiTest com.framework.innolive.test/androidx.test.runner.AndroidJUnitRunner` → 3/3 통과. 기존 `WebRtcSessionStateLifecycleTest` 3/3, `EmailAuthScreenTest` 지정 메서드 2/2 통과. 계기 테스트 합계 8개 실행·통과, 실패 0, skip 0.
- 후속 수정: API 36 임시 에뮬레이터에서 debug 네트워크 설정 전 `EmailSignInLoopbackApiTest` 4개 모두 실패했다. HTTP 3개는 `CLEARTEXT communication to 127.0.0.1 not permitted`, 정책 검사는 `expected true but was false`였다. `src/debug`에서 `127.0.0.1`만 허용한 뒤, 앱·계기 APK를 새로 설치하고 최종 집중 테스트 5/5를 API 36과 API 37에서 각각 통과했다. `malformedBodyAssertionRejectsCancellation`의 방어 assertion을 임시 제거하면 1/1 실패했고, 원복 후 5/5 통과했다.
- 후속 수정의 JVM 결과는 XML 기준 39개 suite, 177개 실행·통과, 실패 0, error 0, skip 0이다. `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:processReleaseMainManifest`도 성공했다. 병합 Manifest에는 debug만 `networkSecurityConfig`가 있으며 release에는 없다.
- 전체 계기 테스트는 두 에뮬레이터에서 추가 실행했으나 완료 전 중단했다. API 36의 `BroadcastVideoControlsTest.openingControlsDoesNotTintTheVisibleCameraPreview`에서 색상 assertion 실패가 관찰됐다. 이 결과를 전체 계기 테스트 통과로 세지 않는다.
- Gradle `connectedDebugAndroidTest` 첫 실행은 에뮬레이터 연결 종료로 **테스트 0개** 상태에서 실패했다. 이를 assertion 실패로 세지 않았다. 에뮬레이터를 재시작하고 동일 앱·계기 APK를 설치해 `am instrument`로 직접 실행했다.
- JVM 결과: `app/build/reports/tests/testDebugUnitTest/index.html`, XML: `app/build/test-results/testDebugUnitTest/`.

## API 요청·응답·로그

`EmailSignInLoopbackApiTest`는 Mock 인터셉터가 아닌 에뮬레이터 안의 `127.0.0.1` TCP 서버로 **실제 HTTP**를 보냈다. 요청은 `POST /auth/sign-in`, `Content-Type: application/json; charset=utf-8`, Authorization 헤더 없음이었다. 본문 키는 `email,password`뿐이었고, 이메일은 소문자로 정규화·비밀번호 공백은 보존됐다. 200 응답의 필수 토큰·Bearer·만료 필드에서는 세션이 1회 저장됐다. 401에서는 세션이 저장되지 않고 자격 증명 오류로 매핑됐다. 200 `{}`에서는 저장 없이 토큰 검증 예외로 종료됐다. 후속 실행에서 API 36·37의 집중 테스트 직후 `adb logcat -d -v time -s AndroidRuntime:E` 출력은 없었다. 이 API 경로는 성공/오류에 별도 앱 정보 로그를 남기지 않아 요청 ID로 로그 상관관계를 확인할 수는 없다.

외부 서버에 요청하지 않았다. 로컬 설정의 서버가 승인된 테스트 대상인지 확인되지 않았고 테스트 계정도 제공되지 않았다. 따라서 방송·인증의 실제 서버 응답과 서버 애플리케이션 로그는 **미검증**이다.

## 변경과 잔여 위험

`outboundVideoPackets`는 `kind` 또는 `mediaType`이 `video`인 송신 RTP만 합산한다. 영상 통계가 없으면 null을 반환해 복구 성공을 보수적으로 막는다. 방송 설정 최종 검사에서도 길이 상한을 적용한다. 후속 수정에서는 debug 앱의 loopback cleartext만 허용하고, 잘못된 200 본문 테스트가 취소 예외를 성공으로 인정하지 않게 했다. HTTP·signaling payload나 저장 스키마 변경은 없다.

테스트 대상의 모든 조합을 검증한 것은 아니다. 특히 실제 process death, 권한 회수, WebRTC 영상·서버 복구 확인, 로그인 계정 교체, 방송 공개·취소, 지원 API 하한과 실제 서버 로그에는 별도 통제된 기기·서버·계정이 필요하다. 서버의 YouTube 제목·설명 길이가 UTF-16 단위인지 Unicode 코드포인트 단위인지 계약에 명시되지 않아 emoji 경계 기대값은 확정하지 않았다.
