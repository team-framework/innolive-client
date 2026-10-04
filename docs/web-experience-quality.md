# 웹 체험 품질 측정

대상은 `apps/landing`의 회원·게스트 체험이다. 공유 서버 API와 WebRTC signaling
payload는 유지한다. 브라우저의 `/api/experience-quality` 요청을 Next가 검증한 뒤
서버 `POST /experience-quality`에 전달해 PostgreSQL에 저장한다.

## 연결과 영상 표시

- WebRTC `connected`는 전송 연결 완료다. 이 상태만으로 처리된 영상이 보인다고 판단하지 않는다.
- 원격 video의 `requestVideoFrameCallback`으로 첫 프레임의 composition 제출을 확인한다.
  미지원 브라우저는 `playing` 또는 `loadeddata` 시 재생 상태·decoded dimensions를 확인한다.
  fallback은 실제 화면 composition 확인보다 약한 근거다.
- 연결과 첫 프레임을 모두 확인한 뒤 기존 체험 완료 문구·얼굴 등록 버튼을 표시한다.
- 영상 transceiver는 브라우저가 VP8을 지원하면 offer에서 VP8을 우선한다.
  H.264·RTX 등 나머지 codec은 유지한다. VP8 또는 codec preference API가 없으면
  브라우저의 기본 협상을 유지한다. HTTP·signaling 필드는 바꾸지 않는다.
- 연결 제한 시간은 기존 30초, 연결 후 첫 프레임 제한 시간은 15초다.
  게스트 대기열·권한 허용 시간은 이 제한 시간에 포함하지 않는다.
- 대기 중 종료할 수 있다. 재시도·페이지 이탈·pagehide에서 카메라, 연결, 프레임 관찰을 정리한다.
  back/forward cache 복귀 시 새 시도로 연결한다. 이전 시도의 callback은 현재 상태를 바꾸지 않는다.

## 수집 API v1

같은 origin의 `POST /api/experience-quality`, `application/json`, 최대 1024 bytes.
DB 저장 확인은 204, 저장 실패·미설정은 503, 프로세스당 분당 600건 초과는 429, 잘못된 값은 400, 다른 origin은 403, 크기 초과는 413,
다른 Content-Type은 415다. 응답은 `no-store`다.

```json
{"version":1,"attemptId":"12345678-1234-4234-8234-123456789abc","event":"failed","role":"guest","locale":"ko","retry":true,"stage":"first_frame","elapsedMs":15500,"code":"timeout"}
```

- event: `started`, `transport_connected`, `first_frame`, `failed`, `ended`, `cancelled`.
- stage: `session`, `queue`, `camera`, `signaling`, `transport`, `first_frame`, `streaming`.
- code는 failed에만 사용한다: `permission_denied`, `camera_missing`, `timeout`,
  `request_failed`, `connection_failed`.
- elapsedMs는 시도 시작부터 `performance.now()`로 측정한 정수 ms다. 연결·첫 프레임
  시간에는 서버 세션 생성·게스트 대기·권한 허용 시간이 포함된다.
- retry는 같은 화면에서 실패 후 다시 시도했음을 뜻한다. 이전 시도의 실패 원인과
  다음 시도를 연결하는 영속 식별자는 수집하지 않는다.
- browser는 safari, chrome, firefox, edge, other, unknown 중 하나다. 원본 User-Agent를
  브라우저에서 분류하고 종류만 보낸다. 기존 payload는 browser를 생략할 수 있다.
- Next는 runtime의 INNOLIVE_WEB_REVISION을 release로 추가한다. 브라우저가 release를
  지정할 수 없다. 값 미설정 시 unknown이다.
- attemptId는 시도마다 생성하는 UUID다. 쿠키·localStorage에 저장하지 않는다.
  회원 ID, 서버 세션 ID, ticket ID, 토큰, URL, IP, User-Agent, SDP, ICE candidate,
  얼굴 사진, 영상, 원본 오류 메시지는 진단 JSON에 넣지 않는다. 추가 필드는 거부한다.

브라우저는 beacon 또는 keepalive fetch로 전송한다. 실패해도 체험을 중단하지 않는다.
Next는 사용자 쿠키·인증 토큰·요청 헤더를 전달하지 않는다. 서버 전용 수집 키만 사용한다.
서버의 experience_quality_events 테이블은 attemptId·event를 기본 키로 중복을 무시한다.
수신 시각은 서버가 생성하며 중복 요청이 보관 기간을 늘리지 않는다. 기동 시·매시간
30일 초과 행을 배치 삭제한다. 장애나 큰 backlog 시 삭제가 지연될 수 있다.
DB 백업·reverse proxy 접속 로그는 별도 보관 정책을 따른다.
Next는 DB 저장 확인 후 204를 반환한다. 실패 시 payload·키·원본 오류 없이
experience_quality_storage_unavailable만 기록하며 503을 반환한다.

## Runtime 설정

- 서버와 Next: EXPERIENCE_QUALITY_INGEST_KEY, 동일한 32자 이상 랜덤 수집 키.
- Next: EXPERIENCE_QUALITY_SERVER_URL, 서버 API base URL. HTTPS 또는 loopback HTTP.
- 운영 웹은 root 소유·0600의 /etc/innolive/web-quality.env로 두 값을 주입한다.
  NEXT_PUBLIC 또는 Docker build ARG에 키를 넣지 않는다.
