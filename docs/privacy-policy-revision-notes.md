# 개인정보 처리방침 전문 개정 검토

작성일: 2026-10-01

## 범위와 상태

Web 전용 방침 대신 Web(`apps/landing`)·iOS·Android 공통 전문을 작성했다. 이번 변경은 `apps/landing/content/documents/privacy-policy.ko.mdx`에 한정하며, 검토 기록은 이 문서에 남긴다. 기존 Web(`apps/web`), macOS·Windows, 모바일 코드, API·시그널링 계약은 변경하지 않았다.

한국어 `/ko/privacy`는 iOS·Android도 링크하는 문서이므로 향후 이 Web 변경을 배포하면 모바일에서 여는 한국어 전문에도 반영된다. 영어·일본어 전문, 모바일의 요약·동의 화면과 도움말은 이번에 변경하지 않았다. 사용자가 지정한 시행일은 2026년 10월 2일이며, 작성일과 이전 방침의 시행일을 구분했다. PR 작성은 배포 완료를 의미하지 않는다.

## 참고자료와 반영 원칙

사용자가 제공한 한국어 본문을 참고했다. 외부 홈페이지를 실시간으로 다시 확인한 결과는 아니다. 원본과 비교 문서는 이번 작업 환경의 `/workspace/privacy-policy-research/`에 보존되어 있으며 저장소에 복사하지 않았다.

