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

아래에서 직접 조회한 수정 전 인증 설정에서는 같은 package·릴리스 서명·Web Client ID를 사용하는 배포용 1.1.0도 동일한 OAuth 등록 문제의 영향을 받을 것으로 예상한다. 배포 서명을 유지한 1.1.0의 실제 로그인 재실행은 하지 않았다. 사용자 데이터 보존을 위해 실제 기기의 앱 삭제/다운그레이드는 수행하지 않았다.

## Google Cloud 콘솔에서 확정한 등록 누락 (2026-10-01)

권한이 있는 계정으로 운영 프로젝트 `innolive-505114` (번호 `243768920567`)의 Google 인증 플랫폼 클라이언트 목록과 Android 클라이언트 2개의 상세 화면을 직접 조회했다. 아래 값은 수정 전 상태다.

| Android OAuth 클라이언트 | 패키지명 | 등록된 SHA-1 |
| --- | --- | --- |
| InnoLive Android | `com.framework.innolive` | `D3:28:37:9E:14:4D:53:17:AE:1F:77:44:11:BD:15:19:C6:90:F4:BE` |
| Android 김연호 | `com.framework.innolive` | `A4:E8:D2:BA:0F:59:74:9B:21:46:D4:4A:93:2D:FE:27:C1:30:74:30` |

두 항목 모두 패키지명은 맞지만, 실제 실패 설치본과 배포 AAB의 릴리스 SHA-1 `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20`을 등록한 항목은 없었다. 따라서 누락과 오등록 사이의 미확정 상태가 아니라 **릴리스 인증서의 Android OAuth 클라이언트 등록 누락**으로 원인을 확정한다. 기존 `Android 김연호`의 SHA-1이 Debug 인증서와 일치하므로 이전 Debug 서명 환경의 성공과 릴리스 설치본의 거부가 설명된다. `D3:28:…` 인증서의 실제 사용 설치본은 보관된 파일에서 확인하지 못했다.

기존 Android 클라이언트와 Web Client ID는 유지하고, 다음 항목을 추가해야 한다.

| 항목 | 값 |
| --- | --- |
| 이름 | InnoLive Android Release |
| 클라이언트 유형 | Android |
| Google Cloud 프로젝트 | `innolive-505114` / `243768920567` |
| 패키지명 | `com.framework.innolive` |
| 인증서 SHA-1 | `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20` |

현재는 생성 폼에 위 값을 입력하고 최종 생성 승인을 기다리는 상태이며, 아직 생성하지 않았다. Google Play App Signing을 사용하는 배포에서는 AAB 업로드 인증서 대신 실제 설치 APK의 앱 서명 인증서를 별도로 확인하여 등록한다. 이번 실패 설치본은 APK 인증서도 위 릴리스 SHA-1과 일치함을 직접 확인했다.

이 오류는 Google 인증 서버 설정에 의해 발생하므로 앱의 오류 문구 변경만으로 로그인 성공이 보장되지 않는다. 설정 반영 후 원래 1.1.1 설치본에서 계정 선택 → 방송 화면 진입 → 재실행 후 로그인 유지를 다시 확인해야 한다.

## 이전 버전들의 인증서 추가 대조

Downloads의 이전 버전 파일 및 보관된 APK에서 인증서와 버전명을 직접 읽었다. AAB는 `keytool -printcert -jarfile`, APK는 `apksigner verify --print-certs`로 대조했다. 아래 배포 AAB의 패키지와 내장 Web Client ID는 모두 현재 운영 프로젝트와 같다.

| 파일 또는 설치본 | 버전 | SHA-1 | 수정 전 OAuth 등록 |
| --- | --- | --- | --- |
| `InnoLive-1.0.1.aab`, `InnoLive-1.0.1-onestore.aab` | 1.0.1 | `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20` | 미등록; 두 파일의 SHA-256도 동일 |
| `innolive-1.0.2.aab`, 보관된 서명 완료 1.0.2 AAB | 1.0.2 | `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20` | 미등록 |
| `innolive-1.1.0.aab` | 1.1.0 | `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20` | 미등록 |
| `innolive-1.1.1.aab` 및 Galaxy S25의 실패 APK | 1.1.1 | `1E:24:74:3F:44:1E:4F:7B:03:BA:8A:66:66:B4:D4:55:E0:39:6F:20` | 미등록; 실제 Google 거부 관찰 |
| 보관된 기존 설치/테스트 APK | 1.0, 1.0.2, 1.1.0 | `A4:E8:D2:BA:0F:59:74:9B:21:46:D4:4A:93:2D:FE:27:C1:30:74:30` | 등록됨 |

릴리스 인증서가 1.1.1에서 바뀐 것이 아니다. 이전 배포 AAB도 같은 인증서를 쓴다. 이전 모든 버전의 배포본에서 실제 Google 로그인이 통과했다고 확인한 것은 아니며, 보관된 APK의 등록 상태도 로그인 성공 실측과 구분한다. 직접 성공을 관찰한 1.1.0 설치 환경은 앞 절의 테스트 서명 검사 기록에 따라 Debug 인증서와 호환되는 환경이었다. 스토어가 재서명한 과거 설치 APK나 당시 로그인 로그가 없으므로 이전 스토어 심사가 통과한 이유까지 단정하지 않는다.

## Web Client ID와 서버 경로 추가 확인 (2026-10-01)

- 두 첨부 AAB의 DEX에 각각 Web Client ID 하나가 있으며 서로 같다. 현행 로컬 빌드 설정과도 일치한다.
- 프로젝트 번호는 `243768920567`이며 위키 `지식베이스/연동/05_GCP_프로젝트_2종_분리.md`의 2026-09-11 운영 구성에 기록된 Web Client ID와 정확히 같다. 위키의 과거 기록만으로 현재 운영값을 단정하지 않고 아래 API로 추가 대조했다.
- 실제 `GET /auth/youtube/config`는 HTTP 200 JSON을 반환했고, 응답의 `web_client_id`가 AAB의 값과 정확히 같다.
- 실제 `POST /auth/google`에 유효하지 않은 진단 토큰을 보내 HTTP 401 JSON의 `invalid_google_token`을 확인했다. 유효한 계정·Google 토큰은 보내지 않았다. 이는 주소·라우팅·무효 토큰 거부 검증이며, 실제 Google 로그인 성공이나 서버 전체의 정상 동작 검증은 아니다. 서버 로그는 읽지 못했다.
- 따라서 Web Client ID 오타·앱과 현행 서버 설정의 프로젝트 불일치는 확인된 실패 원인을 설명하지 않는다. 실제 기기 로그가 지목한 것은 Android OAuth의 package·설치 APK 서명 조합이다.
- 초기 CLI 인증 만료와 계정의 IAM 권한 부족은 사용자가 프로젝트 권한을 가진 계정으로 재로그인한 뒤 해소됐다. 프로젝트 조회 및 OAuth 클라이언트 조회·목록·생성·수정 권한을 확인했고, Chrome 콘솔에서 위 Android 클라이언트 상세값을 직접 읽었다.

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
