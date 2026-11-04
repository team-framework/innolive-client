# iOS YouTubeAPI 파일 구성

작성 기준: 2026-11-03 (#887)

`YouTubeAPI`는 서버 요청을 보내는 단일 타입이다. 엔드포인트 메서드를 역할별 extension 파일로 나눴고, HTTP 경로·메서드·본문·헤더는 바꾸지 않았다.

## 파일별 역할

| 파일 | 내용 |
| --- | --- |
| `YouTubeAPI.swift` | 타입, 인증 설정, 공통 `request`, 인증 갱신, 응답 검증 |
| `+Account` | 요금제·사용량, 관리자 확인, YouTube·치지직 연결, 스트리밍 계정, 카테고리, 로컬 테이크 업로드 |
| `+Session` | 세션 생성·삭제·상태, 세션 범위, WebRTC 설정, 하트비트 |
| `+Broadcast` | 방송 설정, 준비, 라이브 전환, 스트림 동작, 비식별화, 모자이크, 방송 모드, 업그레이드 제안 |

## 접근 제어

- Swift extension은 저장 속성을 가질 수 없어 상태 선언을 본 파일에 둔다.
- `private` 멤버는 같은 파일의 extension에서만 보이므로, 다른 파일이 쓰는 `serverURLProvider`, `request`, `authenticatedData`, `perform`, `validate`, `decode`, `YouTubeEmptyRequest`, `YouTubeAPIErrorEnvelope`는 모듈 범위로 열었다.
- 요청 무효화 오류와 URL 세션은 본 파일에서만 쓰므로 `private`을 유지한다.
- 한 파일 안에서만 쓰는 요청 본문 타입은 그 파일의 `private` 구조체로 둔다.
