# YouTube 방송 수명주기

YouTube 방송 설정과 공개 전환은 서로 다른 사용자 동작이다. YouTube 송출을
지원하는 클라이언트는 다음 순서를 지키며
`POST /sessions/{id}/stream/start`를 사용하지 않는다.

```text
PUT  /sessions/{id}/broadcast
POST /sessions/{id}/stream/prepare
  -> prepared (시청자에게 미공개)
POST /sessions/{id}/stream/golive
  -> live (시청자에게 공개)
```

모든 요청에는 access token과 session owner token을 함께 보낸다.

```text
Authorization: Bearer <access_token>
X-Session-Owner-Token: <owner_token>
```

## 클라이언트 동작

- `방송 준비`는 저장된 설정을 `PUT /broadcast`로 저장한 뒤 `/stream/prepare`만 호출한다.
- `broadcast_phase == prepared`가 되기 전에는 `라이브 시작`을 활성화하지 않는다.
- `라이브 시작`을 명시적으로 누른 경우에만 `/stream/golive`를 호출한다.
- 준비 중이거나 준비된 동안에는 방송 설정을 잠근다.
- 준비 취소는 `/stream/stop`을 호출한다. `prepared` 방송은 서버가 삭제한다.
- `broadcast_not_ready`는 준비 실패가 아니라 프레임 도착 전 상태다. 잠시 후 같은
  `golive` 요청을 재시도하며, 재시도가 끝나도 준비 상태를 유지해 사용자가 다시
  시도하거나 취소할 수 있게 한다.

## 방송 단계

| `broadcast_phase` | 클라이언트 표시 및 허용 동작 |
| --- | --- |
| `idle` | 방송 준비 |
| `preparing` | 준비 처리 중, 설정 잠금 |
| `prepared` | 라이브 시작 또는 준비 취소, 설정 잠금 |
| `going_live` | 공개 전환 처리 중, 중복 요청 차단 |
| `live` | 방송 종료, 설정 잠금 |

`stream.status`는 RTMP egress 상태이고 `broadcast_phase`를 대신하지 않는다.
