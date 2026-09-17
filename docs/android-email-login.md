# Android 이메일 로그인

## 범위

이메일 로그인 화면을 기존 `POST /auth/sign-in`에 연결한다. 서버 계약은 변경하지 않는다. 기존 회원가입 폼은 그대로 두며 회원가입·인증 메일 API는 연결하지 않는다.

## 동작

- 이메일 앞뒤 공백만 제거하고 비밀번호는 입력 그대로 전송한다.
- 로그인 요청 중 입력·재요청·회원가입 전환을 잠그고 `로그인 중…`을 표시한다.
- 뒤로가기·화면 제거·Activity 재생성으로 요청이 취소되면 HTTP 호출을 취소하고 늦은 응답을 세션에 저장하지 않는다. 비밀번호는 saved state에 보관하지 않는다.
- 성공 응답의 access/refresh token, Bearer token type, 양수 만료 시간을 검증한 뒤 기존 `AuthenticationSessionRepository`와 Keystore 기반 암호화 저장소에 저장한다. 로그인 후 기존 메인 화면 이동·토큰 갱신·로그아웃을 재사용한다. 비밀번호는 저장하지 않는다.
- 401은 이메일/비밀번호 확인, 429는 잠시 후 재시도, 503은 이메일 로그인 일시 불가로 안내한다. 기타 서버·통신·파싱·저장 실패는 로그인 실패로 처리한다. 서버 응답 본문이나 자격증명을 사용자 메시지·로그에 노출하지 않는다.
- HTTPS와 리다이렉트 비추종을 사용한다. Google 로그인과 이메일 로그인 모두 YouTube 연동과는 별개다.

## 검증 기록 (2026-09-17)

- Android unit test 80개 통과. 새 `EmailSignInTest` 4개가 요청 경로·JSON·공백 처리, 성공 세션 전달, 실패 시 저장 방지, 비정상 토큰 거부, 취소 뒤 늦은 응답 차단을 확인한다. HTTP 응답은 interceptor로 주입한다.
- debug 앱·androidTest APK 빌드 통과.
- 에뮬레이터의 `EmailAuthScreenTest` 5개 통과. 진입·뒤로가기·기존 폼 전환과 로그인 중 중복 클릭 방지, 성공 콜백, 오류·재시도, 요청 중 뒤로가기를 확인한다. 실제 계정 인증은 모의 콜백이다.
- 서버의 기존 `TestEmailSignupClashContract`, `TestLoginHardLimitAndFailureReset` 통과. 서버 내부 테스트 저장소에서 로그인 HTTP 계약·시도 제한을 확인한 것이며 실제 회원가입 요청을 보내지는 않았다.
- 앱 설정 서버에 비등록 테스트 자격정보로 `POST /auth/sign-in`을 호출해 `401 / invalid_email_credentials`를 확인했다. 기존 계정의 로그인 성공·앱 재실행 후 세션 복원은 아직 실제 계정으로 확인하지 않았다.

실행: `apps/android/InnoLive`에서 `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`. UI 테스트는 설치된 APK와 동일한 androidTest APK에서 `com.framework.innolive.feature.login.EmailAuthScreenTest`를 지정한다.
