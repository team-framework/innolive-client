# 라이브 방송 정보 수정

대상: iOS. 기존 방송 준비 설정과 별도로 라이브 대상의 정보만 수정한다.
2026-10-01 서버 main의 `internal/server/live_settings.go`와
`internal/session/broadcast.go`를 확인했다. 실제 플랫폼 검증 결과는
`docs/ios-live-broadcast-editing.md`에 기록한다.

## 요청

`PATCH /sessions/{id}/broadcast/live?provider={youtube|chzzk}`

Bearer access token과 `X-Session-Owner-Token`을 보낸다.

| 대상 | 허용 필드 |
| --- | --- |
| YouTube | title, description, category_id |
| 치지직 | title, category_type, category_id, tags |

공개 범위·아동용·썸네일은 전송하지 않는다. YouTube category_id가
없으면 생략해 서버의 현재 값을 유지한다. 치지직 카테고리 없음은
category_type과 category_id를 빈 문자열로 보내며, 태그를 지울 때는
빈 배열을 명시한다. 제목은 앞뒤 공백을 제거한다. 길이와 태그 규칙은
[broadcast-settings-v1.md](broadcast-settings-v1.md)를 따른다.

성공 시 HTTP 200과 세션 snapshot을 반환한다. prepare·golive·stop·방식
전환을 호출하지 않는다. 영상 업링크·현재 방송·세션을 유지한다.

## 오류

- `400 bad_request`: details.field·reason으로 해당 입력에 안내한다.
- `400 field_not_changeable_live`: details.fields 배열의 각 필드에
  ‘방송 중에는 바꿀 수 없는 항목입니다.’를 표시한다.
- `409 broadcast_not_live`: 해당 대상의 수정 버튼을 잠그고 최신 상태를
  조회한다. 조회 실패 시에도 입력을 보관하고 편집을 잠근다.
- `502 streaming_update_failed`, 통신·응답 오류: 입력을 유지해 재시도한다.
- `401`: 기존 인증 갱신과 계정 범위 검사 후 한 번 재시도한다.

## 호환성

기존 서버의 추가 API를 소비한다. 준비용 PUT·기존 필드·WebRTC signaling은
유지한다. 공통 오류 모델은 optional details.fields를 추가해 기존 응답을
읽는다. 기존 방송 응답의 title·description·category_id도 optional로 읽는다.
요청·오류 fixture는 `fixtures/live-broadcast-settings.json`에 있다.
