# Web experience quality v1

브라우저용 API는 `apps/landing`이 소유한다. Next는 검증 후 추가 서버 API
`POST /experience-quality`에 서비스 수집 키로 전달한다. 기존 방송 HTTP·signaling
필드는 유지하며 모바일·데스크톱 클라이언트는 호출하지 않는다.

- POST `/api/experience-quality`, 같은 origin, JSON, 최대 1024 bytes.
- payload·오류 응답·수집 범위·집계 기준: [웹 체험 품질 측정](../../docs/web-experience-quality.md).
- fixture: [첫 프레임 timeout](../fixtures/web-experience-quality-v1.json).
- 호환성: 기존 클라이언트는 측정값을 전송하지 않아도 체험할 수 있다.
  수집 실패는 연결·영상 처리·재시도에 영향을 주지 않는다. version은 1만 허용한다.
  새 버전의 추가 필드는 수집기가 해당 버전을 지원하기 전에는 전송하지 않는다.

- v1 additive 확장: browser 선택 필드. 기존 브라우저 payload도 수신 가능.
- Next→서버 payload는 browser·release 선택 필드 포함. release는 Next runtime에서만 설정.
- 서버 인증은 별도 수집 키이며 사용자 인증·쿠키·방송 소유 토큰 전달 금지.
- 204는 PostgreSQL 저장 또는 중복 확인, 미설정·저장 실패는 503, 수집 제한은 429.
- DB 중복 키: attemptId·event. 보관: 서버 수신 시각 기준 30일 초과 배치 삭제.
- 서버 계약·migration·배포 선행 조건은 서버 deploy/experience-quality.md에 정의.
