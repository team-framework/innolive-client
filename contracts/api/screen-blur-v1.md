# 전체 화면 블러 v1

근거: [서버 명세](https://app.notion.com/p/3faba097c659805a81c3c728043dace1),
[서버 PR #466](https://github.com/team-framework/innolive-server/pull/466).

- 로그인: `PATCH /sessions/{session_id}/screen-blur`, Bearer Access Token과
  `X-Session-Owner-Token` 헤더를 전달한다.
- 게스트: `PATCH /guest/sessions/{session_id}/screen-blur`, 게스트 쿠키를 전달한다.
- 요청: `{"enabled":true}` 또는 `{"enabled":false}`.
- 성공: 세션 전체 응답의 `media.screen_blur_enabled`가 확정값이다.
- 생성 기본값은 false다. 비식별화 설정과 무관하게 서버가 미리보기와 송출 전체에
  16×9 격자 블러를 적용한다. pause가 우선하고 JPEG 경로에서는 검은 화면을 보낸다.
- 오류: 400 `bad_request`, 404 `not_found`, 409 `on_device_processing`.

## 호환성

기존 서버 계약을 클라이언트에 추가한다. 필드가 없는 구버전 응답도 디코딩하며,
일반 상태 조회에서 필드가 빠지면 마지막 확인값을 유지한다. 토글 응답에 필드가 없으면
실패로 표시하고 기존 값을 유지한다. 서버에 엔드포인트가 없으면 오류를 표시한다.
기존 HTTP/시그널링 필드와 다른 플랫폼 동작은 변경하지 않는다.

픽스처: `contracts/fixtures/session-screen-blur.v1.json`.