- 서버 versioned migration 000013 또는 auto migration과 수집 키를 먼저 적용하고 웹을 배포한다.
  운영 설정·배포는 이 PR의 로컬 검증과 별도다.
- Next는 3초, 서버 DB 저장은 2초 timeout을 사용한다. 재전송 큐를 보관하지 않으므로
  저장 장애·브라우저 종료 시 이벤트가 유실될 수 있다. 체험 연결은 계속 동작한다.

## 집계

새 기록은 서버 저장소의 scripts/experience-quality/report.sql로 허가된 DB 연결에서
최근 7일 브라우저·릴리즈별 성공률·시간·실패 단계를 조회한다.
기존 stdout 로그 파일에는 아래 집계 도구를 계속 사용할 수 있다.

```sh
cd apps/landing
node scripts/report-experience-quality.mts /path/to/web-container.log
```

동일 attemptId·event 중복을 제거하고 started가 있는 시도를 분모로 사용한다.
첫 프레임 성공률, 실패·취소·종료·진행 중 시도 수, 재시도 첫 프레임 성공률,
연결·첫 프레임·연결 이후 프레임까지의 p50/p95 시간, 단계별 실패 건수를 출력한다.
시도 0건인 비율·표본 없는 percentile은 null이다. 표본 없는 결과를 0%로 해석하지 않는다.

집계 기간은 로그 추출 범위로 지정한다. 진행 중 시도·누락 전송 때문에 성공률이
낮아질 수 있으므로 openAttempts와 orphanAttempts를 함께 확인한다. 로그는 클라이언트가
보낸 진단 정보이며, 전송 유실·위조·자동화 트래픽을 포함할 수 있다. 과금·사용량 집계에는 쓰지 않는다.
첫 프레임 도착 이후의 지속적인 화면 정지·화질은 이 작업의 측정 범위에 포함하지 않는다.

## 검증

`npm run test:experience`는 이벤트 중복·종료 이후 callback·진단 실패 격리,
권한 거부·첫 프레임 timeout·재시도 결과, 수집 API의 필드·origin·크기 제한,
composition callback·fallback·취소, 집계 분모·누락·중복을 검사한다.
배포 CI에도 같은 테스트를 실행한다.

실제 기기에서는 회원·게스트 연결, 권한 거부 후 재시도, 연결되었지만 영상이 없는 경우,
대기 중 종료, 페이지 이탈과 뒤로 가기, Safari 자동재생을 별도로 확인한다.

### 2026-10-04 로컬 검증

- 진단·수집·집계·codec 협상·DB 전달 테스트 16개, 기존 번역 키 테스트 3개 통과.
- 전체 ESLint, production build·TypeScript 검사 통과.
- production 서버의 수집 API 정상 요청 204와 실제 stdout JSON 기록 확인.
- Chromium 390px에서 서버·WebRTC를 모의 처리하고 canvas MediaStream을 재생해
  권한 거부, 재시도, 첫 프레임 전 완료 표시 방지, 실제 composition callback 이후 완료,
  정상 종료·track 정리, 첫 프레임 timeout, 대기 중 취소 확인. page error 없음.
- 이 테스트에서 수집한 로그의 집계 결과: 5개 시도, 첫 프레임 1개, 실패 3개,
  취소 1개, 정상 종료 1개, 누락 started·진행 중 시도 0개. 운영 성능 수치가 아니다.
- 실제 Safari 카메라로 게스트 체험에서 VP8 송수신, 원격 영상 1280×720 및
  첫 프레임 확인 후 완료 표시 확인. 재시도 1회에서 연결 796ms·첫 프레임 1223ms 기록.
  codec 우선순위 변경 전에는 전송 연결 뒤 첫 프레임 timeout 관측.
  H.264 처리 경로의 구체적인 실패 원인은 미확인.
- 로컬 개발에서는 loopback API gateway로 게스트 쿠키를 같은 site에서 전달해 검증.
  gateway는 저장소 밖 임시 실행 도구이며 운영 배포 구성을 변경하지 않는다.
- 운영 배포, 회원 인증 E2E 및 Safari back/forward cache 복귀는 미검증.

### DB 저장 확장 검증

- Next 전달·서비스 인증·사용자 헤더 제외·저장 실패·수집량 제한·proxy origin 및 브라우저 분류 테스트 통과.
- 실제 Next production 서버에서 수집 API→PostgreSQL까지 요청 4건·이벤트 3행 저장 확인.
  Next 프로세스 재시작 후 재전송도 204이며 행 수 3개 유지. 합성 이벤트를 사용한 로컬 검증이다.
- runtime env 주입 테스트 2개 및 배포 스크립트 bash 문법 검사 통과.
- PostgreSQL HTTP 저장·중복·서버 재시작 후 중복 방지·30일 보관 및 versioned migration 테스트 추가.
- 실제 운영 DB 마이그레이션과 runtime 키 설정·운영 배포는 미적용.
