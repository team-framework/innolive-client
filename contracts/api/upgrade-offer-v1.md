# 업그레이드 제안 계약

2026-10-03 기준 서버 `d1b7259`의 `internal/session/upgrade_offer.go`, `internal/server/upgrade_offer.go`, `internal/server/resolution_switch.go`를 확인했다. 기존 서버 API를 소비하며 요청·응답 필드와 WebRTC signaling 변경은 없다.

## 표시와 보류

- 전체 세션 응답의 `upgrade_offer`는 제안이 없으면 `null`이다. 부분 응답은 직전 전체 스냅샷의 제안을 보존한다.
- `options[]`마다 `mode`, `resolution`, `targets`, `units_to`, `remaining_seconds_after`, `needs_settings`, `restarts_broadcast`, `restart_effects[]`를 사용한다. `units_from`은 제안 전체의 현재 차감 배수다.
- 재시작 없는 옵션의 `restart_effects`는 서버에서 `null`일 수 있다. null·누락은 빈 영향 목록으로 파싱한다.
- 기본 보류는 30초이며 `expires_at`은 서버의 RFC 3339 만료 시각이다. 소수점 이하 초를 포함할 수 있다. 클라이언트가 임의의 30초·3분 타이머로 서버 만료 시각을 대체하지 않는다.
- `remaining_seconds_after`는 숫자·null·필드 없음의 구분을 유지한다. 라이브 제안의 null은 제한 없음으로 표시하고 누락은 확인 필요로 표시한다.
- `selected`는 설정을 진행 중인 선택의 mode다. 복원 시 해당 선택의 설정을 이어갈 수 있다.

## 선택과 확정

| 조건 | 요청 | 결과 |
| --- | --- | --- |
| 설정 불필요 | `PUT /sessions/{id}/broadcast-mode`, `{resolution, targets}` | 기존 방식 전환 시작, 제안 전체 소멸 |
| 설정 필요 | `POST /sessions/{id}/upgrade-offer/select`, `{mode}` | 전체 세션, selected 및 현재 시각부터 3분으로 연장된 expires_at |
| 추가 설정 저장 | `PUT /sessions/{id}/broadcast?provider={provider}` | 기존 추가 플랫폼 설정 저장, 성공 후에만 broadcast-mode 호출 |
| 거절 | `DELETE /sessions/{id}/upgrade-offer`, 본문 없음 | 전체 세션, 제안 소멸 및 보류 반납 |

위 요청은 Bearer 인증과 `X-Session-Owner-Token`을 사용한다. 방식 전환은 `200`(동일 구성) 또는 `202`(전환 시작) 응답이며 기존 `resolution_switch` 폴링을 그대로 따른다.

`restarts_broadcast=true`이면 확인 전에 `restart_effects[]`의 provider별 `same_link`와 제공된 `gap_seconds`를 안내한다. 확인 취소는 select·설정 저장·broadcast-mode 요청을 보내지 않는다. 서버가 이미 selected로 확인한 설정 재진입은 select를 반복하지 않는다.

`upgrade_offer_not_found`는 HTTP 404이며 제안 UI만 닫는다. 세션 삭제·영상 연결 종료·계정 초기화로 처리하지 않는다. 다른 오류는 재시도 안내와 입력 초안을 유지한다. 만료·전체 스냅샷에서 제안 소멸·수락·거절 후에는 전체 제안과 제안에서 연 설정 화면을 닫는다.

## 호환성

- 새 API·필드를 서버에 추가하지 않는다. 기존 공통 세션 모델의 파싱을 유지한다.
- 알 수 없는 mode·플랫폼·해상도는 원문을 보존하되 지원하지 않는 전환 요청은 보내지 않는다.
- options가 없는 구형 제안은 임의로 재시작 영향·플랫폼 정보를 만들지 않는다. 선택 불가 안내와 거절을 제공한다.
- 서버 만료 또는 제안 소멸은 준비·송출·세션 전체를 종료하지 않는다. 설정 저장 응답이 도착해도 제안이 사라졌다면 전환하지 않는다.
- 늦은 응답은 세션·계정·요청 세대 검사로 차단한다. 만료되거나 소비한 동일 세션의 제안은 오래된 스냅샷으로 다시 표시하지 않는다.
