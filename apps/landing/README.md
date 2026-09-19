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

| 경로 | 설명 |
| --- | --- |
| `/` | 인트로 후 홈(히어로, 프라이버시, 기능, FAQ) |
| `/pricing` | 요금제 |
| `/try-out` | 로그인 세션에 따른 게스트·회원 체험 진입 |
| `/try-out/experience` | WebRTC 체험 연결 |
| `/login`, `/signup` | 이메일 로그인·인증 회원가입 |
| `/privacy` | 개인정보 처리방침 (한국어 MDX) |
| `/terms` | 이용약관 (한국어 MDX) |
| `/support` | 고객 지원 (한국어 MDX) |
| `/en/privacy`, `/en/terms`, `/en/support` | 영어 MDX |
| `/ja/privacy`, `/ja/terms`, `/ja/support` | 일본어 MDX |
| `/ko/privacy`, `/ko/terms`, `/ko/support` | 접두사 없는 한국어 경로로 이동 |

## 인트로

스크롤에 묶인 일곱 장면이 약 1.75 뷰포트 동안 전환된 뒤 본문으로 이어집니다. 타이머 자동 재생은 없습니다. `본문으로 건너뛰기`와 `/#main`, `/#faq`는 인트로를 건너뜁니다. `prefers-reduced-motion: reduce`이면 인트로를 숨기고 본문을 바로 보여 줍니다. JavaScript가 없으면 인트로는 숨겨지고 본문에 바로 접근합니다.

## 다운로드 URL

스토어 링크는 저장소에 넣지 않습니다.

- `NEXT_PUBLIC_ANDROID_DOWNLOAD_URL`
- `NEXT_PUBLIC_IOS_DOWNLOAD_URL`

값이 없으면 Download 메뉴가 준비 중이라고 안내합니다.

## 인증·AI 경계

인증 서버 주소는 `NEXT_PUBLIC_INNOLIVE_SERVER_URL`에 설정합니다. 로그인 토큰은 Landing same-origin Route Handler가 HttpOnly Cookie로 보관하며, 브라우저 저장소에는 남기지 않습니다. 회원 체험은 이 세션으로 서버의 ICE 설정과 WebRTC 세션을 요청합니다.

게스트 체험은 서버의 게스트 대기열을 내부적으로만 사용하며, 순번·대기 인원은 표시하지 않습니다. 게스트 대기열은 서버의 Redis 및 `GUEST_QUEUE_ENABLED=true`, HTTPS Cookie, Landing origin의 CORS 허용 설정이 필요합니다.

## 정책

문서는 `content/documents/`의 `{privacy-policy,terms-of-service,support}.{ko,en,ja}.mdx`에서 편집합니다. 프론트매터 없이 본문만 둡니다. 기본 경로는 한국어 `/privacy`, `/terms`, `/support`이고, 영어는 `/en/…`, 일본어는 `/ja/…`입니다. `/ko/privacy`, `/ko/terms`, `/ko/support`는 접두사 없는 한국어 경로로 이동합니다. 게시일·시행일은 문서 본문에 있습니다. MDX는 `@next/mdx`로 로컬 컴파일합니다. 설정은 [Next.js MDX 가이드](https://nextjs.org/docs/app/guides/mdx)를 따릅니다.

랜딩의 결제와 얼굴 등록 UI는 포함하지 않습니다. 실제 WebRTC 연결 결과는 서버의 인증, CORS, ICE/TURN, 게스트 대기열 운영 설정에 따라 달라집니다. 문서 본문은 제품 전체의 처리·약관·지원을 설명합니다.
