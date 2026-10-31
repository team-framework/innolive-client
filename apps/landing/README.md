# InnoLive landing

Next.js 16 App Router 사이트입니다. 마케팅 페이지와 이메일 인증 로그인, WebRTC 체험을 포함합니다. 결제와 얼굴 등록 UI는 포함하지 않습니다.

## 개발

```bash
pnpm install
pnpm dev
```

빌드:

```bash
pnpm build
pnpm start
```

## 라우트

모든 페이지는 `/ko`, `/en`, `/ja` 접두사를 사용합니다. 접두사가 없는 경로는 `NEXT_LOCALE` 쿠키, `Accept-Language`, 기본값 `ko` 순으로 로케일을 붙여 이동합니다. 언어 페이지의 미리 불러오기는 `NEXT_LOCALE`을 변경하지 않습니다. 지원하지 않는 두 글자 코드(`/fr`)는 404입니다.

| 경로 | 설명 |
| --- | --- |
| `/ko`, `/en`, `/ja` | 인트로 후 홈(히어로, 프라이버시, 기능, FAQ) |
| `/ko/pricing`, `/en/pricing`, `/ja/pricing` | 요금제 |
| `/ko/try-out` 등 | 로그인 세션에 따른 게스트·회원 체험 진입 |
| `/ko/try-out/experience` 등 | WebRTC 체험 연결 |
| `/ko/login`, `/ko/signup` 등 | 이메일 로그인·인증 회원가입 |
| `/ko/privacy`, `/en/privacy`, `/ja/privacy` | 개인정보 처리방침 MDX |
| `/ko/terms`, `/en/terms`, `/ja/terms` | 이용약관 MDX |
| `/ko/support`, `/en/support`, `/ja/support` | 고객 지원 MDX |

## 인트로

스크롤·터치 이동량에 비례해 일곱 장면과 본문 사이의 디졸브 전환이 즉시 반영됩니다. 화면 높이 대비 이동 비율에 `components/intro-scroll.tsx`의 `INTRO_SCROLL_SPEED`를 곱해 전환이 진행되고, 역방향으로 스크롤하면 전환도 되돌아갑니다. 마지막 ‘당신에게만 집중’ 장면이 완전히 나타난 뒤에는 `INTRO_FOCUS_SCROLL_RANGE`만큼 스크롤 구간을 추가해 장면을 유지합니다. 값 `1`은 일반 전환에 필요한 스크롤 거리와 같으며, 시간 대기는 없습니다. 키보드는 한 전환씩 진행합니다. 타이머 자동 재생은 없습니다. 인트로 입력은 본문 스크롤에 전달되지 않으며 완료 후 본문 상단에서 시작합니다. 완료 시 인트로와 진행 중인 본문 스크롤 애니메이션을 멈추고 위치를 상단으로 맞춥니다. 인트로가 사라진 뒤 `INTRO_EXIT_SCROLL_LOCK_MS`에 지정된 고정 시간(ms 단위) 동안 입력을 차단합니다. 이 시간은 새 입력이 들어와도 연장하지 않습니다. 완료 상태는 현재 문서에만 유지되어 홈 재방문 시에는 건너뛰고 새로고침 시에는 다시 표시합니다. `본문으로 건너뛰기`와 `/#main`, `/#faq`는 인트로를 건너뜁니다. 모바일 또는 `prefers-reduced-motion: reduce`이면 인트로를 숨기고 본문을 바로 보여 줍니다. JavaScript가 없으면 인트로는 숨겨지고 본문에 바로 접근합니다.

기능 카드의 스크롤 애니메이션 거리는 최대 640px입니다.

## 다운로드 URL

스토어 링크는 저장소에 넣지 않습니다.

- `NEXT_PUBLIC_ANDROID_DOWNLOAD_URL`
- `NEXT_PUBLIC_IOS_DOWNLOAD_URL`

값이 없으면 Download 메뉴가 준비 중이라고 안내합니다. 배포 환경에서는 이미지 빌드 전에 URL을 설정해야 합니다.

## InnoLive Video (로그인 전용)

`/[locale]/video`는 로그인한 사용자가 녹화 영상을 업로드하면 비식별화 결과를 받는 페이지입니다. 비공개 페이지이므로 sitemap에서 제외하고 `noindex`로 처리합니다.

플래그 두 개로 제어합니다.

