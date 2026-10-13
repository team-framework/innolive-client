# 방송 중 송출 방식 전환 v1

2026-10-02 GitHub 서버 `main`의 `a1328b7ae379b9253c0d3adf6908c4fe2e30d34d`를 기준으로 한다. `internal/server/resolution_switch.go`, `internal/session/resolution_switch.go`, `internal/server/live_settings.go`의 blob SHA가 로컬 서버 소스와 일치함을 확인했다. 운영 배포 상태는 확인하지 않았다. 기존 HTTP·WebRTC signaling 의미는 변경하지 않는다.

## 요청

`PUT /sessions/{session_id}/broadcast-mode`

인증은 기존 세션 API와 같은 Bearer access token 및 `X-Session-Owner-Token`을 사용한다.

```json
{"resolution":"fhd","targets":["youtube","chzzk"]}
```

- `resolution`: `720p` 또는 `fhd`. 생략하거나 빈 문자열이면 현재 서버 해상도를 사용한다. `1080p`는 허용하지 않는다.
- `targets`: 전환 후 라이브 대상의 전체 목록. 최소 한 개가 필요하다. 현재 서버가 지원하는 `youtube`, `chzzk`만 허용하며 중복은 제거한다. 순서는 의미가 없다.
- 방송 중에만 호출한다. 일시정지한 라이브 대상도 전환 대상에 포함한다.
- 새 플랫폼을 추가할 때는 계정을 연결하고 해당 플랫폼의 방송 설정을 `PUT /sessions/{id}/broadcast?provider=<name>`으로 먼저 저장한다. 저장 성공 후에만 방식 전환을 요청한다. 별도 `prepare`·`golive`는 호출하지 않는다. 서버가 추가 대상의 준비·라이브 전환을 수행한다.
- 기존에 방송 중인 대상의 정보 수정은 `PATCH /broadcast/live?provider=<name>`을 사용한다. 방식 전환 body에는 방송 제목·설정을 넣지 않는다.

## 응답과 완료 판단

| HTTP | 의미 |
| --- | --- |
| `200` | 해상도와 라이브 대상 구성이 이미 같음. 전체 세션 스냅샷 반환 |
| `202` | 비동기 전환 수락. 전체 세션 스냅샷 반환. 완료를 뜻하지 않음 |

`GET /sessions/{id}`로 전체 스냅샷을 조회한다. 응답 모델은 [방송 세션 상태 v1](broadcast-session-state-v1.md)을 재사용한다. `resolution_switch`는 가장 최근 전환 기록으로 완료 후에도 남는다.

| `resolution_switch.status` | 의미 |
| --- | --- |
| `switching` | 진행 중 |
| `done` | 전환 종료. 일부 실패가 있을 수 있으므로 `failed_targets`도 확인 |
| `failed` | 요청한 대상 수 이상 실패 기록. 서버의 전체 실패 판정 |
| `canceled` | 사용자 방송 종료 등으로 취소 |

`failed_targets`의 항목은 `{provider, code}`이며 `message`가 없을 수 있다. 최종 라이브 대상, 상태와 링크는 세션 `targets[]`의 실제 상태를 기준으로 표시한다. 전환 기록의 `targets`는 요청 목록이다. 기본 `provider`와 최상위 `stream`만으로 새 구성의 성공을 판단하지 않는다. 알 수 없는 상태·실패 코드는 보존한다. 폴링 실패는 마지막 확인 상태를 유지하고 다음 조회에서 재시도한다.

## 전환 동작

- 해상도가 같으면 빠지는 대상만 종료하고 추가 대상만 준비·시작한다. 유지 대상은 송출을 계속한다.
- 해상도가 바뀌면 현재 라이브 대상을 종료하고 새 해상도로 요청 대상을 준비·시작한다. 유튜브 시청 URL이 바뀌므로 최신 링크를 다시 표시한다. 치지직은 같은 채널 링크를 사용하며 재개까지 공백이 생길 수 있다.
- 서버는 치지직 재시작 전에 최소 15초 대기한다. 라이브 전환은 대상별 최대 60초 재시도하며 전체 작업 제한은 3분이다. 이 값은 현재 구현의 시간 설정이며 클라이언트의 완료 타이머가 아니다.
- 한 대상이 실패해도 나머지를 계속 진행하며 이전 구성으로 되돌리지 않는다.
- 전부 일시정지 상태였다면 추가 대상도 일시정지 상태로 연다. 일부만 일시정지했다면 해당 대상의 정지를 유지한다. 서버가 새 송출의 재정지에 실패하면 로그만 남기고 열린 상태를 유지할 수 있으므로 실제 대상 상태를 다시 확인한다.
- 카메라 입력 설정은 이 HTTP 계약에 포함하지 않는다. 클라이언트는 서버가 확인한 `broadcast_resolution`과 자신의 입력 해상도를 동기화하며 signaling 필드는 바꾸지 않는다.

## 거절과 비동기 실패

| HTTP / code | 처리 |
| --- | --- |
| `400 bad_request` | 빈 대상, 미지원 대상, 잘못된 해상도·body 수정 |
| `409 broadcast_not_live` | 최신 상태 조회 후 방송 준비 흐름 사용 |
| `409 resolution_switch_in_progress` | 기존 전환 상태 조회 |
| `409 broadcast_busy` | 준비·라이브 전환 중인 대상이 끝날 때까지 대기 |
| `403 plan_server_streaming_not_allowed`, `plan_resolution_not_allowed`, `plan_simulcast_not_allowed` | 플랜 안내. 거절 전에 기존 방송은 유지 |
| `503 egress_slots_exhausted` | 송출 자리 부족 안내. 거절 전에 기존 방송은 유지 |

계정 미연결·재연결 필요, 플랫폼 준비·라이브 전환 실패는 수락 이후 `failed_targets`로 나타날 수 있다. 예: `streaming_not_connected`, `streaming_reconnect_required`, `streaming_rate_limited`, `streaming_prepare_failed`, `resolution_change_failed`, `resolution_switch_canceled`. HTTP 거절과 수락 이후 실패를 구분하고 입력값을 유지한다.

## 클라이언트 요청 순서

방식 전환 요청 중, 서버 전환 중, 방송 정보 저장 중에는 두 작업의 진입·저장을 서로 잠근다. PUT 응답 전에도 잠금이 필요하다. 서버의 현재 live PATCH handler는 전환 상태를 검사하지 않는다. 완료 스냅샷을 적용한 뒤 실제 라이브 대상의 정보 수정만 다시 허용한다. 계정·세션 변경, 로그아웃, 취소, 늦은 응답은 기존 세션 범위와 요청 세대 검사를 적용한다.

가상 예시: [요청](../fixtures/broadcast-mode-request.v1.json), [수락 응답](../fixtures/broadcast-mode-accepted.v1.json), [부분 실패 완료](../fixtures/broadcast-mode-partial-done.v1.json).
