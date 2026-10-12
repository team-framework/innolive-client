# 온디바이스 직접 송출 v1

`ai_processing: "on_device"`로 만든 세션은 서버가 영상을 받거나 송출하지 않는다.
앱이 가림 처리한 영상을 YouTube·치지직으로 RTMP(S) 직접 송출한다. 서버는 플랫폼
방송 생성, 라이브 전환, 종료와 사용 시간·한도 집행만 맡는다.

이 계약은 최상위 `ai_processing: "on_device"`로 만든 세션에만 적용한다.
`server` 세션에서 앱 비식별화를 쓰는 기존 경로는 [AI 처리 계약](ai-processing-v1.md)을
따른다. 서버 근거는 innolive-server #438, #434, #435, #436, #437이다.

## 역할

| 구분 | 담당 |
| --- | --- |
| 가림 처리, RTMP(S) 연결·송출·종료 | 앱 |
| 플랫폼 방송 생성·라이브 전환·종료 | 서버 |
| ingest URL 전달 | 서버, 세션 소유자 응답에만 |
| 사용 시간 차감, 1회·월 한도, 입력 없음 종료 | 서버, 하트비트 기준 |

## 세션 생성

```json
{"provider":"youtube","ai_processing":"on_device","broadcast_resolution":"720p"}
```

- `provider`: `youtube` 또는 `chzzk`, 생략 시 `youtube`
- `broadcast_resolution`: `720p` 또는 `fhd`. 차감 배수 기준이며 서버가 실제 인코딩을
  검증하지 않으므로 앱의 실제 송출 해상도와 같아야 한다. 방송 중에는 바꿀 수 없다.
- 응답 201의 `owner_token`은 이 응답에서 한 번만 온다. 기존 세션 정리 저장소에 보관한다.
- 앱은 이 세션으로 WebRTC 시그널링을 하지 않는다.

## 송출 순서

YouTube:

1. `PUT /sessions/{id}/broadcast`로 설정 저장 (`made_for_kids` 필수)
2. `POST /sessions/{id}/stream/prepare` 응답의 `ingest_url`로 RTMPS 송출 시작
3. `POST /sessions/{id}/stream/golive`로 공개 전환. `409 broadcast_not_ready`는 영상
   도착 전 상태이므로 송출을 유지한 채 재시도
4. 종료: `POST /sessions/{id}/stream/stop?provider=youtube` 후 RTMPS 연결 종료

치지직:

1. `POST /sessions/{id}/stream/prepare`. `ingest_url`은 없다.
2. `POST /sessions/{id}/stream/golive` 응답의 `direct_ingest[]`로 RTMP 연결.
   연결하는 순간 공개 방송이다. golive 전에는 연결하지 않는다.
3. 종료: `POST /sessions/{id}/stream/stop?provider=chzzk` 후 RTMP 연결 종료.
   서버는 치지직 방송을 끝낼 수 없으므로 앱이 끊어야 방송이 끝난다.

동시 송출은 YouTube 송출 시작, `golive`, 치지직 연결 순서를 따른다. `golive`가
치지직 대상을 `failed_targets[]`로 반환하면 치지직은 연결하지 않는다.

stop 요청이 실패해도 앱은 연결을 끊는다. YouTube는 송출이 멈추면 자동 종료되고,
치지직은 연결이 끊긴 뒤 약 13~14초가 지나면 방송이 끝난다.

## 응답 필드

| 요청 | 필드 | 설명 |
| --- | --- | --- |
| prepare | `ingest_url` | 온디바이스 세션의 YouTube 대상이 `prepared`일 때만 포함 |
| golive | `direct_ingest[]` | `{provider, ingest_url}`. 라이브 상태인 치지직 대상 |
| golive | `failed_targets[]` | `{provider, code, message}`. 실패 대상 |

- 직접 송출 중 `targets[].stream.status`는 계속 `idle`이다. 방송 상태는
  `broadcast_phase`로 판단한다.
- 같은 대상의 `direct_ingest`가 재시도 응답에 다시 올 수 있다. 이미 연결한 대상은
  다시 연결하지 않는다.

## 하트비트

`POST /sessions/{id}/heartbeat`, 로그인 토큰과 `X-Session-Owner-Token` 필요.

