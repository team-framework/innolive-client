# 송출 계정 연결·조회·해제

기존 서버 계약의 iOS 소비 명세다. HTTP 필드와 서버 동작은 변경하지 않는다.
플랫폼 값은 `youtube`, `chzzk`이며 새 플랫폼 항목은 기존 배열에 추가될 수 있다.
알 수 없는 provider는 보존하되 지원하는 계정 화면에만 표시한다.

## 치지직 연결

1. 클라이언트가 32바이트의 암호학적 난수로 매번 새로운 state를 생성한다.
2. 공개 `GET /auth/chzzk/config?state=<state>`에서 `authorize_url`·`redirect_uri`·`client_id`를 받는다.
3. 치지직 공식 인가 URL의 `clientId`·`redirectUri`·`state`가 응답과 요청에 일치하는지 확인한다.
4. 등록된 HTTPS callback의 host·path와 정확히 일치하는 인증 브라우저 복귀를 받는다.
5. callback에 state가 하나만 있고 요청 state와 일치할 때만 code를 교환한다.
6. InnoLive Bearer 인증으로 `POST /auth/chzzk/connect`에 `{"code":"<code>","state":"<state>"}`를 보낸다.

`authorize_url`을 클라이언트에서 재조립하지 않는다. 서버의 공급자 토큰 교환을 사용하며
클라이언트에 client secret·공급자 access/refresh token을 전달하지 않는다.
성공 응답의 치지직 channel은 **camelCase**다. 공통 목록은 **snake_case**다.

```json
{
  "connected": true,
  "provider": "chzzk",
  "channel": { "channelId": "fake-channel", "channelName": "예시 채널", "nickname": "예시 이름" }
}
```

사용자 취소, 인증 실패, state 불일치, 클라이언트 시간 만료를 구분한다. 만료·취소·불일치에서
POST하지 않는다. 인가 code는 일회용이며 교환 실패 뒤 같은 code를 사용자 재시도로 보내지 않는다.
`invalid_auth_code`는 만료·무효·재사용을 합친 서버 오류여서 만료만으로 단정하지 않는다.
InnoLive Bearer 401 후 기존 인증 갱신 재시도는 공급자 교환 전의 인증 거절에 한정한다.

## 공통 조회

`GET /auth/streaming/accounts`는 Bearer가 필요하며 연결이 없으면 `[]`를 반환한다.

```json
[
  {
    "provider": "chzzk",
    "channel_id": "fake-channel",
    "channel_title": "예시 채널",
    "connected_at": "2026-10-01T00:00:00Z",
    "reconnect_required": true
  }
]
```

`reconnect_required`가 true이면 채널과 재연결 버튼을 표시하고 연결 완료로 취급하지 않는다.
목록 성공은 해당 사용자의 서버 상태로 반영한다. 조회 실패는 연결 해제의 증거가 아니므로
마지막으로 확인한 상태를 유지하고 요청 실패를 표시한다. 앱 재실행 후 계정 화면 진입 시
서버 목록을 다시 조회한다. 한 플랫폼 연결·해제 후 다른 플랫폼을 임의로 지우지 않는다.

## 해제와 오류

`DELETE /auth/streaming/accounts/{provider}`는 Bearer가 필요하고 성공은 204다.
그 플랫폼의 `broadcast_phase`가 idle이 아니거나 방식 전환 중이면 해제를 막는다.
`stream.status == stopped`만으로 계정 사용이 끝났다고 판정하지 않는다.
서버의 `409 streaming_account_in_use`에서는 상태를 유지하고 그 플랫폼 방송 종료를 안내한다.

| 코드 | 처리 |
| --- | --- |
| `unauthorized` | InnoLive 로그인 갱신 또는 재로그인 |
| `invalid_auth_code` | 새 인가로 재연결 |
| `chzzk_scope_missing` | 필요한 권한 전부 동의 안내 |
| `chzzk_channel_missing` | 채널 생성 안내 |
| `chzzk_token_exchange_failed` | 인증 요청 실패, 나중에 새 인가 시도 |
| `streaming_reconnect_required` | 계정 재연결 안내 |
| `streaming_account_in_use` | 해당 플랫폼 방송 종료 안내, 연결 유지 |
| 알 수 없는 오류 | 일반 실패 문구, 서버 메시지 원문 미출력 |

## 보안·호환성

- code·callback URL·공급자 토큰을 로그·UserDefaults에 기록하지 않는다.
- state와 code는 현재 시도의 메모리에만 존재한다.
- 로그아웃·계정 초기화 때 인증 브라우저를 닫고 늦은 응답을 적용하지 않는다.
- 기존 YouTube 네이티브 SDK의 `server_auth_code`, `code_source: native` 흐름을 유지한다.
- 기존 `YouTubeStreamingAccountSummary` 이름은 공통 모델의 typealias로 소스 호환성을 유지한다.
- HTTP payload·필드·signaling 변경 없음. 공통 명세·fixture 추가만 포함한다.

예시 fixture: [계정 목록](../fixtures/streaming-accounts.v1.json),
[치지직 연결 성공](../fixtures/chzzk-connect.v1.json).

근거: 서버 `internal/auth/chzzk_oauth_http.go`, `streaming_account_http.go`,
`streaming_account_service.go`, `internal/session/manager.go`의 `ProviderInUse`.
공급자 명세: [치지직 Authorization](https://chzzk.gitbook.io/chzzk/chzzk-api/authorization).
