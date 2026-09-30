# 방송 세션 상태 v1

2026-09-30 기준 서버 `eed7bcfa963ea9c673de0621db31ee0fa2f7af87`의 `internal/session/manager.go`, `internal/session/resolution_switch.go`, `internal/session/upgrade_offer.go`, `internal/server/server.go` 응답 형태를 기준으로 기록했다.

`POST /sessions`와 `GET /sessions/{id}`는 세션 전체 스냅샷을 반환한다. `POST /sessions/{id}/stream/prepare`도 전체 스냅샷을 반환하고, 준비 중 일부 설정 적용에 실패하면 `warnings`를 추가한다. 기존 `stream`은 기본 대상의 상태이고, `targets[]`는 대상별 `{provider, stream}`이다. 대상이 아직 없으면 `targets`가 생략될 수 있다. 기존 단일 대상 클라이언트는 `stream`을 계속 읽을 수 있다.

`POST /sessions/{id}/stream/golive`는 준비된 **모든** 대상을 한 번에 전환하고, `StreamState` 필드와 `targets[]`, 선택적 `failed_targets[]`를 최상위에 반환한다. 일부 대상만 실패하면 성공 응답이며 각 실패 항목은 `{provider, code, message}`다. `pause`, `resume`, `stop`은 `?provider=<name>`으로 선택한 한 대상의 `StreamState`만 반환한다. `provider`를 생략하면 기본 대상이다. 이 응답들은 전체 세션 스냅샷이 아니므로 미포함 `media`, `notices`, 시간, 전환 상태를 지우지 않는다.

전체 스냅샷의 추가 필드:

| 필드 | 의미 |
| --- | --- |
| `provider` | 기본 플랫폼 |
| `broadcast_resolution` | 서버가 선택한 `720p` 또는 `fhd` |
| `notices[]` | `{code, at}` 방송 한도 알림 |
| `broadcast_remaining_seconds` | 숫자는 현재 방식의 잔여 초. `null`은 무제한 또는 비방송 상태. 필드 없음은 구형 서버/부분 응답으로 미확인 |
| `resolution_switch` | `status`, `resolution`, `targets`, 선택적 `failed_targets`, `started_at`, 선택적 `finished_at`. 여기의 실패 항목은 `{provider, code}`이며 `golive` 실패 항목과 다름 |
| `upgrade_offer` | `resolution`, `mode`, `units_from`, `units_to`, `remaining_seconds_after`, `options[]`, 선택적 `selected`, `expires_at` |

`options[]`에는 `mode`, `resolution`, `targets`, `units_to`, `remaining_seconds_after`, `needs_settings`, `restarts_broadcast`, `restart_effects[]`가 있다. 각 `restart_effects[]`는 `{provider, same_link, gap_seconds}`다. 제안과 선택지 각각의 `remaining_seconds_after`는 숫자, `null`, 필드 없음의 구분을 유지한다. `null`만으로 현재 라이브가 진행 중인지 판단하지 않는다. 알 수 없는 플랫폼·단계·상태·알림 코드는 원문을 보존한다. 전체 GET에 `warnings`가 빠져도 직전 준비 경고를 유지하고, 다음 `prepare` 응답에 경고가 없으면 지운다.

호환성 확인용 예시는 `contracts/fixtures/broadcast-session-state-*.v1.json`에 있다. 값과 토큰은 모두 가상 데이터다.
