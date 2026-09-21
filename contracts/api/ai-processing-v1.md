# AI 처리 위치와 얼굴 등록

## 세션 생성

기본값은 `server`다. 온디바이스 클라이언트는 기존 서버의 엄격한 JSON 디코더와
호환되도록 `POST /sessions`에 다음 본문을 보낸다.

```json
{"metadata":{"ai_processing":"on_device"}}
```

새 서버는 최상위 `ai_processing`도 받는다. 두 값이 있으면 최상위 값을 우선한다.
허용 값은 `server`, `on_device`이며 그 밖의 값은 400이다. 생성·조회 응답은
선택한 값을 최상위 `ai_processing`으로 반환한다. 세션 생성 뒤에는 변경하지 않는다.
다른 모드를 선택하면 기존 세션을 종료하고 새 세션을 만든다.

`on_device` 세션은 서버 AI 스트림을 만들지 않고 처리된 입력 영상을 전달한다.
서버의 `media.anonymization_enabled`는 false다. 이 세션에서 서버 AI를 변경하는
`PATCH /anonymization`은 409 `on_device_processing`을 반환한다.
앱의 비식별화 버튼은 로컬 처리기에 적용한다.

## 이전 서버와의 호환

생성 응답에 최상위 `ai_processing`이 없으면 온디바이스 앱은 카메라 연결 전에
`PATCH /sessions/{id}/anonymization`으로 `{"enabled":false}`를 보낸다.
응답의 `media.anonymization_enabled == false`를 확인해야 송출을 시작한다.
누락·true·요청 실패는 연결 실패로 처리하고 세션을 정리한다. metadata를 그대로
되돌려준 것만으로 온디바이스 지원을 확인했다고 판단하지 않는다.
기존 서버는 이 경로에서 추론을 건너뛰지만 AI 연결 자체는 만들 수 있다.

## 저장소

서버 얼굴 등록은 인증 계정에 속하며 기존 `/reference-face` API를 사용한다.
온디바이스 얼굴 등록은 해당 앱 설치의 Application Support에 이름과 임베딩을
저장한다. 사진을 저장하거나 서버로 업로드하지 않는다. 완전 파일 보호를 적용하고
백업 대상에서 제외한다. 두 저장소 사이에 자동 복사나 병합은 없다.

서버 등록의 선택적 multipart `name` 및 응답 `faces[].name`은 추가 필드다.
이전 서버는 이름을 저장하지 않을 수 있으므로 서버 이름 보존에는 서버 업데이트가
필요하다. 기존 이름 없는 얼굴은 `등록 얼굴 N`으로 표시한다.
