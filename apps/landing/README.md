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

모든 페이지는 `/ko`, `/en`, `/ja` 접두사를 사용합니다. 접두사가 없는 경로는 `NEXT_LOCALE` 쿠키, `Accept-Language`, 기본값 `ko` 순으로 로케일을 붙여 이동합니다. 지원하지 않는 두 글자 코드(`/fr`)는 404입니다.

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

스크롤·터치·키보드 입력으로 일곱 장면이 디졸브 전환한 뒤 본문으로 디졸브 전환됩니다. 타이머 자동 재생과 인트로 중 본문 스크롤 이동은 없습니다. 완료 상태는 탭마다 `sessionStorage`에 저장되어 새로고침 또는 홈 재방문 시 인트로를 다시 보이지 않습니다. `본문으로 건너뛰기`와 `/#main`, `/#faq`는 인트로를 건너뜁니다. `prefers-reduced-motion: reduce`이면 인트로를 숨기고 본문을 바로 보여 줍니다. JavaScript가 없으면 인트로는 숨겨지고 본문에 바로 접근합니다.

## 다운로드 URL

스토어 링크는 저장소에 넣지 않습니다.

- `NEXT_PUBLIC_ANDROID_DOWNLOAD_URL`
- `NEXT_PUBLIC_IOS_DOWNLOAD_URL`

값이 없으면 Download 메뉴가 준비 중이라고 안내합니다.

## 인증·AI 경계

인증 서버 주소는 `NEXT_PUBLIC_INNOLIVE_SERVER_URL`에 설정합니다. 로그인 토큰은 Landing same-origin Route Handler가 HttpOnly Cookie로 보관하며, 브라우저 저장소에는 남기지 않습니다. 회원 체험은 이 세션으로 서버의 ICE 설정과 WebRTC 세션을 요청합니다.

게스트 체험은 서버의 게스트 대기열을 내부적으로만 사용하며, 순번·대기 인원은 표시하지 않습니다. 게스트 대기열은 서버의 Redis 및 `GUEST_QUEUE_ENABLED=true`, HTTPS Cookie, Landing origin의 CORS 허용 설정이 필요합니다.

## 정책

문서는 `content/documents/`의 `{privacy-policy,terms-of-service,support}.{ko,en,ja}.mdx`에서 편집합니다. 프론트매터 없이 본문만 둡니다. 화면 문구는 `messages/{ko,en,ja}.json`에서 편집하며 세 파일의 키를 같게 유지합니다. 게시일·시행일은 문서 본문에 있습니다. MDX는 `@next/mdx`로 로컬 컴파일합니다. 설정은 [Next.js MDX 가이드](https://nextjs.org/docs/app/guides/mdx)를 따릅니다. 인트로 장면의 손글씨 이미지는 한국어 아트워크입니다.

랜딩의 결제와 얼굴 등록 UI는 포함하지 않습니다. 실제 WebRTC 연결 결과는 서버의 인증, CORS, ICE/TURN, 게스트 대기열 운영 설정에 따라 달라집니다. 문서 본문은 제품 전체의 처리·약관·지원을 설명합니다.

## Privacy 데모 슬라이드

`lib/privacy-slides.ts`의 `privacySlides` 배열에서 항목을 추가하거나 삭제합니다. 각 항목의 `src`에 `/landing/example.gif`처럼 `public` 기준 경로를 넣으면 최적화하지 않은 원본 이미지 또는 GIF를 표시합니다. `src`가 비어 있으면 `label`을 자리표시 카드로 표시합니다.