- `NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED` (빌드 타임): 정확히 `1` 또는 `true`일 때만 기능을 켭니다. 꺼져 있으면 `/[locale]/video`가 404를 반환하고, 헤더 등 어디에도 진입 링크가 렌더링되지 않으며, 목업 라우트도 비활성입니다. `Dockerfile`의 `ARG NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED=true` 기본값으로 프로덕션 이미지는 켜진 상태로 빌드되며, `--build-arg NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED=false`로 끌 수 있습니다. 로컬 `pnpm build`는 값을 주지 않으면 꺼진 상태입니다.
- `NEXT_PUBLIC_VOD_MOCK` (개발 전용): `1`/`true`이고 `NODE_ENV`가 `production`이 아니며 위 비디오 플래그가 켜져 있을 때만 목업을 사용합니다. 세 조건 중 하나라도 어긋나면 목업은 동작하지 않습니다. 프로덕션 빌드(`NODE_ENV=production`)에서는 이 값이 박혀 있어도 라우트 핸들러와 클라이언트 모두 목업을 쓰지 않습니다. 실서버 주소(`NEXT_PUBLIC_INNOLIVE_SERVER_URL`)가 없거나 절대 http(s) URL이 아니면 목업으로 대체하지 않고 이용 불가 화면을 표시합니다.

VOD API 호출은 `lib/vod-client.ts` 한 곳에 격리합니다. 타입·베이스 URL 해석·응답 파싱/검증·상태 매핑이 모두 여기에 있으며, 계약이 바뀌면 이 파일만 수정합니다. 클라이언트는 `team-framework/innolive-server`의 확정 계약(`feat/vod-jobs/#521`의 `api/vod/openapi.yaml`)에 맞춰져 있습니다.

- 경로 접두사는 `/vod`입니다(`/api/v1` 아님). 실서버는 `${NEXT_PUBLIC_INNOLIVE_SERVER_URL}/vod/jobs`에 `/api/auth/access-token`으로 받은 Bearer 토큰으로 호출합니다.
- wire 필드는 snake_case(`job_id`, `queue_position`, `eta_sec`, `created_at`, `updated_at`, `completed_at`, `original_delete_at`, `result_expires_at`, `failure`)이고, `progress`는 0..100 정수 백분율입니다. camelCase·0..1 정규화는 클라이언트에서 처리합니다.
- 계약에는 원본 파일명이 없습니다(다운로드 이름은 서버가 `vod-{job_id}.mp4`로 생성). 표시용 파일명은 업로드 시 브라우저가 기억합니다.
- 계약에는 `cancelled` 상태가 **없습니다**. DELETE가 취소 + 논리 삭제를 함께 수행하므로 삭제된 작업은 목록에서 사라집니다(이후 404). 다만 서버가 혹시 `cancelled`를 보내더라도 UI가 깨지지 않도록 방어적으로 해당 상태를 수용·표시합니다.
- 페이지네이션(`next_cursor`)은 계약에 정의돼 있으나 현재는 첫 페이지만 사용합니다(긴 이력은 후속 과제).

한도 상수는 `lib/vod-limits.ts`에 모읍니다(최대 2GB·60분, 결과 7일 보관, 원본은 처리 완료 후 24시간 이내 파기).

### 프로덕션 활성화·확인·롤백

프로덕션 이미지는 배포 호스트가 `/etc/innolive/web-deploy.env`의 값으로 이 `Dockerfile`을 직접 빌드합니다(`scripts/deploy-innolive-web.sh`의 `write_release_override`). GitHub 저장소 변수는 CI 검증 이미지에만 닿고 운영 빌드에는 전달되지 않으므로, 비디오 플래그는 `Dockerfile` 기본값으로 켭니다. VOD API 베이스 URL은 이미 운영 필수값인 `NEXT_PUBLIC_INNOLIVE_SERVER_URL` 뒤에 `/vod`를 붙여 사용합니다.

머지 조건: innolive-server(`/vod/jobs`)와 innolive-ai VOD 처리기가 프로덕션에서 동작 중이어야 합니다.

배포 후 확인:

- `/ko/video` 로그인 상태 200(미로그인은 로그인 화면으로 이동), `/en/video`·`/ja/video` 동일
- 로그인 후 헤더에 ko·en·ja 모두 Video 링크 표시
- 짧은 테스트 영상 업로드 후 대기(queued) → 처리(processing) → 완료(done) 전환과 결과 다운로드
- `/api/dev-vod/jobs` 404
- 브라우저 네트워크 탭의 VOD 요청이 `${NEXT_PUBLIC_INNOLIVE_SERVER_URL}/vod/jobs`(실서버)로 향하고 `/api/dev-vod`가 없음

롤백:

