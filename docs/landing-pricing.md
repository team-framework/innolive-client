# 랜딩 요금제 표시

2026-09-30 기준. 대상은 `apps/landing`의 가격 안내이며 결제·서버 요금제 계약을 변경하지 않는다.

| 플랜 | 정가 / 월 | 베타 / 월 | 월 방송 시간 | 1회 최대 시간 |
| --- | --- | --- | --- | --- |
| Spark | 0원 | 0원 | 5시간 | 2시간 |
| Glow | 9,900원 | 0원 | 무제한 | 무제한 |
| Beam | 19,900원 | 0원 | 120시간 | 8시간 |
| Plasma | 39,000원 | 0원 | 240시간 | 12시간 |

서버 플랜의 월 시간은 720p 단독 송출 기준이다. FHD 단독·720p 동시 송출은 2배, FHD 동시 송출은 3배 차감한다. Beam은 FHD 단독·720p 동시로 월 60시간, Plasma는 각각 월 120시간·FHD 동시 월 80시간을 표시한다. Spark는 720p 단독만, Glow는 온디바이스 단독 송출을 안내한다. 1회 한도는 실제 방송 시간이다.

Crew 가격은 BM에서 재계산 대상으로 남아 있다. 임의 금액을 표시하지 않고 가격 문의로 연결한다. Business도 맞춤 견적이며, 두 플랜의 방송 시간은 도입 문의로 확인한다.

## 근거

- [Notion BM](https://app.notion.com/p/3e8ba097c6598017b137f8b3bea5824c), 2026-09-29 편집: 플랜명·가격·시간·얼굴 등록 수·베타 0원, Crew 가격 미확정
- [Framework Wiki 요금제 계약](https://github.com/team-framework/framework-llm-wiki/blob/69b8091/지식베이스/연동/06_요금제·방송방식·제안_클라이언트_계약.md), 2026-09-30: Notion BM을 수치 정본으로 지정, 송출 방식별 차감 규칙
- [서버 plan 정의](https://github.com/team-framework/innolive-server/blob/eed7bcfa963ea9c673de0621db31ee0fa2f7af87/internal/plan/plan.go): 월 5/120/240시간, 1회 2/8/12시간, Glow 서버 송출 없음, 등록 2/2/5/10명

Wiki MCP는 인증 오류를 반환했다. 로컬 Wiki 파일의 Git blob이 GitHub 최신 파일과 같은지 확인했고(`cb2fe6bcc24adb1bdf6aaebb565134df08ad3a4a`), 원문을 직접 읽었다. Notion은 페이지를 미검증 상태로 반환한다. 가격은 해당 기획 정본을 반영하고, 시간·허용 방식은 서버 코드와 대조했다. 결제와 실제 시간 차감 실행을 이 작업에서 검증했다고 해석하지 않는다.
