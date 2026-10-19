# 랜딩 검색·AI 노출 진단 (2026-10-19 KST)

## 변경 전 기준선

이 문서는 구현 전에 작성했다. 대상은 `apps/landing` 웹 랜딩의 공개 콘텐츠·메타데이터·검색용 응답이다. HTTP API와 WebRTC 계약 영향은 없다. 기존 미커밋 SEO·번역·UI 변경은 시작 시점의 기준선으로 보존한다.

적용 자료: [fire-your-seo-agency](https://github.com/leopard627/fire-your-seo-agency), commit `5f7249f7eff119703f2a509d0dc5e21b7b3ccc4c`의 SKILL.md 및 한국어 references. 특정 사이트의 성과나 추정 알고리즘은 InnoLive의 성과로 사용하지 않는다. llms.txt는 보조 문서이며 검색 등록이나 모델 학습을 보장하지 않는다.

실서비스에 인증 없이 HTTP GET을 보내고 JavaScript를 실행하지 않은 응답을 확인했다.

| 항목 | 변경 전 관측 |
| --- | --- |
| `/ko`, `/ko/live-mosaic` | 각각 200, h1 1개, canonical 존재, JSON-LD 존재, X-Robots-Tag 없음 |
| `/robots.txt` | 200, `*` 허용, `/api/` 차단, sitemap 안내 |
| `/sitemap.xml` | 200, URL 54개 |
| `/llms.txt`, `/llms-full.txt`, `/feed.xml` | 각각 404 |
| `/ko/seo-audit-missing-20261019` | 404 |
| 네이버 소유 확인 메타 | 대표 HTML에 존재. 계정의 등록·수집 완료 여부는 미확인 |

| 영역 | 상태 | 근거·개선 대상 |
| --- | --- | --- |
| SEO | 보완 | canonical·다국어 사이트맵 구현됨. 전체 URL의 원시 HTML 회귀 검증을 자동화할 필요 |
| AEO | 보완 | guide intro·FAQ를 SSR과 JSON-LD에서 공유. 블로그 Article의 publisher 연결 부족 |
| GEO | 보완 | 공개 크롤링 허용. llms 안내와 전문 없음 |
| LLMO | 보완 | 공통 Organization ID 존재. 화면의 공식 소셜 링크와 sameAs 연결 없음 |
| NEO | 보완 | 한국어 본문·사이트맵·소유 확인 메타 존재. Yeti 정책 명시와 배포 후 수집 확인 필요 |
| 콘텐츠 운영 | 보완 | 언어별 가이드 11종, 그중 blog 2종. RSS 없음. 검색어 기반 백로그·재측정 절차 필요 |

노출·클릭·순위·AI 인용·봇 방문 수는 이번 점검에서 계정/서버 로그를 조회하지 않아 미측정이다. 과거 측정치와 현재 URL 수를 섞지 않는다.

## 적용 순서와 완료 기준

1. 기존 허용 정책을 유지하며 검색·AI·Yeti 크롤러의 공개 페이지 접근 및 API 제외를 명시한다.
2. 기존 가이드 데이터를 재사용해 llms.txt·llms-full.txt와 블로그 RSS를 생성한다. 가격이나 날짜를 별도 복제하지 않는다.
3. 화면에 연결한 공식 소셜 링크를 Organization에 연결하고 Article의 publisher를 연결한다.
4. 전체 사이트맵 HTML에서 상태 코드, canonical, hreflang, 제목·설명 중복, noindex, h1, JSON-LD 및 FAQ 가시 텍스트를 검사한다.
5. lint·구조화 데이터·다국어 테스트·프로덕션 build와 로컬 HTTP 검증 결과를 기록한다.

## 배포 후 측정

- 배포 당일: 같은 HTTP 점검을 실서비스에 실행하고 Search Console·Bing Webmaster Tools·네이버 Search Advisor에서 sitemap 수집 상태를 확인한다. 기존 인증 메타만으로 계정 등록 성공을 판단하지 않는다.
- 2026-11-02: 10월 19일 배포 시 14일 후 재측정 제안일. 실제 배포일이 다르면 배포일 +14일로 이동한다. 자동 일정은 등록하지 않았다.
- 최근 2~3일의 집계 지연을 제외하고 같은 기간·검색어·페이지 집합으로 노출, 클릭, CTR, 평균 순위를 비교한다. 데이터 처리 중이면 미측정으로 남긴다.
- 질문: `InnoLive란?`, `실시간 방송 행인 얼굴 가리기`, `나만 빼고 얼굴 모자이크`, `치지직 얼굴 블러 방송`, `무료 실시간 얼굴 블러`. ChatGPT 검색·Perplexity·네이버 AI 브리핑에서 질문, 측정 시각, 모델/검색 모드, 답변, 실제 출처 URL을 기록한다.
- LLMO: 검색을 끈 모델의 제품 설명 정확성을 별도로 기록한다. 브라우징 인용 증가와 모델 학습 여부를 같은 지표로 취급하지 않는다.

## 콘텐츠 백로그·발행 규칙

기존 질문별 가이드를 먼저 유지한다. 검색 수요 근거가 없는 유사 키워드 페이지를 추가하지 않는다.

| 후보 | 근거 | 상태 |
| --- | --- | --- |
| 기존 가이드별 검색어와 노출/클릭 매핑 | GSC·네이버 최근 28일 export 필요 | 데이터 대기 |
| 비교표의 가격·지원 기능 재확인 | 기존 비교 가이드의 공식 출처·기준일 | 제품 변경 시 검토 |
| 실제 방송의 감지 누락·처리 지연 측정 설명 | 재현 조건·원본 측정 기록 필요 | 근거 확보 후 작성 |

발행 전: 직답·출처·수치 기준일, SSR, 고유 메타, FAQ 본문 일치, 내부 링크, sitemap/llms/RSS 반영을 확인한다. 날짜는 실제 내용 검토·변경일만 사용한다. 배포 후 IndexNow 및 수집 요청의 접수와 실제 색인을 구분한다.

## 1차 구현 후 검증 (중간 기록)

2026-10-19 14:47~14:48 KST에 같은 검사기로 실서비스와 로컬 프로덕션 빌드를 비교했다. 실서비스는 변경 전 배포본이며, 아래 after는 로컬 빌드 결과다.

| 항목 | 변경 전 실서비스 | 변경 후 로컬 |
| --- | --- | --- |
| 사이트맵 URL / 정상 HTML | 54 / 54 | 54 / 54 |
| JSON-LD가 있는 페이지 | 36 | 39 |
| FAQ 본문과 JSON-LD 답변 일치 | 91 / 91 | 91 / 91 |
| llms.txt / llms-full.txt / feed.xml | 모두 404 | 모두 200 |
| RSS 글 | 없음 | 6개 (2종 × 3개 언어) |
| robots 명시 그룹 | `*` 1개 | `*` 포함 15개, 각 그룹 `/api/` 제외 |
| 공통 HTML 검사 | 기존 가이드 3개 JSON-LD 누락 | 오류 0 |

- `npm run test:structured-data`: 7개 통과. 다국어 안내·FAQ 원문 동기화, 공식 소셜·발행자 연결, RSS 생성 확인 포함.
- `npm run test:i18n`: 3개 통과.
- `npm run lint`, `npm run build`, `git diff --check`: 통과.
- 54개 HTML: HTTP 200, h1 1개, canonical, ko/en/ja/x-default hreflang 존재, 고유 title/description, OG/Twitter 메타, noindex 부재 확인.
- 없는 페이지는 404. 새 안내 문서는 text/plain, RSS는 application/rss+xml로 응답하고 XML 파싱과 블로그 URL 포함 여부를 통과했다.
- 화면 레이아웃·카메라·송출·API 계약은 변경하지 않았다. 모바일 실기기·성능 재측정은 수행하지 않았다.
- 커밋·배포·수집 요청은 수행하지 않았다. 검색 순위·AI 인용 개선 효과는 아직 미측정이다.

원시 측정: [변경 전 실서비스](evidence/landing-search-2026-10-19/before-live.json), [변경 후 로컬](evidence/landing-search-2026-10-19/after-local.json).

재실행 (`apps/landing`에서 프로덕션 빌드 서버를 실행한 뒤):

```sh
python3 scripts/audit-search.py http://127.0.0.1:3109 --output /tmp/landing-search-local.json
python3 scripts/audit-search.py https://innolive.studio --output /tmp/landing-search-live.json
```

## 변경 파일과 데이터 경로

- `lib/search-documents.ts` → `app/llms.txt/route.ts`, `app/llms-full.txt/route.ts`, `app/feed.xml/route.ts`: 기존 가이드에서 안내·전문·RSS 생성. 전문의 동적 요금표 값은 복제하지 않고 pricing 정본으로 연결한다.
- `app/robots.ts`: 기존 공개 허용 정책을 검색·AI·네이버 크롤러별로 명시. 접근 허용 범위를 확대하지 않는다.
- `lib/structured-data.ts`, `app/[locale]/guides/live-face-blur/page.tsx`: 공식 링크, Article publisher, 기존 소개 가이드의 WebPage 연결.
- `lib/seo.ts`, `app/[locale]/layout.tsx`: RSS 발견용 alternate 링크.
- `lib/structured-data.test.cjs`, `scripts/audit-search.py`: 콘텐츠 동기화 및 원시 HTTP 회귀 검사.

기존 번역·SEO·UI 작업에 이번 변경만 추가했다. 일본어 가이드 추가 및 기존 문구 수정은 이번 작업의 성과로 계산하지 않는다.

## 공식 문서 교차 확인

- [Google AI features](https://developers.google.com/search/docs/appearance/ai-features): AI 검색에도 기본 SEO·가시 본문과 구조화 데이터 일치가 적용되며, 별도 AI 텍스트 파일은 노출 필수조건이 아니다. llms.txt의 효과를 보장하지 않는 근거다.
- [네이버 robots.txt 가이드](https://searchadvisor.naver.com/guide/seo-basic-robots): 루트의 robots.txt로 수집 허용·제한을 지정한다. 허용과 실제 수집·색인을 구분한다.


## 추가 구현 및 계정 기준선 (15:00 KST)

앞 절은 계정 조회 전 중간 기록이다. 추가 구현은 최신 main에서 분리한 `feat/landing-search-optimization/#676`에서 진행했다. 원래 작업 폴더의 미커밋 변경은 보존했다.

- 제품 소개 ko/en/ja 추가, Framework 운영팀과 InnoLive 제품 엔티티 구분, 공식 채널 연결
- 가이드 33개에 작성·관리 주체와 출처 표시, 블로그 author·publisher·citation 동기화
- 로그인·회원가입·체험 실행 화면 noindex 설정, 공개 체험 소개는 색인 허용 유지
- 33개 가이드 인벤토리와 검색어 기반 백로그 작성, 배포 전후 HTTP 검사 및 IndexNow 알림을 CI에 연결

최종 로컬 검사: 사이트맵 57개 모두 200, FAQ 91/91 일치, 오류 0. 구조화 데이터 8개·다국어 3개 테스트, lint·build 통과. [최종 로컬 원시 결과](evidence/landing-search-2026-10-19/after-local-final.json). 브라우저에서 한국어 소개 페이지 렌더링과 출처 링크를 확인했다. 실기기 모바일 검증은 수행하지 않았다.

| 계정·기간 | 현재 관측 | 해석 범위 |
| --- | --- | --- |
| Google Search Console, Web 28일 | 클릭 7, 노출 114, CTR 6.1%, 평균 게재순위 4.1 | 그래프 9/30~10/16, 마지막 갱신 약 6.5시간 전 |
| GSC 색인, 10/12 기준 | 색인 37, 미색인 11 | 리디렉션 5, 404 1, 발견됨 3, 크롤링됨 2 |
| GSC 사이트맵 | 성공, 10/17 제출·읽음, 발견 41 | 현재 sitemap 57개와 집계 시점 다름 |
| 네이버 성과, 최근 30일 PC·모바일 | 콘텐츠 노출·클릭 정보 없음 | 0으로 환산하지 않음 |
| 네이버 사이트맵 | 10/02 등록 확인 | 등록과 개별 URL 색인 구분 |
| Bing AI Performance, 9/19~10/18 | 총 인용 0, 평균 인용 페이지 0, 상세 표 No data available | Copilot·지원 파트너 범위의 화면 값, 전체 AI 인용 지표 아님 |
| Bing 사이트맵 | Success, 10/17, URL 41 | 새 배포 후 재제출 필요 |

GSC 검색어 4행만 표시: innolive 19회 노출·1클릭, 아이폰 15 1회·1클릭, framework? 1회·0클릭, 깃허브 검색 1회·0클릭. 다른 검색어는 미표시다. [페이지별 표](evidence/landing-search-2026-10-19/gsc-pages-28d.txt), [Bing AI 화면 기록](evidence/landing-search-2026-10-19/bing-ai-30d.txt).

서버 Caddy 최근 7일 로그의 마지막 최대 20만 줄 중 도메인 일치 기록은 24줄이었다. 알려진 봇 User-Agent는 표본에 없었다. 접근 로그 전체 수집을 확인하지 못했으므로 봇 방문 0회로 보고하지 않는다.

### AI 기준선

각 질문은 별도 대화에서 실행했다. ChatGPT는 Temporary Chat이며 화면 모델명은 High로 표시되어 정확한 모델 버전은 미확인이다. 검색 사용 여부와 인용 출처를 구분한다. 생성된 답변 내용 자체를 검증된 제품 사실로 취급하지 않는다.

- 검색 끈 ChatGPT: InnoLive를 모른다고 답변. [원문](evidence/landing-search-2026-10-19/chatgpt-no-search.txt)
- ChatGPT 브랜드 검색: Framework 서비스 식별, App Store·GitHub·LinkedIn 참조. 공식 홈페이지 링크를 적었지만 홈페이지를 열지 못했다고 명시. 도메인 링크 언급과 홈페이지 내용 인용을 구분. [원문](evidence/landing-search-2026-10-19/chatgpt-brand-search.txt)
- Perplexity 브랜드 검색: InLive 라디오와 혼동하고 GitHub 저장소를 동명 사례로 언급. innolive.studio 인용 미관측. [원문](evidence/landing-search-2026-10-19/perplexity-brand.txt)
- Perplexity 행인 얼굴 질문: IPCamLive·BystanderVideoBlur·PutMask·Fluendo 언급, InnoLive 미언급. [원문](evidence/landing-search-2026-10-19/perplexity-bystanders.txt)

배포·재제출 상태와 남은 AI 측정은 후속 절에서 갱신한다. 이 기준선만으로 개선 성과를 주장하지 않는다.
