# 웹 체험 품질 측정

대상은 `apps/landing`의 회원·게스트 체험이다. 공유 서버 API와 WebRTC signaling
payload는 유지한다. 추가하는 `/api/experience-quality`는 landing 내부의 진단 수집 API다.

## 연결과 영상 표시

- WebRTC `connected`는 전송 연결 완료다. 이 상태만으로 처리된 영상이 보인다고 판단하지 않는다.
- 원격 video의 `requestVideoFrameCallback`으로 첫 프레임의 composition 제출을 확인한다.
  미지원 브라우저는 `playing` 또는 `loadeddata` 시 재생 상태·decoded dimensions를 확인한다.
  fallback은 실제 화면 composition 확인보다 약한 근거다.
- 연결과 첫 프레임을 모두 확인한 뒤 기존 체험 완료 문구·얼굴 등록 버튼을 표시한다.
- 연결 제한 시간은 기존 30초, 연결 후 첫 프레임 제한 시간은 15초다.
  게스트 대기열·권한 허용 시간은 이 제한 시간에 포함하지 않는다.
- 대기 중 종료할 수 있다. 재시도·페이지 이탈·pagehide에서 카메라, 연결, 프레임 관찰을 정리한다.
  back/forward cache 복귀 시 새 시도로 연결한다. 이전 시도의 callback은 현재 상태를 바꾸지 않는다.

## 수집 API v1

같은 origin의 `POST /api/experience-quality`, `application/json`, 최대 1024 bytes.
정상 수신은 204, 잘못된 값은 400, 다른 origin은 403, 크기 초과는 413,
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
- attemptId는 시도마다 생성하는 UUID다. 쿠키·localStorage에 저장하지 않는다.
  회원 ID, 서버 세션 ID, ticket ID, 토큰, URL, IP, User-Agent, SDP, ICE candidate,
  얼굴 사진, 영상, 원본 오류 메시지는 진단 JSON에 넣지 않는다. 추가 필드는 거부한다.

브라우저는 beacon 또는 keepalive fetch로 전송한다. 실패해도 체험을 중단하지 않는다.
앱은 `kind=experience_quality`, 수신 시각과 허용 필드를 stdout JSON으로 기록한다.
수집기는 DB·외부 Analytics에 저장하지 않는다. 보관 기간과 접근 권한은 배포 환경의
컨테이너 로그 정책을 따른다. reverse proxy의 일반 접속 로그는 별도다.

## 집계

로그 파일 또는 stdin을 입력한다. 앞에 Docker timestamp가 있어도 읽는다.

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

- 진단·수집·집계 테스트 9개, 기존 번역 키 테스트 3개 통과.
- 전체 ESLint, production build·TypeScript 검사 통과.
- production 서버의 수집 API 정상 요청 204와 실제 stdout JSON 기록 확인.
- Chromium 390px에서 서버·WebRTC를 모의 처리하고 canvas MediaStream을 재생해
  권한 거부, 재시도, 첫 프레임 전 완료 표시 방지, 실제 composition callback 이후 완료,
  정상 종료·track 정리, 첫 프레임 timeout, 대기 중 취소 확인. page error 없음.
- 이 테스트에서 수집한 로그의 집계 결과: 5개 시도, 첫 프레임 1개, 실패 3개,
  취소 1개, 정상 종료 1개, 누락 started·진행 중 시도 0개. 운영 성능 수치가 아니다.
- 운영 배포, 실제 서버 연결·AI 처리, 회원 인증 E2E, 실제 Safari·카메라 및
  back/forward cache 복귀는 미검증.
