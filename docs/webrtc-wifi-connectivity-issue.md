# WebRTC Wi-Fi 연결 실패 공유

## 증상

- 모바일 핫스팟에서는 WebRTC 연결이 성공한다.
- 일반 Wi-Fi에서는 웹과 macOS 클라이언트 모두 WebRTC 연결이 실패하거나 시간 초과된다.
- 따라서 특정 클라이언트 구현 문제가 아니라, 공통 WebRTC 연결 경로 또는 서버 인프라 문제로 판단한다.

## 확인된 클라이언트 구성

| 클라이언트 | 현재 ICE 서버 |
| --- | --- |
| Web (`apps/web`) | `stun:stun.l.google.com:19302` |
| macOS (`apps/mac`) | `stun:stun.l.google.com:19302` |

- 두 클라이언트 모두 STUN만 사용한다.
- TURN 서버 주소, relay 후보, TURN 인증정보 발급 경로가 없다.
- offer/answer 및 ICE candidate 시그널링 메시지 자체는 공통 계약에 존재한다.

## 원인 가설

일반 Wi-Fi가 UDP/STUN 통신을 차단하거나, NAT 정책 때문에 client-server 간 직접 ICE 후보 연결이 성립하지 않을 수 있다. 현재는 TURN relay가 없어 이 경우에 우회할 경로가 없다.

핫스팟에서만 성공하는 현상은 이 가설과 일치하지만, 서버와 클라이언트의 ICE 로그 확인 전까지 확정 원인으로 간주하지 않는다.

## 서버팀 조치 요청

1. TURN 서버(coturn 등)를 운영 환경에 배포한다.
2. 방화벽과 보안 그룹에서 다음 경로를 허용한다.
   - TURN: UDP/TCP `3478`
   - TURN TLS: TCP `5349`
   - relay: TURN 서버의 설정된 UDP 포트 범위
3. 세션 생성 API 또는 별도 ICE 설정 API가 단기 만료 TURN credential과 `stun`/`turn`/`turns` URL을 반환하도록 추가한다.
4. 서버 WebRTC 엔진이 TURN 후보를 생성하고 기존 `ice_candidate` 시그널링으로 클라이언트에 전달하는지 확인한다.
5. WebSocket signaling, 세션 API, TURN TLS 인증서가 동일한 운영 도메인에서 정상 동작하는지 확인한다.

> TURN의 장기 비밀번호나 비밀키를 웹의 `NEXT_PUBLIC_*` 환경변수 또는 클라이언트 저장소에 넣지 않는다. 클라이언트에는 세션별 단기 credential만 전달한다.

## 확인에 필요한 로그

Wi-Fi와 핫스팟 각각에서 같은 세션 흐름을 비교한다.

- 클라이언트: `iceConnectionState`, `connectionState`, 수집된 candidate type (`host`, `srflx`, `relay`)
- 서버: offer/answer 처리, 수신·전송 ICE candidate, ICE 연결 실패 사유
- TURN: allocation 생성 여부, 인증 실패, relay 포트 바인딩 및 전송량

## 완료 기준

- 일반 Wi-Fi와 모바일 핫스팟에서 웹·macOS 연결이 모두 성공한다.
- 일반 Wi-Fi 테스트에서 최소 하나의 `relay` candidate를 확인할 수 있다.
- UDP를 제한한 네트워크에서도 TURN TCP 또는 TLS relay로 연결할 수 있다.
