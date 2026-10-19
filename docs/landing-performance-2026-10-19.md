# 2026-10-19 landing 성능 개선

대상은 `apps/landing` 웹 첫 화면이며 API·signaling 계약 변경은 없다.

## 변경 내용

- 제목 전체의 공간을 SSR에서 확보하고, 글자별 CSS visibility 애니메이션으로 타이핑 표시를 유지했다. 40ms마다 실행하던 React 상태 갱신을 제거했다.
- 모바일에서는 인트로를 표시하지 않으므로 타이핑과 설명 애니메이션이 hydration을 기다리지 않는다. 데스크톱은 인트로 완료 후 시작한다.
- 영상의 native poster로 첫 화면을 표시하고, load·인트로 완료·화면 노출 조건을 충족한 후 두 번의 animation frame을 거쳐 자동 재생한다. 화면 이탈·백그라운드 일시정지와 reduced-motion 포스터 표시를 유지했다.
- 인트로 전용 폰트의 전역 preload를 제거하고, 한국어 공통 폰트의 명시적 preload를 한국어 페이지로 제한했다. 폰트 파일과 글자 모양은 변경하지 않았다.

## 로컬 비교

Lighthouse 13.5.0, Chrome 155.0.8059.84, 기본 모바일 simulated throttling,
프로덕션 빌드를 `http://localhost:3221`에서 실행했다. 병렬 Lighthouse 실행 없이
각 조건을 순차 측정했다. 아래 한국어 수치는 각각 3회 중앙값이다.

| 항목 | 기준 `25d7f819` | 개선 `cf284918` |
| --- | ---: | ---: |
| Performance | 91 (83–94) | 93 (89–94) |
| LCP | 3.532초 | 3.144초 |
| TBT | 56ms | 19.5ms |
| CLS | 0.02246 | 0 |
| Speed Index | 1.748초 | 1.507초 |

`cf284918`의 언어별 단일 측정은 모바일 한국어 93, 영어 97, 일본어 92점이며,
데스크톱 한국어·영어 100, 일본어 98점이었다. 인트로 뒤 영상 재생을 차단한
`a887fa72`의 일본어 데스크톱 재측정은 99점, LCP 0.828초, TBT·CLS 0이었다.
이 단일 측정들을 중앙값과 섞어 해석하지 않는다. 모바일 100점은 아직 확인하지 못했다.

로컬 측정의 접근성·SEO는 100점이다. Best Practices 96점은 로컬 analytics 저장소
미설정으로 `/api/analytics`가 503을 반환한 결과이며, 이를 숨기도록 앱을 변경하지 않았다.
기록한 측정에는 Lighthouse 로딩 시간 제한 경고가 없다.

## 동작 검증

- 프로덕션 빌드·TypeScript·변경 파일 ESLint 통과.
- 한국어 데스크톱 제목·흐림 효과·폰 이미지 배치 확인.
- 412×823 모바일 영어·일본어 가로 넘침 없음, 영상 자동 재생 확인.
- reduced-motion 전환 후 영상 source 제거·일시정지·포스터 유지 확인.
- 모바일 JavaScript 실행을 막은 상태에서 제목 전체 표시·설명 opacity 1 확인.
- 데스크톱 인트로 진행 중 영상 source 없음, 건너뛰기 후 source 연결·재생 확인.

## 운영 확인

변경 전 운영 revision `25d7f819`의 한국어 모바일 단일 측정은 97점,
LCP 2.605초, TBT 37ms, CLS 0.02243이었다. 로컬 결과와 운영 결과를 직접 증감 비교하지 않는다.
운영 배포는 main 병합 후 GitHub Actions에서 실행되며, 변경 후 운영 측정은 아직 수행하지 않았다.

수치 근거는 `docs/landing-performance-evidence/2026-10-19/summary.json`에 있다.
원본 JSON은 로컬 `/Users/chaeyn/Documents/Codex/2026-10-19/innolive-performance-669/`에 보존했다.
