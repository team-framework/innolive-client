# 웹 방문 전환 분석

대상은 apps/landing이며 기존 인증·방송·체험 API는 유지한다. 서버 collector만 additive API를 추가한다.

브라우저 → 동일 출처 `/api/analytics` → 저장 서버 `/analytics/events` → 기존 PostgreSQL `analytics_events`.
수집 서버는 공통 envelope와 JSONB properties만 저장한다. 이벤트별 Go DTO나 DB 컬럼을 추가하지 않는다.
브라우저의 이벤트 이름·속성은 Next의 `lib/conversion-analytics.ts`에서 검사한다. 현재 properties는 locale·browser만 허용하며 추가 속성은 이 정책을 먼저 수정한다.
서버는 서비스 키로 인증한 gateway를 신뢰한다. JSONB 자체는 개인정보를 자동 제거하지 않으므로 서버 주소·키를 브라우저에 공개하지 않는다.

## 이벤트

- landing_viewed: locale 홈 진입
- pricing_viewed: 요금제 화면 진입
- tryout_viewed: 체험 소개 화면 진입
- experience_started: 실제 연결 시도 시작
- experience_succeeded: 첫 원격 영상 프레임 표시
- signup_viewed: 가입 화면 진입
- signup_verification_sent: 인증 코드 전송 API 성공
- signup_completed: 이메일 인증 완료 API 성공. 클릭·폼 제출은 가입 성공으로 집계하지 않는다.

sessionStorage의 방문 UUID와 순서(sequence)를 사용하며 30분 무활동 시 새 방문으로 구분한다.
같은 탭의 새로고침은 방문을 유지한다. 별도 탭·재방문·다른 기기는 같은 사람으로 연결하지 않는다.
복제된 탭은 브라우저가 sessionStorage를 복사할 수 있으므로 같은 방문을 공유할 수 있다.
Do Not Track=1이면 전송하지 않는다. 전송은 best effort이며 차단·오프라인·이탈 시 누락 가능하다.
실제 사용자 수·서버의 확정 회원가입 수와 동일한 지표로 해석하지 않는다.
회원 ID·이메일·IP·원본 User-Agent·URL·UTM·referrer·토큰·영상·쿠키를 payload에 넣지 않는다.

## 저장과 집계

version 1, eventId UUIDv4, visitId UUIDv4, sequence 정수 1~1000000, event, properties JSON 객체.
release는 Next가 runtime에서 추가한다. eventId 중복은 최초 행과 수신 시각을 유지한다.
서버 수신 시각과 클라이언트 sequence를 분리해 지연된 beacon이 단계 순서를 바꾸지 않게 한다.
`innolive-server/scripts/analytics/funnel.sql`은 최근 7일 랜딩 방문 cohort의 단계별 방문 수와 전환율을 출력한다.
체험 후 가입 흐름과 직접 가입 흐름을 별도로 출력하며 0건일 때도 단계를 표시한다.
체험·가입을 요구 순서대로 수행한 방문만 다음 단계에 포함한다. 가입 화면을 먼저 열었다면 순서를 맞추기 위해 데이터를 재배열하지 않는다.

## 배포

저장 서버를 먼저 배포해 migration 000014 또는 auto migration을 적용한다.
Next는 기존 EXPERIENCE_QUALITY_SERVER_URL, EXPERIENCE_QUALITY_INGEST_KEY runtime 설정을 재사용한다.
수집 키는 서비스 전용이며 사용자 인증을 전달하지 않는다. 키가 없으면 서버 API가 등록되지 않고 Next는 503을 반환한다.
서버가 204로 저장 완료를 확인한 경우 Next도 204를 반환한다. 원본 payload는 stdout에 기록하지 않는다.
기동과 매시간 30일 지난 행을 bounded batch로 삭제한다. 재배포가 기록을 지우지 않는다.
