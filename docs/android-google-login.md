# Android Google 로그인 검증

대상은 Android 로그인 UI와 Google 인증이다. HTTP·WebRTC 계약 변경은 없다.

## 1.1.1 계정 선택 후 무응답의 원인

2026-10-01 KST에 Galaxy S25 (SM-S931N)의 실제 설치본 1.1.1 (versionCode 5)에서 지정 계정 선택 후 로그인 화면으로 돌아오고 안내가 표시되지 않는 현상을 재현했다. 19:34:30 KST에 Google Play services가 다음 오류를 기록했다.

- `GetTokenResponseHandler`: Android 애플리케이션이 OAuth 2.0에 등록되지 않았으므로 package name과 SHA-1 certificate fingerprint를 확인하라는 응답.
- `AccountReauth_flowRunner`: `[8] Unknown error [status=UNREGISTERED_ON_API_CONSOLE]`.
- `GoogleSignIn_flowRunner`: `[16] Account reauth failed`.
- 앱 프로세스의 `CredManProvService`: `GetCredentialResponse error returned from framework`.

Google 토큰 획득 단계가 실패한 것이므로 이 시도의 InnoLive `POST /auth/google` 성공이나 서버 장애를 주장할 수 없다. 서버 로그 접근은 없었다. 이후 서버 경로의 제한된 검증은 아래에 구분해 기록한다. 계정 및 토큰 원문은 문서에 기록하지 않는다.

앱은 `GetCredentialCancellationException`을 사용자 취소로 간주하고 `Idle`로 되돌렸다. Google의 계정 재인증 실패도 취소 오류로 반환될 수 있어 이 처리로 실패 안내가 사라졌다. 로그인 진행 중에도 화면에 진행 표시가 없었다.

## 1.1.0과 1.1.1 비교

첨부된 `innolive-1.1.0.aab`와 `innolive-1.1.1.aab`를 비교했다. 두 버전 모두 패키지명이 `com.framework.innolive`이며, 내장된 Web Client ID·로그인 서버 주소와 AAB 서명 인증서가 같다. 해당 버전 빌드의 소스 `7b7a3ec5`와 `acc17f12` 사이에서 `feature/login/oauth/google` 및 `feature/login/Login.kt`는 변경되지 않았다. Google 인증 라이브러리와 관련 ProGuard 규칙도 변경되지 않았다.

| 비교 대상 | SHA-1 | 확인 결과 |
| --- | --- | --- |
| 배포용 1.1.0 AAB | `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20` | 1.1.1 AAB와 같은 서명 |
| 배포용 1.1.1 AAB 및 실제 오류 설치본 | `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20` | Google OAuth 미등록 오류를 실제 관찰 |
| 보관된 Debug/테스트 APK | `A4:E8:D2:BA:0F:59:74:9B:21:46:D4:4A:93:2D:FE:27:C1:30:74:30` | 배포 서명과 다름 |

이전 성공 설치본의 서명은 당시 테스트 실행 기록으로 역추적할 수 있다. 당시 Galaxy S25의 앱은 1.1.0 (versionCode 4, lastUpdateTime 2026-10-01 16:36:53)이었다. 실제 로그아웃 → Google 로그인 → 방송 화면 진입 → 재시작 후 로그인 유지를 관찰한 뒤, 본 앱을 교체하지 않고 `app-debug-androidTest.apk`만 설치하여 `com.framework.innolive.test/androidx.test.runner.AndroidJUnitRunner`를 실행했다. 17:03:52 KST의 기록에서 테스트 8개가 통과했다. 테스트 APK의 targetPackage는 `com.framework.innolive`이며 서명은 위 표의 Debug SHA-1이다.

Android 16의 `ActivityManagerService.startInstrumentation()`은 테스트 APK와 targetPackage의 서명을 검사하고, 맞지 않으면 실행을 거부한다. 예외는 디버그 OS에서 root로 실행하는 경우이며 당시 명령은 일반 `adb shell am instrument`이고 서명 검사 우회 명령은 사용하지 않았다. 따라서 기록은 당시 설치본이 Debug 인증서와 서명이 호환되었다는 근거이며, 릴리스 서명의 첨부 AAB를 그대로 설치한 결과로 취급할 수 없다. 이전 APK 파일 자체를 보관한 직접 인증서 검증은 아니지만, 단순히 파일명이나 버전명만으로 추정한 것이 아니라 Android의 서명 검사와 실제 테스트 실행 결과를 연결한 결론이다.

첨부 AAB의 서명과 설치 APK의 서명은 구분해야 한다. AAB를 APK로 변환할 때 사용하는 키가 실제 설치본의 서명을 결정한다. 두 AAB는 릴리스 인증서로 서명되어 있지만, 앞서 성공한 설치 환경은 Debug 인증서와 호환되는 환경이었다. 기존 성공을 배포용 1.1.0 정상 동작의 근거로 확장한 것은 검증 오류였다.

현재 인증 설정에서 같은 package·릴리스 서명·Web Client ID를 사용하는 배포용 1.1.0도 동일한 OAuth 등록 문제의 영향을 받을 것으로 예상한다. 배포 서명을 유지한 1.1.0의 실제 로그인 재실행은 하지 않았다. 사용자 데이터 보존을 위해 실제 기기의 앱 삭제/다운그레이드는 수행하지 않았다.

