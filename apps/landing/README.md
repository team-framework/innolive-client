# InnoLive landing

Next.js 16 App Router 사이트입니다. 마케팅 페이지와 UI 미리보기만 포함합니다. 로그인, 회원가입, 결제, 카메라, AI 비식별화는 동작하지 않습니다.

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
| `/try-out` | 비식별화 체험 **화면 미리보기** |
| `/login`, `/signup` | 계정 UI. 제출 시 준비 중 안내만 |
| `/privacy` | 개인정보 처리방침 (한국어 MDX) |
| `/terms` | 이용약관 (한국어 MDX) |
| `/support` | 고객 지원 (한국어 MDX) |
| `/ko/privacy` | `/privacy`로 이동 |

## 인트로

스크롤에 묶인 일곱 장면이 약 1.75 뷰포트 동안 전환된 뒤 본문으로 이어집니다. 타이머 자동 재생은 없습니다. `본문으로 건너뛰기`와 `/#main`, `/#faq`는 인트로를 건너뜁니다. `prefers-reduced-motion: reduce`이면 인트로를 숨기고 본문을 바로 보여 줍니다. JavaScript가 없으면 인트로는 숨겨지고 본문에 바로 접근합니다.

## 다운로드 URL

스토어 링크는 저장소에 넣지 않습니다.

- `NEXT_PUBLIC_ANDROID_DOWNLOAD_URL`
- `NEXT_PUBLIC_IOS_DOWNLOAD_URL`

값이 없으면 Download 메뉴가 준비 중이라고 안내합니다.

## 인증·AI 경계

폼은 브라우저에만 있고 자격 증명을 보내지 않습니다. 체험 화면의 게스트/체험 중/회원은 미리보기이며 로그인 상태가 아닙니다. 카메라와 실시간 비식별화는 호출하지 않습니다. 실제 동작이 없는 버튼은 `체험 기능을 준비 중입니다`처럼 짧게 알립니다.

## 정책

한국어 문서는 `content/documents/`의 `privacy-policy.ko.mdx`, `terms-of-service.ko.mdx`, `support.ko.mdx`에서 편집합니다. 프론트매터 없이 본문만 두고, `/privacy`, `/terms`, `/support`가 이 파일을 그대로 렌더합니다. 게시일·시행일은 문서 본문에 있습니다. 다른 언어 연결은 이후 단계에서 다룹니다.
