# Web experience quality v1

이 API는 `apps/landing` 내부 진단 수집용이다. 기존 InnoLive 서버 HTTP·signaling
계약에 필드를 추가하지 않으며 모바일·데스크톱 클라이언트는 호출하지 않는다.

- POST `/api/experience-quality`, 같은 origin, JSON, 최대 1024 bytes.
- payload·오류 응답·수집 범위·집계 기준: [웹 체험 품질 측정](../../docs/web-experience-quality.md).
- fixture: [첫 프레임 timeout](../fixtures/web-experience-quality-v1.json).
- 호환성: 기존 클라이언트는 측정값을 전송하지 않아도 체험할 수 있다.
  수집 실패는 연결·영상 처리·재시도에 영향을 주지 않는다. version은 1만 허용한다.
  새 버전의 추가 필드는 수집기가 해당 버전을 지원하기 전에는 전송하지 않는다.
