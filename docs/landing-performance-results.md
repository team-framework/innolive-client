# InnoLive Web 랜딩 최적화 결과

2026-10-03. 대상은 `apps/landing`의 Web 랜딩이다. HTTP·signaling 계약 변경은 없다.

이미지·영상·폰트 담당 서브에이전트 세 명의 변경을 통합했다. 기존 작업을 보존하기 위해 `origin/main`의 `783b2ba7aea50f277e4698be2299e7bc41dbed9d`에서 만든 별도 worktree에 적용했다. 운영 배포 전 로컬 측정 결과다.

## SEO 조치 전 코드까지 포함한 비교

첫 기술 SEO 구현 `329abf5`의 부모 커밋 `c8e88d4600e6469f150af13d0a40016c5ad112d4`를 `git archive`로 추출했다. 앱 코드를 수정하지 않고 당시 lockfile로 의존성을 설치해 production build를 측정했다. 기존 제목·설명·언어 태그는 남아 있으며, 이후 추가한 canonical·hreflang·OG/Twitter·robots·sitemap·검색어 FAQ 조치는 없다. 메인 미디어는 당시 `Phone1.gif` 그대로다.

| 상태 | 코드 | 측정 요약 |
|---|---|---|
| SEO 조치 전 | `c8e88d4`, 첫 SEO 구현 직전 | `landing-performance-evidence/pre-seo/` |
| 기존 기준 | `783b2ba`, SEO·GIF→MP4 등 후속 변경 적용 상태 | `landing-performance-evidence/baseline-default/` |
| 이번 최적화 후 | `783b2ba` + 이번 worktree 변경 | `landing-performance-evidence/final/` |

기존 보고서의 “전”은 SEO 조치가 이미 적용된 `baseline-default`였다. 아래는 SEO 조치 전 코드까지 추가한 세 단계 비교다. 각 단계에서 같은 Lighthouse·Chrome·모바일/데스크톱 preset으로 세 번 측정했다. 성능·전송량·LCP는 중앙값이다.

| 상태 | 화면 | 성능 | 접근성 | 권장사항 | SEO |
|---|---|---:|---:|---:|---:|
| SEO 조치 전 | 모바일 | 75 | 100 | 100 | 100 |
| SEO 조치 전 | 데스크톱 | 75 † | 100 | 100 | 100 |
| SEO·MP4 적용 후 기존 기준 | 모바일 | 75 | 100 | 100 | 100 |
| SEO·MP4 적용 후 기존 기준 | 데스크톱 | 75 | 100 | 100 | 100 |
| 이번 최적화 후 | 모바일 | 97 (96–100) | 100 | 100 | 100 |
| 이번 최적화 후 | 데스크톱 | 100 | 100 | 100 | 100 |

† SEO 조치 전 데스크톱 세 번 모두 Lighthouse의 45초 로딩 시간 제한 경고가 발생했다. Lighthouse는 “결과가 불완전할 수 있음”을 표시했다. runtimeError는 없었고 GIF 요청은 완료됐지만, 이 단계 데스크톱 수치는 경고를 포함한 참고값으로 읽어야 한다. 모바일 세 번과 기존 기준·최종 측정에는 이 경고가 없다. 경고 원문은 `landing-performance-evidence/pre-seo/summary.json`에 보존했다. 대용량 raw report는 로컬 측정 디렉터리에 보존하고 Git에는 포함하지 않았다.

| 화면 | 초기 전송량: SEO 전 → 기존 기준 → 최종 | SEO 전 대비 감소 |
|---|---:|---:|
| 모바일 | 52.53 → 10.20 → 1.12 MB | 97.9% |
| 데스크톱 † | 57.05 → 14.71 → 1.76 MB | 96.9% |

| 화면 | Lighthouse 모의 LCP: SEO 전 → 기존 기준 → 최종 |
|---|---:|
| 모바일 | 251.81 → 36.41 → 2.65초 |
| 데스크톱 † | 42.90 → 7.77 → 0.63초 |

LCP는 Lighthouse의 simulated throttling으로 계산한 값이다. 모바일의 251.81초는 이 저속망 모의 조건에서 43.99MB GIF를 포함한 요청 그래프를 모델링한 결과이며, 로컬 브라우저에서 실제로 그 시간 동안 기다렸다는 뜻은 아니다.

이 비교에는 SEO 메타데이터 외에 GIF→MP4 전환과 이후 이미지·영상·폰트·애니메이션 변경도 포함한다. 성능 개선을 SEO 메타데이터만의 효과로 해석하지 않는다.

## 기술 SEO 직접 확인

당시 존재했던 공통 27개 페이지(한·영·일 × 홈·가격·체험·체험 실행·로그인·가입·개인정보·약관·지원)의 초기 HTML과 엔드포인트를 로컬 서버에서 확인했다.

| 확인 항목 | SEO 조치 전 | 최종 코드 |
|---|---:|---:|
| HTTP 200 페이지 | 27/27 | 27/27 |
| title | 27/27 | 27/27 |
| description | 27/27 | 27/27 |
| canonical | 0/27 | 27/27 |
| hreflang | 0/27 | 27/27 |
| OG title | 0/27 | 27/27 |
| Twitter card | 0/27 | 27/27 |
| `/robots.txt` | HTTP 404 | HTTP 200 |
| `/sitemap.xml` | HTTP 404 | HTTP 200 |

