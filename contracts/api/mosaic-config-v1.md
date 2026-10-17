# 모자이크 강도·영역 v1

근거: [서버 PR #484](https://github.com/team-framework/innolive-server/pull/484).

- 로그인: `PUT /sessions/{session_id}/mosaic-config`, Bearer Access Token과
  `X-Session-Owner-Token` 헤더를 전달한다.
- 게스트: `PUT /guest/sessions/{session_id}/mosaic-config`, 게스트 쿠키와 owner
  token을 전달한다. iOS는 아직 쓰지 않는다.
- 요청 본문은 세션 설정 전체다. 빠지거나 `null`인 필드는 서버 AI 기본값이고 `0`과
  다르다. `{}`는 세 값을 모두 기본값으로 되돌린다.
- 성공: 세션 전체 응답의 `media.mosaic_config`가 확정값이다. 송출은 끊기지 않고
  다음 프레임부터 바뀐다.
- 오류: 400 `bad_request`(`details.field`), 404 `not_found`,
  409 `on_device_processing`. 400이면 서버가 기존 설정을 유지한다.

| 필드 | 범위 | 비우면 | 의미 |
|------|------|--------|------|
| `blur_radius` | 0 초과 64 이하 실수 | 24 | 가우시안 블러 반경(px) |
| `pixel_size` | 정수 1..8 | 2 | 모자이크 블록 크기(px), 1이면 블러만 |
| `mask_margin` | 정수 0..64 | 8 | 검출 윤곽선 바깥으로 넓히는 폭(송출 영상 px) |

## 호환성

기존 서버 계약을 클라이언트에 추가한다. iOS는 `mosaic_config`가 없는 구버전 응답도
디코딩하고, 상태 조회에서 필드가 빠지면 마지막 확인값을 유지한다. PUT 응답에 필드가
없거나 서버에 엔드포인트가 없으면 실패로 표시하고 기존 값을 유지한다.
`pixel_size`는 iOS 화면에서 바꾸지 않고 확정값을 그대로 다시 보낸다.

픽스처: `contracts/fixtures/session-mosaic-config.v1.json`.