- 라이브 대상이 하나 이상인 동안 15초마다 보낸다. 첫 전송은 `golive` 성공 직후다.
- 실패해도 송출은 유지하고 다음 주기에 다시 보낸다. 누락분은 재전송하지 않는다.
- 누적 카운터는 송출 시작 이후 누적값이다.

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `sent_at` | O | 앱 전송 시각, RFC 3339 UTC |
| `targets[].provider`, `connected` | O | 대상별 RTMP 연결 상태 |
| `targets[].bitrate_kbps`, `reconnects` |  | 실제 비트레이트, 누적 재연결 |
| `video.fps`, `dropped_frames` | O | 인코더 입력 fps, 누적 드롭 |
| `video.width`, `height` |  | 송출 해상도 |
| `anonymization.failed_frames` | O | 가림 처리 실패로 내보내지 않은 누적 프레임 |
| `anonymization.enabled`, `inference_ms_p50`, `inference_ms_p95` |  | 주기 내 추론 상태 |
| `device.thermal`, `battery_pct`, `charging`, `cpu_pct` |  | `thermal`: `nominal`·`fair`·`serious`·`critical` |
| `platform`, `model` | O | `ios`·`android`, 기기 모델 |

응답 200은 `stop_targets[]`, `notices[]`, `broadcast_remaining_seconds`다.

- `stop_targets[]`: 서버가 끝낸 대상(한도 도달, 입력 없음, 플랫폼에서 종료). 앱은
  해당 연결을 즉시 끊는다. 사용자가 stop한 대상은 담기지 않고, 한 번 알린 대상은
  다시 담기지 않는다.
- `notices[]`, `broadcast_remaining_seconds`: [세션 상태](broadcast-session-state-v1.md)와
  같은 의미다.

| 상태 | code | 앱 동작 |
| --- | --- | --- |
| 400 | `bad_request` | 필수 필드 누락. 송출 유지, 구현 오류로 처리 |
| 401 | `unauthorized` | 기존 인증 갱신 후 재시도 |
| 403 | `forbidden` | 소유권 상실. 모든 연결 종료 |
| 404 | `not_found` | 세션 삭제. 모든 연결 종료 |
| 409 | `heartbeat_not_applicable` | 라이브 대상 없음. 모든 연결 종료 |
| 5xx, 네트워크 오류 |  | 송출 유지, 다음 주기 재전송 |

서버는 마지막으로 연결된 대상이 있다고 보고한 하트비트 후 5분이 지나면 방송을
끝낸다. 시계는 `golive` 시점에 시작하고, 연결된 대상이 없는 보고는 시계를 다시
시작하지 않는다.

## 한도와 차감

- 라이브 전환부터 stop 또는 세션 종료까지 월 사용량에서 차감한다.
- 배수: 720p 단독 1, 720p 동시 2, FHD 단독 2, FHD 동시 3
- 요금제가 허용하지 않는 송출 방식과 소진된 한도는 prepare에서 403으로 막힌다.
- 1회·월 한도 알림과 종료는 하트비트 응답과 세션 응답의 `notices[]`로 온다.

## 거부되는 요청

| 요청 | 응답 |
| --- | --- |
| `POST stream/pause`, `POST stream/resume` | `409 on_device_processing` |
| 방송 중 `PUT broadcast-resolution` | `409 on_device_processing` |
| `PATCH anonymization` | `409 on_device_processing` |

## 스트림 키 보호

`ingest_url`에는 플랫폼 스트림 키가 들어 있다. 키는 방송마다 바뀌지 않으므로
유출되면 그 채널로 언제든 송출할 수 있다.

- 메모리에만 보관하고 디스크, 저장 상태, 백업에 쓰지 않는다.
- 로그, 오류 문구, 크래시 리포트, 분석 이벤트에 남기지 않는다. RTMP 라이브러리
  로그도 포함한다.
- 문자열 변환 시 값을 가리는 타입으로 다룬다.
- 치지직 ingest는 평문 RTMP다.

## 현재 제약

- 서버는 WebRTC answer가 없는 세션을 `SESSION_NEGOTIATION_TIMEOUT`(기본 30초,
  방송 설정 저장 시 생성 후 최대 10분까지 연장) 뒤 삭제한다. 온디바이스 세션 예외가
  서버에 반영되기 전에는 운영 서버에서 방송이 중간에 끝날 수 있다. 개발 검증은
  `SESSION_NEGOTIATION_TIMEOUT=0` 서버를 사용한다.
- 치지직 방송 제목·카테고리·태그 적용은 서버 송출 상태를 기다리는 구조라 직접
  송출에서 확인되지 않았다.

## Fixture

- `contracts/fixtures/direct-publish-session-request.v1.json`
- `contracts/fixtures/direct-publish-prepare-youtube.v1.json`
- `contracts/fixtures/direct-publish-golive-chzzk.v1.json`
- `contracts/fixtures/direct-publish-heartbeat-request.v1.json`
- `contracts/fixtures/direct-publish-heartbeat-response.v1.json`
- `contracts/fixtures/direct-publish-heartbeat-not-applicable.v1.json`