| 참고자료 | 제공된 본문의 날짜 | 반영한 설명 방식 |
| --- | --- | --- |
| [PRISM Live Studio](https://guide.prismlive.com/legal/privacy-policy) | 시행 2025-09-29 | 방송 영상·음성 처리와 저장의 구분, 공통 방침 안의 플랫폼별 설명 |
| [네이버](https://policy.naver.com/policy/privacy.html) | Ver.12.2, 시행 2026-09-03 | 수집부터 파기까지 이어지는 문서 구조, 쉬운 표현 |
| 카카오 | 시행 2026-10-01 | 처리 항목·목적·이용자 선택 연결, 권리행사와 쿠키 안내 |
| Microsoft | 업데이트 2026년 9월 | 공통 원칙과 기능별 설명의 분리. 제공 자료는 접힌 세부정보를 제외한 요약 본문 |

다른 회사의 연령 제한, 보유기간, 위탁업체, 국외 보관 국가와 AI 학습 목적은 InnoLive에 적용하지 않았다. 기존 InnoLive의 사진·영상·음성·얼굴 특징값에 대한 학습·연구·홍보 이용 금지를 유지했다.

## 구현을 확인하여 반영한 사항

| 설명 | 저장소 근거 |
| --- | --- |
| Web 체험은 카메라만 사용하고 서버로 원본 영상 전송. 외부 방송 시작 전에도 전송 | `apps/landing/components/try-out-experience.tsx` |
| 브라우저 얼굴 감지 후 서버로 등록 사진 전송. 얼굴 감지 실행 구성요소는 jsDelivr에서 받음 | `apps/landing/components/face-registration-modal.tsx`, `apps/landing/app/api/reference-face/route.ts` |
| 로그인·가입 진행 쿠키, 가입 진행 5분, 언어 선택 세션 쿠키 | `apps/landing/lib/auth-server.ts`, `apps/landing/proxy.ts` |
| 로그인 토큰은 인증된 요청으로 브라우저 메모리에서 사용할 수 있음 | `apps/landing/app/api/auth/access-token/route.ts`, `apps/landing/components/try-out-experience.tsx` |
| 현재 Web에 얼굴 동의 상태를 localStorage로 보관하는 구현과 사전신청 UI가 없음 | `apps/landing`의 저장소 호출·화면 검색. 기존 사전신청 데이터 처리 안내는 유지 |
| 모바일 온디바이스 AI는 처리한 영상을 서버로 전송. 서버 등록과 온디바이스 등록은 별도 | `docs/ios-ai-processing-settings.md`, `docs/android-on-device-ai.md`, `contracts/api/ai-processing-v1.md` |
| iOS 온디바이스 특징값은 앱 설치 단위로 저장. 계정 삭제 경로에서 해당 저장소 삭제를 호출하지 않음 | `apps/ios/InnoLive/InnoLive/Features/OnDevicePrivacy/PrivacyFaceLibrary.swift`, `Features/Auth/AuthSession.swift`, `Features/Settings/AccountSettingsView.swift` |
| Android 온디바이스 특징값은 계정별 암호화 저장. 서버 등록 사진의 기기 내 JPEG 사본도 보관 | `apps/android/InnoLive/app/src/main/java/com/framework/innolive/feature/live/privacy/PrivacyFaceLibrary.kt`, `feature/face/ReferenceFaceImageStore.kt`, `feature/face/ReferenceFaceRepository.kt` |
| Android 계정 삭제 성공 시 기기 내 얼굴정보와 사진 사본 정리 | Android `feature/login/account/AccountLocalDataCleaner.kt` |
| 모바일 방송 플랫폼 선택·설정에 CHZZK 항목 존재 | Android `feature/live/components/PlatformDialog.kt`, `contracts/api/broadcast-settings-v1.md`. 실제 제공 범위에 한정하여 설명 |

## 게시 전 확인할 운영 정보

아래 항목은 다른 회사의 사실을 빌려 채우지 않았다. 클라이언트 저장소만으로 확인할 수 없는 서버 운영 사실은 기존 방침을 근거로 유지했으며 운영 담당자의 확인이 필요하다.

1. **서버 보유·파기:** 서버가 국내 자체 운영이라는 설명, 서버 등록 사진 임시 파일 정리, AI 특징값 메모리 보관과 삭제, 계정·문의·기존 사전신청 삭제 기준, SMTP 직접 발송이 실제 배포 구성과 일치하는지 확인한다. 보안 항목의 서버 해시·연결 토큰 암호화도 기존 방침에서 유지한 내용이다.
2. **접속·게스트 기록:** 실제 IP·User-Agent·인증·접속 로그의 저장 여부와 목적·보유기간, 게스트 쿠키의 항목·수명, 종료 요청 누락 시 세션 만료·정리 기준을 확인하여 보유기간 표를 보완한다. 탭 종료만으로 즉시 삭제를 약속하지 않았다.
3. **위탁·국외 이전:** 실제 STUN·TURN 제공자, CDN·인증·방송·메일·호스팅·백업의 운영 경로를 확인한다. 외부 서비스 표는 법정 위탁·국외 이전 현황표를 대체하지 않는다. 실제 수탁자·위탁업무와 이전받는 자·연락처·국가·항목·시기·방법·목적·기간·근거·거부 영향의 구체적인 현황을 확정해야 한다. 기존 방침의 Google STUN 명칭은 Web에서 확인된 고정 제공자가 아니므로 확정된 운영 설정에 따라 보완한다.
4. **법령상 예외 보관:** 적용되는 법령과 실제 보관 항목·기간을 확정한다. PRISM의 5년·3년·3개월이나 다른 회사의 부정이용 기록 기간을 그대로 적용하지 않았다. 백업 삭제에 지연이 있다면 실제 삭제 주기·접근 제한도 안내한다.
5. **동의와 아동:** 회원가입 약관 확인이나 얼굴 등록 안내는 민감정보 별도 동의를 자동으로 충족하지 않는다. 현재 Web 얼굴 등록 경로에 별도 민감정보 동의가 없으므로 처리 근거·동의 기록·철회 절차를 확인하고 필요한 동의 흐름을 마련해야 한다. 만 14세 미만 법정대리인 동의·확인도 실제 절차를 확인해야 한다. 기존 가입 연령 방침을 임의로 바꾸지 않았다.
6. **운영 주체와 공지:** 운영팀 명칭·보호책임자·문의 주소의 최신성을 확인하고, 개정 공고·게시 일정을 점검한다. 사용자가 지정한 시행일은 2026년 10월 2일이다.

## 후속 변경 범위

- 영어·일본어 전문을 한국어 확정본과 맞추고 버전·시행일을 통일한다.
- iOS·Android의 개인정보 요약, 서버·온디바이스 처리 안내, 얼굴 등록·민감정보 동의, 외부 전송 안내를 전문과 맞춘다.
- iOS 계정 삭제 시 온디바이스 얼굴정보를 유지할지 삭제할지 정하고 구현·안내를 함께 조정한다. 현재 전문은 기기에서 별도로 삭제하도록 실제 동작을 설명한다.
- Web의 가입·얼굴 등록 동의 흐름과 도움말의 오래된 사전신청·로컬 저장소 안내를 점검한다. 이번에는 기능·동의 UI를 변경하지 않았다.

## 검증 결과

`apps/landing`의 `npm run build`가 성공했다. MDX 컴파일·TypeScript 검사와 37개 정적 페이지 생성이 완료되었다. 빌드 결과를 로컬에서 실행하여 `/ko/privacy`의 HTTP 200 응답, 본문의 14개 항목·7개 표, 영상 처리·Android 사진 보관·YouTube 정책 설명과 시행일 2026년 10월 2일을 확인했다. 개정 전문과 이 문서, 렌더링된 본문에 EM dash(U+2014)가 없다. `git diff --check`도 통과했다.

기존 개발 서버를 통한 요청은 시간 초과되어 페이지 검증에는 별도로 실행한 프로덕션 빌드를 사용했다. 검증용 프로덕션 서버는 종료했다. 이번 변경은 문서 변경이며 API·시그널링 계약에 영향이 없다.
