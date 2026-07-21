# Web 사전신청 수집 및 개인정보처리방침

## Request

InnoLive 웹 랜딩 페이지에서 출시 알림을 위한 이메일 사전신청을 받고, 개인정보
수집·이용 동의와 개인정보처리방침을 제공합니다.

## Scope

- Target platform(s): web
- Directories allowed to change: `apps/web/`, `docs/tasks/`
- Explicitly out of scope: 방송·인증·WebRTC 계약, 계정 생성, 이메일 발송

## Reference and behavior

- Existing client or contract to inspect: 기존 `apps/web` Vite 랜딩 페이지
- Expected state transitions: 유효한 이메일과 필수 동의 입력 → Server Action 검증 →
  중복은 같은 성공 응답으로 처리 → PostgreSQL에 이메일·동의 시각·정책 버전 저장

## Acceptance criteria

- [x] 이메일과 필수 동의가 있을 때만 사전신청을 등록한다.
- [x] 중복 이메일 요청도 성공 메시지로 처리한다.
- [x] 개인정보처리방침을 폼 및 푸터에서 확인할 수 있다.
- [x] 홈서버에서 Next.js, PostgreSQL, HTTPS reverse proxy를 함께 실행할 수 있다.

## Contract impact

- Impact: none
- Contracts/fixtures to update: 없음
- Compatibility requirement: 기존 공용 방송 API와 독립적으로 동작한다.

## Verification

- Automated command: `npm ci && npm run typecheck && npm run build` (`apps/web`)
- Manual test path: 홈페이지에서 이메일·동의 후 등록, `/privacy` 링크 확인
- Failure paths: 잘못된 이메일·동의 미체크는 오류를 표시하며, 과도한 요청과 honeypot
  요청은 동일한 성공 응답으로 노출을 최소화한다.
