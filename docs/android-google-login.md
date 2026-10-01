# Android Google 로그인 검증

대상은 Android 로그인 UI와 Google 인증이다. HTTP·WebRTC 계약 변경은 없다.

## 1.1.1 계정 선택 후 무응답의 원인

2026-10-01 KST에 Galaxy S25 (SM-S931N)의 실제 설치본 1.1.1 (versionCode 5)에서 지정 계정 선택 후 로그인 화면으로 돌아오고 안내가 표시되지 않는 현상을 재현했다. 19:34:30 KST에 Google Play services가 다음 오류를 기록했다.

- `GetTokenResponseHandler`: Android 애플리케이션이 OAuth 2.0에 등록되지 않았으므로 package name과 SHA-1 certificate fingerprint를 확인하라는 응답.
- `AccountReauth_flowRunner`: `[8] Unknown error [status=UNREGISTERED_ON_API_CONSOLE]`.
- `GoogleSignIn_flowRunner`: `[16] Account reauth failed`.
- 앱 프로세스의 `CredManProvService`: `GetCredentialResponse error returned from framework`.

Google 토큰 획득 단계가 실패한 것이므로 이 시도의 InnoLive `POST /auth/google` 성공이나 서버 장애를 주장할 수 없다. 서버 로그 접근은 없었으며 서버 응답·로그 검증은 수행하지 않았다. 계정 및 토큰 원문은 문서에 기록하지 않는다.

앱은 `GetCredentialCancellationException`을 사용자 취소로 간주하고 `Idle`로 되돌렸다. Google의 계정 재인증 실패도 취소 오류로 반환될 수 있어 이 처리로 실패 안내가 사라졌다. 로그인 진행 중에도 화면에 진행 표시가 없었다.

## 1.1.0과 1.1.1 비교

첨부된 `innolive-1.1.0.aab`와 `innolive-1.1.1.aab`를 비교했다. 두 버전 모두 패키지명이 `com.framework.innolive`이며, 내장된 Web Client ID·로그인 서버 주소와 AAB 서명 인증서가 같다. 해당 버전 빌드의 소스 `7b7a3ec5`와 `acc17f12` 사이에서 `feature/login/oauth/google` 및 `feature/login/Login.kt`는 변경되지 않았다. Google 인증 라이브러리와 관련 ProGuard 규칙도 변경되지 않았다.

| 비교 대상 | SHA-1 | 확인 결과 |
| --- | --- | --- |
| 배포용 1.1.0 AAB | `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20` | 1.1.1 AAB와 같은 서명 |
| 배포용 1.1.1 AAB 및 실제 오류 설치본 | `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20` | Google OAuth 미등록 오류를 실제 관찰 |
| 보관된 Debug/테스트 APK | `A4:E8:D2:BA:0F:59:74:9B:21:46:D4:4A:93:2D:FE:27:C1:30:74:30` | 배포 서명과 다름 |

기기에서 이전에 관찰한 1.1.0 로그인 성공 및 Debug 계측 테스트 성공은 배포용 1.1.0 AAB 검증과 구분한다. 당시 기기의 APK·서명은 교체 전에 보관하지 않았으므로 정확한 기존 설치본 서명을 사후 확정할 수 없다. 남아 있는 Debug/테스트 APK는 다른 서명을 사용한다. 따라서 1.1.0 성공의 유력한 설명은 설치/서명 환경 차이지만, 이전 설치본의 정확한 서명 자체는 추론이다.

현재 인증 설정에서 같은 package·릴리스 서명·Web Client ID를 사용하는 배포용 1.1.0도 동일한 OAuth 등록 문제의 영향을 받을 것으로 예상한다. 배포 서명을 유지한 1.1.0의 실제 로그인 재실행은 하지 않았다. 사용자 데이터 보존을 위해 실제 기기의 앱 삭제/다운그레이드는 수행하지 않았다.

## 필요한 OAuth 설정

현재 `GOOGLE_WEB_CLIENT_ID`를 발급한 Google Cloud 프로젝트의 Android OAuth 클라이언트에 다음 조합을 등록해야 한다.

| 항목 | 값 |
| --- | --- |
| 클라이언트 유형 | Android |
| 패키지명 | `com.framework.innolive` |
| 인증서 SHA-1 | `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20` |

기존 Debug 클라이언트를 덮어쓰지 말고 릴리스 서명의 Android 클라이언트를 추가하거나 해당 릴리스 항목을 수정한다. 기존 Web Client ID는 그대로 사용한다. Google Play App Signing을 사용하는 배포에서는 AAB 업로드 인증서 대신 실제 설치 APK의 앱 서명 인증서를 별도로 확인하여 등록한다.

이 오류는 Google 인증 서버 설정에 의해 발생하므로 앱의 오류 문구 변경만으로 로그인 성공이 보장되지 않는다. 설정 반영 후 원래 1.1.1 설치본에서 계정 선택 → 방송 화면 진입 → 재실행 후 로그인 유지를 다시 확인해야 한다.

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