SEO 조치 전에도 Lighthouse SEO는 100점이었다. raw report에서 canonical과 robots.txt는 `notApplicable`로 점수 계산에서 빠졌고, hreflang은 태그가 없는 상태에서도 통과했다. 이 점수만으로 검색 설정 누락을 판단할 수 없다. 위 직접 확인 결과와 함께 읽어야 한다. 검색 색인·순위·노출 변화는 이 로컬 검사로 확인하지 않았다.

## 변경

- 이미지: PNG 여섯 개를 WebP로 변환해 원본 파일 합계 12,329,177 → 876,648 bytes로 줄였다. 반응형 이미지를 사용하고 개인정보 섹션은 화면 접근 시 요청한다. 폰 프레임의 전면은 25,858-byte WebP를 HTML에 포함한다.
- 영상: 데스크톱 MP4 1,526,576 → 1,034,419 bytes. 모바일용 534,549-byte 영상을 추가했다. 포스터·페이지 로딩 후 자동재생하며, 화면 밖·백그라운드에서 정지하고 동작 줄이기 설정에서는 소스를 제거한다. 표시 폭×DPR에 따라 영상 해상도를 선택한다.
- 폰트: 1,289,292-byte 원본을 Unicode 범위로 나눴다. 한국어 첫 화면은 Latin+공통 문자 77,904 bytes를 사용한다. 원본 12,032개 코드포인트, 굵기 400–1000, 글자 advance를 보존했다.
- 애니메이션: 모바일 인트로 이미지를 요청하지 않고, GSAP와 하단 섹션 애니메이션을 화면을 그린 뒤 초기화한다. 로딩 지연·실패·건너뛰기 시 기본 스크롤을 복구한다.
- 접근성: 기능 카드에 이미지 역할을 명시하고 보조 텍스트 색상을 #767676 → #6b6b6b로 바꿨다.

## 파일

- 이미지·영상: `apps/landing/components/{hero,phone-artwork,hero-demo-video,privacy-section}.tsx`, `lib/privacy-slides.ts`, `public/landing/`, `public/mockups/`, `lib/hero-phone-inline.ts`, `scripts/optimize-landing-images.mjs`.
- 폰트: `apps/landing/app/fonts.ts`, `app/[locale]/layout.tsx`, `app/fonts/wanted-sans.css`, `app/fonts/wanted-sans/subsets/`, `public/fonts/`, `scripts/subset-wanted-sans.py`.
- 애니메이션·접근성: `components/{intro-scroll,smooth-wrapper,feature-section,faq-section}.tsx`, `lib/landing-animation.ts`, `app/globals.css`, `scripts/check-{intro,privacy,animation-loading}.cjs`.
- 재생성·검증 절차: `docs/landing-performance.md`, `docs/landing-performance-media.md`, `apps/landing/app/fonts/wanted-sans/README.md`.

## 검증

- `npm run build`, `tsc --noEmit`, 변경 파일 ESLint, `git diff --check` 통과.
- `npm run test:i18n` 3개 통과. 인트로·개인정보 애니메이션·지연 초기화 검사 통과.
- Chrome에서 한·영·일 모바일/데스크톱 여섯 화면을 확인했다. console 오류·HTTP 실패·가로 넘침이 없었다. 동작 줄이기 설정에서는 개인정보 첫 슬라이드만 보이며, 숨긴 슬라이드의 이미지 미요청은 정상이다.
- 실제 데스크톱 휠 스크롤로 개인정보 슬라이드 1→2→3의 전환과 세 이미지 로딩을 확인했다. FAQ 펼침, 인트로 정상/로딩 지연 중 건너뛰기/파일 요청 실패 후 스크롤 복구를 확인했다.
- 모바일 영상 자동재생→화면 밖 정지→복귀 재개→동작 줄이기 소스 제거를 확인했다. DPR 1.75/3에서 영상 선택 및 모바일 인트로 미요청을 확인했다.

## 측정 조건과 해석

- Lighthouse 13.5.0, Headless Chrome 154, Next.js 16.3.5 기본 Turbopack production build.
- 모바일: 412×823, DPR 1.75, 기본 simulated throttling(150ms RTT, 1638.4Kbps, CPU×4). 데스크톱: 1350×940, 기본 desktop preset.
- 측정 URL: SEO 조치 전 `http://localhost:3212/ko`, 기존 기준 `http://localhost:3210/ko`, 최종 `http://localhost:3211/ko`. 각 실행은 새 Lighthouse Chrome 세션이며 브라우저 검증과 동시에 실행하지 않았다.
- 커밋한 측정 요약은 `docs/landing-performance-evidence/{pre-seo,baseline-default,final}/`에 있다. Lighthouse raw JSON·HTML과 화면 캡처는 로컬 `output/landing-performance-2026-10-03/`에 보존하며 Git에는 포함하지 않았다. 모바일 최종 LCP는 1.90–2.73초로 변동한다. 한 번의 100점을 지속적인 만점으로 해석하지 않는다.
- 폰 프레임 별도 요청을 높은 우선순위 preload로 고친 비교 실행(`final-priority/`)은 모바일 92/92/91점이었다. 전면 HTML 내장은 약 49KB(4.6%) 전송을 더하면서 최종 100/97/96점으로 개선했다. 다른 조건으로 얻은 점수를 섞지 않았다.
- 이 결과는 로컬 빌드다. 운영 사이트는 이번 변경을 배포하지 않았으며, 운영 점수와 검색 노출·순위는 이 결과로 확인하지 않았다.

근거: `docs/landing-performance-evidence/`의 코드 기준·측정 요약·HTML 검사·브라우저 검증 JSON. raw report와 화면 캡처는 로컬 측정 디렉터리에 보존.
