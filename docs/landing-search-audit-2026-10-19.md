# 랜딩 검색·AI 노출 진단 (2026-10-19 KST)

## 현재 결과 요약

- 구현 PR [#677](https://github.com/team-framework/innolive-client/pull/677) 머지·배포 완료. 운영 SHA `ced47dbf04c3871a1f85651b7a2da1d7ac36c162` 확인
- 공개 URL 54→57, JSON-LD 페이지 36→42, FAQ 91/91 일치. llms.txt·llms-full.txt·RSS 404→200
- Google 사이트맵 재제출 성공, Bing 처리 중, IndexNow 57개 URL HTTP 200 접수, 네이버 RSS·수집 요청 등록 확인
- 네이버 AI 브리핑 기준선에서 5개 질문 중 3개가 InnoLive 공식 페이지 인용. 이번 배포의 효과로 계산하지 않음
- 검색 없는 ChatGPT·Claude·Gemini 3개 모두 모른다고 답변. 11월 2일 재측정과 1월 19일 분기 점검 예약

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


## 운영 배포와 수집 요청 결과

2026-10-19 15:05~15:09 KST 확인. [배포 workflow](https://github.com/team-framework/innolive-client/actions/runs/39207706864)의 validate·deploy 성공 후 공개 `/api/version`에서 merge SHA를 직접 확인했다. 머지 커밋은 부모 2개, 제목 `merge: feat/landing-search-optimization/#676`, 빈 본문이다.

| 항목 | 결과 | 확인 범위 |
| --- | --- | --- |
| 운영 HTTP 점검 | 57개 정상, JSON-LD 42개, FAQ 91/91 일치, 오류 0 | [원시 결과](evidence/landing-search-2026-10-19/after-live.json) |
| Google sitemap | Sitemap submitted successfully, 제출일 10/19 | 직후 마지막 읽기 10/17·발견 41; 새 URL 색인 완료 의미 아님 |
| Bing sitemap | 10/19 Submitted, Processing | 직후 발견 41, 새 처리 대기 |
| IndexNow | 57개 URL, HTTP 200 | 접수 성공이며 색인 완료 아님 |
| 네이버 RSS | feed.xml, 10/19 15:07:25 등록 | CAPTCHA는 사용자가 직접 완료 |
| 네이버 수집 | /ko/about 15:07:53, /ko/guides/live-face-blur 15:08:20, /ko/live-mosaic 15:08:38 | 요청 내역 등록 확인, 개별 색인 완료 미확인 |
| 네이버 sitemap | 기존 sitemap.xml 등록 유지 | 같은 URL의 갱신된 사이트맵 제공, 삭제·중복 등록 불필요 |
| 기존 Google 개별 색인 자동화 | 미접수 9개, 10/19 16:00 이후 재시도 예약 유지 | 10/18 quota 제한 기록 존중, 중복 URL 검사·제출 생략 |

[Google 접수](evidence/landing-search-2026-10-19/google-sitemap-submitted.png), [Bing 처리 상태](evidence/landing-search-2026-10-19/bing-sitemap-submitted.png), [네이버 RSS](evidence/landing-search-2026-10-19/naver-rss-submitted.png), [네이버 수집 요청](evidence/landing-search-2026-10-19/naver-collection-submitted.txt).

Bing Search Performance는 `Please check back in 48 hours while we prepare the data for your site`를 표시했다. 노출·클릭을 0으로 채우지 않았다. 일반 Bing `site:innolive.studio` 검색은 약 4개 결과를 표시했고 첫 페이지에서 3개 링크를 확인했다. Google 같은 검색 첫 페이지에는 고유 URL 10개가 보였으며 전체 결과 수는 표시되지 않았다. 이는 표본이며 GSC 색인 37개와 같은 집계가 아니다. [Google 표본](evidence/landing-search-2026-10-19/google-site-sample.json), [Bing 표본](evidence/landing-search-2026-10-19/bing-site-sample.txt).

### 검색 없는 브랜드 인지

| 서비스 | 화면 모델 표기·모드 | 응답 |
| --- | --- | --- |
| ChatGPT | High, Temporary Chat; 정확한 모델 버전 미확인 | 모름 |
| Claude | Opus 5.5 Medium, Incognito | 모름 |
| Gemini | Gemini 4 화면, Flash, Temporary Chat | 모름 |

질문에서 웹 검색·도구 사용을 금지했고, Claude·Gemini는 이전 대화·메모리 사용 금지도 명시했다. 화면상 검색·커넥터 사용은 관측하지 않았다. 임시 대화만으로 모든 계정 개인화가 제거됐다고 단정하지 않는다. 단일 응답은 학습 코퍼스 포함 여부의 증거가 아니다. [Claude 원문](evidence/landing-search-2026-10-19/claude-no-search.txt), [Gemini 원문](evidence/landing-search-2026-10-19/gemini-no-search.txt).

### 봇 관측 공백 보완

Caddy 설정에서 access log 구성이 없음을 확인했다. 기존 로그 24줄은 정상 요청의 분모가 아니므로 과거 봇 트래픽은 미측정이다.

후속 PR [#678](https://github.com/team-framework/innolive-client/pull/678)은 공개 sitemap 경로·가이드·검색 문서만 대상으로 정규화한 봇 이름·경로·GET/HEAD를 stdout에 기록한다. 원본 User-Agent, IP, 쿠키, 쿼리, 계정 화면, API, 임의 경로는 기록하지 않는다. 로그에 나타난 이름은 자가 표기이며 DNS 검증된 봇이나 실제 색인으로 해석하지 않는다. 요청 기록이므로 응답 상태와 성공 수집도 구분한다.

로컬에서 GPTBot 표기를 보낸 공개 GET은 1건 기록됐고, 같은 표기의 로그인 요청은 기록되지 않았다. 쿼리 문자열도 제외됐다. 이 합성 요청은 로컬에서만 실행해 운영 기준선에 포함하지 않았다. 테스트 9개·lint·build 통과. 집계는 `scripts/summarize-crawlers.py`를 사용하며 Docker 로그의 기간·컨테이너 교체·보존 범위를 함께 기록한다.

### 남은 시간 조건과 적용 예외

- 11월 2일 09:00 KST 재측정 자동화 `innolive-ai-14` 등록. 집계 지연을 제외한 같은 조건으로 비교하며, 완료 후 중지
- 2027년 1월 19일부터 분기 점검 자동화 `innolive` 등록. 콘텐츠 갱신 후보와 모델 인지 변화 확인
- 효과 측정: 검색 순위·클릭·인용 증가를 당일 구현 완료와 동일하게 취급하지 않음
- 날짜: 최초 발행·최종 변경일을 확인하지 못한 글에 현재 날짜를 넣지 않음. 비교 출처 확인일은 기존 10/17 유지
- 새 콘텐츠: 기존 질문 11종을 유지하고 제품 정체성 안내만 추가. 검색어 표본이 적어 유사 질문 페이지 대량 생성·301 병합 미실시
- 외부 채널: 요약 초안을 사용자에게 제시했고 게시 계정·채널은 미지정. 공식 사이트에 기존 글과 RSS가 있으므로 새 외부 블로그를 필수로 만들지 않음
- 위키백과·언론 배포: 독립 출처와 등재 적격성을 확보하지 못해 홍보용 계정·문서·보도자료 게시 미실시
- 성능: 모바일 실기기·Core Web Vitals 현장 지표와 실제 방송 성능 재측정은 이번 검색 문서 변경의 검증으로 주장하지 않음


## AI 검색 표본 최종 기록

10월 19일 질문 5종을 각 서비스에서 실행했다. 표는 innolive.studio 내용을 출처로 사용한 응답 여부다. 브랜드명이나 링크만 적은 경우는 별도로 구분했다.

| 질문 | ChatGPT | Perplexity | 네이버 AI 브리핑 |
| --- | --- | --- | --- |
| InnoLive란 | 홈페이지 열기 실패, 외부 출처·브랜드 언급 | 동명 서비스 혼동, GitHub 사례 언급 | 브리핑 없음 |
| 행인 얼굴 가리기 | 자체 도메인 인용 없음 | 없음 | 인용 있음 |
| 나만 빼고 얼굴 가리기 | 없음 | 없음 | 인용 있음 |
| 치지직 얼굴 모자이크 | 없음 | 없음 | 인용 있음 |
| 무료 실시간 얼굴 블러 | 없음 | 없음 | 자체 도메인 인용 없음 |

- 자체 도메인 내용 인용: ChatGPT 0/5, Perplexity 0/5, 네이버 검색 질의 3/5. 네이버는 브리핑이 나온 4건 중 3건에서 인용
- 네이버 고유 인용 경로 3개: `/ko/blog/live-face-blur`, `/ko/live-mosaic`, `/ko/mosaic-software`
- ChatGPT는 질문별 Temporary Chat·웹 검색 명시. 나만 빼고 질문은 로딩 오류 후 새 대화에서 1회 재시도
- Perplexity 질문 1~2는 비로그인, 3~5는 로그인 후 Incognito. 일부 화면은 Pro 고급검색 무료 미리보기 표시. 모델·검색 조건이 같은 통제 실험으로 해석하지 않음
- 네이버는 짧은 검색 질의를 사용. 상세 프롬프트와 응답은 evidence의 서비스별 텍스트에 보관
- 배포 전후 당일 기준선이며 새 코드의 효과를 보여 주는 결과가 아님. 생성 답변의 경쟁 제품 설명은 별도 사실 검증하지 않음

사용자 결정에 따라 외부 블로그 신설·외부 게시를 진행하지 않고, 기존 한국어 얼굴 블러 글에 세 가지 점검 장면과 웹 체험·실제 송출 구분을 보강했다. 실제 기기 실험 수치나 성공률은 추가하지 않았다.
