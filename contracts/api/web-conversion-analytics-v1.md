# Web conversion analytics v1

대상은 apps/landing. 브라우저 POST `/api/analytics` → 서비스 인증 POST `/analytics/events` 추가.
기존 품질·방송·인증·signaling 필드와 플랫폼 API는 유지한다.

공통 envelope: version=1, eventId·visitId UUIDv4, sequence 정수1~1000000, event, properties.
Next는 [이벤트 정의와 개인정보 정책](../../docs/web-conversion-analytics.md)의 이벤트 이름과 locale·browser만 허용한다.
저장 서버는 이벤트 이름을 개별 등록하지 않고 bounded scalar 속성을 JSONB에 보관한다.
Next→서버에는 runtime release만 추가하고 사용자 인증·쿠키·IP·원본 User-Agent는 전달하지 않는다.
fixture: [랜딩 방문](../fixtures/web-conversion-analytics-v1.json).

204는 저장 또는 동일 eventId 확인. 동일 origin을 요구하며 JSON만 수신한다.
브라우저 본문1024bytes·프로세스당600/min, 서비스 본문4096bytes·프로세스당1200/min.
400 입력 오류, 401 서비스 인증 실패, 403 origin 오류, 413 크기, 415 Content-Type, 429 제한, 503 저장 실패.
수집 실패는 제품 흐름을 중단하지 않는다. 구버전 앱은 이벤트를 보내지 않아도 동작한다.
version은1만 허용하며 envelope의 새 버전은 두 수집기가 지원한 후 전송한다.
새 event/properties는 Next 정책 추가만 필요하며 Go DTO·DB migration은 필요 없다.
서버 migration000014/auto와 service collector 배포가 Next 배포보다 먼저 필요하다.
