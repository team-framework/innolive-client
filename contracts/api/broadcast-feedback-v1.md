# 방송 오류와 세션 알림

서버 응답의 기존 필드는 유지한다. iOS는 새 필드를 선택적으로 읽어 이전 서버의
단독 `stream` 응답과 호환한다. 서버 요청의 변경은 사용자가 이미 열린 YouTube
방송을 확인하고 계속한 경우 prepare에 `allow_concurrent: true`를 추가하는 것이다.
HTTP와 WebRTC signaling 필드의 의미는 변경하지 않는다.

## 오류 정보

오류 봉투는 `error.code`, `error.message`, `error.details`다. 클라이언트는 HTTP
상태와 `details.provider`, `details.field`, `details.reason`, `details.help_url`을
보존한다. provider가 없으면 요청의 provider를 사용한다. 이 iOS 버전의 송출
요청 기본값은 youtube다. 지원하지 않는 오류 메시지와 원시 진단은 일반 오류
안내로 표시하고 로그에 응답 본문이나 토큰을 남기지 않는다.

| 코드 | iOS 처리 |
| --- | --- |
| streaming_not_connected, streaming_reconnect_required | 안내 모달과 계정 연결 화면 |
| live_streaming_blocked | 권한 안내와 help_url 열기(HTTP/HTTPS) |
| plan_resolution_not_allowed, plan_simulcast_not_allowed, plan_server_streaming_not_allowed, monthly_limit_exhausted | 안내 모달과 읽기 전용 플랜 안내 |
| broadcast_limit_reached | 새 방송 시작 안내 |
| egress_slots_exhausted, capacity_exceeded, server_busy | 송출 자리 부족 안내 |
| streaming_quota_exceeded | YouTube 연동 한도·초기화 시각 안내 |
| streaming_rate_limited | 요청 한도 안내 |
| streaming_account_in_use | 방송 종료 후 해제 안내 |
| streaming_prepare_failed, streaming_golive_failed, streaming_update_failed | 플랫폼별 실패 안내와 재시도 |
| withdrawal_in_progress | 계정 삭제 중 안내 |
| channel_already_live | 계속·취소 확인. 계속 시 prepare만 allow_concurrent=true로 한 번 재요청 |
| resolution_switch_in_progress, broadcast_busy, broadcast_going_live | 모달 없이 로딩·준비/전환 제어 잠금. 새 세션 상태 조회 후 해제 |
| bad_request, field_not_changeable_live | 필드 인라인 오류. 현재 폼에 없는 필드는 폼 공통 오류 |
| broadcast_not_ready | 기존 1초 간격 최대 15회 golive 시도 유지 |
| unauthorized | 기존 토큰 갱신 후 한 번 재시도. 갱신 무효 시 로그인 만료 |
| stale_negotiation, upgrade_offer_not_found | 오류 모달 없음. 제안 UI는 후속 기능에서 닫기 연결 |

동시 라이브 확인은 세션과 저장한 설정에 귀속된다. 취소·계정 초기화·세션 종료·
설정 변경 뒤에는 확인을 재사용할 수 없다. 버튼을 반복 눌러도 요청은 한 번이다.

플랜 안내는 `GET /users/me/plan`의 plan, allowed_modes,
monthly_broadcast_seconds, max_per_broadcast_seconds를 읽는다. 이 API의 한도 값
0은 무제한이다. 세션 잔여 시간 API의 null 규칙과 혼동하지 않는다.

## 세션 응답

- `targets`: provider와 stream의 배열. 있으면 대상별 상태를 사용한다.
- `notices`: code와 at의 배열. 세션당 코드별 한 번 표시한다.
- prepare의 `warnings`: code와 message의 배열. youtube_quota_low는 notice와 중복을 제거한다.
- `resolution_switch`: 공통 세션 계약의 status, resolution, targets, started_at, failed_targets 상태.
- golive의 stream 응답에도 targets와 failed_targets(provider, code, message)가 붙는다.
- 이 필드들은 누락·null인 기존 응답을 허용한다. 알 수 없는 notice는 무시한다.

지원 notice는 broadcast_limit_30m, broadcast_limit_10m, broadcast_limit_reached,
monthly_usage_80, monthly_usage_100, monthly_limit_reached, no_input_stopped,
channel_live_elsewhere, platform_broadcast_ended, youtube_quota_low다.
배너는 사용자가 닫기 전까지 유지한다. 닫은 뒤 다시 조회해도 표시하지 않으며
다른 세션에서는 표시 기록을 초기화한다. 오류 닫기는 세션 알림을 지우지 않는다.

stopped 대상의 rtmp_reconnect_exhausted는 플랫폼 연결 종료를 알린다.
reconnect_input_timeout은 영상 복구 실패를 알리며 no_input_stopped와 중복을
제거한다. platform_ended는 platform_broadcast_ended와 중복을 제거한다.
user_requested, resolution_change, target_removed와 알 수 없는 종료 사유는
오류 배너로 표시하지 않는다. 같은 현상의 notice와 종료 사유는 수신 순서와
관계없이 한 번만 알린다.

한 대상의 종료는 다른 대상 상태를 지우지 않는다. 공통 세션 계약의 대상별
상태와 제어를 사용하며 targets에 YouTube가 없으면 호환 stream을 YouTube로
잘못 표시하지 않는다. 세션이 있는 동안 공통 세션 조회를 유지한다.
공통 모델은 [세션 상태 계약](broadcast-session-state-v1.md)을 따른다.

## 검증

`BroadcastProblemTests`, `BroadcastProblemAPITests`는 응답 호환·오류 정보 보존·
후속 행동·확인 취소와 일회 재시도·중복 알림·새 세션·부분 종료·busy 상태를
검증한다. 공유 예시는 [broadcast-feedback.v1.json](../fixtures/broadcast-feedback.v1.json)에 둔다.