- 플래그를 켠 커밋을 `git revert`해 main에 머지하면 자동 배포되고, 이후 `/[locale]/video`는 404, 헤더 링크는 사라집니다.
- 배포 호스트의 `/opt/innolive/deploy/deploy-innolive-web.sh`를 이 저장소 버전으로 갱신해 둔 경우에는 `/etc/innolive/web-deploy.env`에 `NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED=false`를 넣고 `Deploy InnoLive landing` 워크플로를 main에서 다시 실행해도 됩니다(값이 없으면 `Dockerfile` 기본값 사용).

### 개발용 목업

`NODE_ENV!=production`이며 두 플래그가 켜진 경우에만 `app/api/dev-vod/*` 라우트 핸들러가 동작합니다. 프로덕션 빌드에서는 플래그와 무관하게 404를 반환합니다.

- 라우트: `POST/GET /api/dev-vod/jobs`, `GET/DELETE /api/dev-vod/jobs/{id}`, `GET /api/dev-vod/jobs/{id}/result`
- 인증: 랜딩 세션 쿠키(`accessToken`)만 있으면 됩니다. 토큰 유효성은 검사하지 않으므로 로컬 테스트 픽스처로 충분합니다.
- 타임라인(`lib/vod-mock-timeline.ts`, 순수 함수): 업로드됨 → 대기(순번 3→1) → 처리 → 일시정지(라이브 우선) → 처리 → 완료, 전체 약 70초. `?speed=` 쿼리로 가속할 수 있습니다.
- 응답은 확정 계약의 wire 형식(snake_case, 0..100 정수 progress, `failure`/`completed_at`)을 그대로 흉내 냅니다. 완료 시 결과물은 업로드한 바이트를 그대로 돌려주며 `result_expires_at = 완료 + 7일`, `original_delete_at = 완료 + 24시간`을 제공합니다.
- 실행 예: `NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED=1 NEXT_PUBLIC_VOD_MOCK=1 pnpm dev`

순수 로직은 `pnpm test:vod`로 검증합니다(한도·플래그·타임라인·포맷·클라이언트 파싱/상태 매핑).

## 인증·AI 경계

인증 서버 주소는 `NEXT_PUBLIC_INNOLIVE_SERVER_URL`에 설정합니다. 로그인 토큰은 Landing same-origin Route Handler가 HttpOnly Cookie로 보관하며, 브라우저 저장소에는 남기지 않습니다. 회원 체험은 이 세션으로 서버의 ICE 설정과 WebRTC 세션을 요청합니다.

게스트 체험은 서버의 게스트 대기열을 내부적으로만 사용하며, 순번·대기 인원은 표시하지 않습니다. 게스트 대기열은 서버의 Redis 및 `GUEST_QUEUE_ENABLED=true`, HTTPS Cookie, Landing origin의 CORS 허용 설정이 필요합니다.

## 정책

문서는 `content/documents/`의 `{privacy-policy,terms-of-service,support}.{ko,en,ja}.mdx`에서 편집합니다. 프론트매터 없이 본문만 둡니다. 화면 문구는 `messages/{ko,en,ja}.json`에서 편집하며 세 파일의 키를 같게 유지합니다. 게시일·시행일은 문서 본문에 있습니다. MDX는 `@next/mdx`로 로컬 컴파일합니다. 설정은 [Next.js MDX 가이드](https://nextjs.org/docs/app/guides/mdx)를 따릅니다. 인트로 장면의 손글씨 이미지는 한국어 아트워크입니다.

랜딩의 결제와 얼굴 등록 UI는 포함하지 않습니다. 실제 WebRTC 연결 결과는 서버의 인증, CORS, ICE/TURN, 게스트 대기열 운영 설정에 따라 달라집니다. 문서 본문은 제품 전체의 처리·약관·지원을 설명합니다.

## Privacy 데모 슬라이드

`components/privacy-section.tsx`의 `PRIVACY_ANIMATION_DELAY_MS`로 도착 후 대기 시간을 ms 단위로 조절합니다(기본 400). 데스크톱은 첫 도착 시 첫 슬라이드와 스크롤 위치를 고정하고, 지정 시간이 지나면 스크롤에 따른 전환을 허용합니다. 대기 중 입력이 들어와도 시간이 연장되지 않습니다. 모바일은 같은 시간만큼 대기한 뒤 등장 애니메이션을 시작합니다.

`lib/privacy-slides.ts`의 `privacySlideSources` 배열에 `public` 기준 이미지 경로를 지정합니다. 설명과 대체 텍스트는 `messages/{ko,en,ja}.json`의 `privacy.slides`에 둡니다.