## 필요한 OAuth 설정

현재 `GOOGLE_WEB_CLIENT_ID`를 발급한 Google Cloud 프로젝트의 Android OAuth 클라이언트에 다음 조합을 확인하고 누락·불일치를 수정해야 한다. Google의 거부 응답으로 이 조합이 현재 인증 요청에 허용되지 않는 것은 확인했지만, 콘솔의 항목이 누락됐는지 다른 서명이 등록됐는지는 관리 조회가 필요하다.

| 항목 | 값 |
| --- | --- |
| 클라이언트 유형 | Android |
| Google Cloud 프로젝트 번호 | `243768920567` |
| 패키지명 | `com.framework.innolive` |
| 인증서 SHA-1 | `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20` |

기존 Debug 클라이언트를 덮어쓰지 말고 릴리스 서명의 Android 클라이언트를 추가하거나 해당 릴리스 항목을 수정한다. 기존 Web Client ID는 그대로 사용한다. Google Play App Signing을 사용하는 배포에서는 AAB 업로드 인증서 대신 실제 설치 APK의 앱 서명 인증서를 별도로 확인하여 등록한다.

이 오류는 Google 인증 서버 설정에 의해 발생하므로 앱의 오류 문구 변경만으로 로그인 성공이 보장되지 않는다. 설정 반영 후 원래 1.1.1 설치본에서 계정 선택 → 방송 화면 진입 → 재실행 후 로그인 유지를 다시 확인해야 한다.

## Web Client ID와 서버 경로 추가 확인 (2026-10-01)

- 두 첨부 AAB의 DEX에 각각 Web Client ID 하나가 있으며 서로 같다. 현행 로컬 빌드 설정과도 일치한다.
- 프로젝트 번호는 `243768920567`이며 위키 `지식베이스/연동/05_GCP_프로젝트_2종_분리.md`의 2026-09-11 운영 구성에 기록된 Web Client ID와 정확히 같다. 위키의 과거 기록만으로 현재 운영값을 단정하지 않고 아래 API로 추가 대조했다.
- 실제 `GET /auth/youtube/config`는 HTTP 200 JSON을 반환했고, 응답의 `web_client_id`가 AAB의 값과 정확히 같다.
- 실제 `POST /auth/google`에 유효하지 않은 진단 토큰을 보내 HTTP 401 JSON의 `invalid_google_token`을 확인했다. 유효한 계정·Google 토큰은 보내지 않았다. 이는 주소·라우팅·무효 토큰 거부 검증이며, 실제 Google 로그인 성공이나 서버 전체의 정상 동작 검증은 아니다. 서버 로그는 읽지 못했다.
- 따라서 Web Client ID 오타·앱과 현행 서버 설정의 프로젝트 불일치는 확인된 실패 원인을 설명하지 않는다. 실제 기기 로그가 지목한 것은 Android OAuth의 package·설치 APK 서명 조합이다.
- 관리 상태를 조회하기 위해 기존 Google Cloud CLI 인증으로 해당 프로젝트를 조회했으나 처음에는 인증 갱신이 `Reauthentication failed`로 거부됐다. 사용자 재로그인 후에는 인증 자체가 복구됐지만 해당 프로젝트 조회가 `PERMISSION_DENIED`로 거부됐다. 읽기 전용 `testIamPermissions`에서 프로젝트 조회 및 OAuth 클라이언트 조회·목록 권한이 모두 비어 있음을 확인했다. 프로젝트 권한이 있는 계정 또는 해당 계정에 OAuth 조회 권한 부여가 필요하다. 콘솔 등록 상태는 아직 읽지 못했다.

## 앱 수정과 검증

- Google 제공자 취소/오류가 반환되면 실패 안내를 표시하고 Google 버튼으로 재시도할 수 있게 한다.
- 로그인 중 `로그인 중…` 진행 표시를 보여주고 중복 요청과 이메일 진입을 막는다.
- 화면 수명 종료에 따른 코루틴 취소는 그대로 전파하며 인증 실패로 바꾸지 않는다.
- 진단 로그는 오류 종류만 남기며 제공자 예외 메시지·토큰·계정을 출력하지 않는다.
- 새 Compose 계측 테스트 2개는 실제 `LoginScreen`·약관 동의·`GoogleSignInController`를 연결하여 검증한다. Google 계정 선택 결과만 대기/취소로 대체하며 실제 Google OAuth 설정 검증으로 간주하지 않는다.
- 동일 테스트가 수정 전에는 진행 문구와 실패 안내 누락으로 각각 실패하고, 수정 후에는 진행 표시·재시도 성공을 확인한다.

## 공식 근거

- [Google 클라이언트 인증](https://developers.google.com/android/guides/client-auth): Android OAuth는 package와 실제 서명 인증서 SHA-1을 확인한다. Play App Signing 인증서는 업로드 인증서와 다를 수 있다.
- [Credential Manager 오류 안내](https://developer.android.com/identity/sign-in/credential-manager-troubleshooting-guide): 일부 동기화/인증 오류가 취소 오류로 반환될 수 있다.
- [Android 16 ActivityManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/am/ActivityManagerService.java): `startInstrumentation()`은 테스트 패키지와 대상 패키지의 서명을 확인하며, 일반 shell 실행에서 서명 불일치를 허용하지 않는다.
