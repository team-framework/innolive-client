# Landing AEO/GEO

## 범위와 목적

공개 웹사이트 `apps/landing`에서 제품 동작과 사용 방법을 설명하고, 검색·AI 답변에서
공식 페이지를 출처로 사용할 수 있도록 본문과 구조화 데이터를 정리한다.
대상은 한국어·영어·일본어 홈 3개와 기존 한국어 기능·사용 방법 페이지 8개다.
HTTP API·WebRTC signaling 계약 영향은 없다.

## 본문과 제품 정보

- 홈 첫 화면에서 미등록 얼굴의 실시간 블러와 공개 대상자 등록을 설명한다.
- 한국어 안내 페이지 첫 문장에서 기능 또는 사용 순서에 답한다. 현재 처리 방식은 블러로 명시한다.
- 홈 FAQ는 `lib/faq.ts`의 질문·답변을 화면과 JSON-LD에서 함께 사용한다. 개인정보 처리방침 링크를 유지한다.
- `lib/structured-data.ts`에서 Organization·WebSite·SoftwareApplication의 공통 `@id`를 사용한다.
- 11개 페이지의 WebPage를 사이트·제품과 연결하고, 안내 페이지의 BreadcrumbList를 표시한 탐색 경로와 일치시킨다.
- 사용 방법 글 2개는 Article로 연결한다. FAQ 질문에는 화면의 `#faq-N` 위치를 연결한다.
- 가격·평점·출시일·성능 수치를 새로 넣지 않는다. 기존 제품의 실제 동작을 설명한다.
- 다운로드 메뉴의 Android 최소 OS를 앱의 `minSdk=30`과 기존 FAQ에 맞춰 Android 11+로 정정한다.

구조화 데이터는 본문의 의미와 관계를 설명한다. 추가만으로 AI 인용이나 검색 노출이
늘었다고 판정하지 않는다. Google은 AI 검색에 별도 schema나 AI 파일을 요구하지 않는다.
`llms.txt`는 Google 검색 노출이나 순위에 영향을 주지 않는다는 공식 안내에 따라 이번 범위에
추가하지 않는다. [Google 생성형 AI 검색 가이드](https://developers.google.com/search/docs/fundamentals/ai-optimization-guide)

FAQPage는 공개한 질문·답변을 설명하는 schema.org 형식으로 사용한다. Google은
2026-05-07부터 FAQ rich result를 표시하지 않으며 관련 문서를 제거했다.
[Google 변경 기록](https://developers.google.com/search/updates#june-2026),
[schema.org FAQPage](https://schema.org/FAQPage)

## 크롤러 접근

기존 robots.txt의 공개 페이지 허용·API 경로 제외 규칙과 29개 sitemap URL을 유지한다.
Googlebot·bingbot·OAI-SearchBot·PerplexityBot에 대해 공개 페이지 허용과 API 제외를 검사한다.
OAI-SearchBot user agent로 운영 HTML의 HTTP 200을 확인했다. 실제 크롤러 IP에서의
접근과 수집 여부는 서버·CDN 로그로 별도 확인한다.

ChatGPT 검색용 OAI-SearchBot과 학습용 GPTBot 설정은 독립적이다.
이번 작업은 기존 학습 크롤러 정책을 변경하지 않는다.
[OpenAI 크롤러 문서](https://developers.openai.com/api/docs/bots)

## 검증

`apps/landing`에서 실행한다.

```sh
pnpm run test:structured-data
pnpm run test:i18n
pnpm run build
pnpm start --hostname 127.0.0.1 --port 3107
python3 scripts/check-aeo.py --base-url http://127.0.0.1:3107 --report /tmp/landing-aeo.json
```

`check-aeo.py`는 JavaScript를 실행하지 않고 응답 HTML을 파싱한다.
사이트맵 전체의 HTTP 200·canonical·언어·h1·색인 차단 지시를 검사하고, 대상 11개 페이지의
schema와 본문 설명, FAQ 질문·답변·앵커의 일치를 검사한다.

2026-10-03 로컬 결과:

- production build·TypeScript·수정 파일 ESLint 통과.
- 구조화 데이터 테스트 2개·기존 번역 테스트 3개 통과.
- sitemap 29개 URL의 HTTP 200·canonical·언어·h1 검사 통과.
- 대상 11개 페이지의 구조화 데이터와 FAQ 답변 30개 일치.
- 4개 크롤러의 robots 규칙 검사 통과.
- 브라우저에서 한영일 홈의 FAQ 열기와 일본어 개인정보 처리방침 링크 유지 확인.

## 노출과 인용 측정

배포 후 같은 검사기를 운영 주소로 실행한다. 이후 색인과 AI 답변을 별도로 측정한다.

| 질문 | 확인할 제품 사실 | 공식 출처 |
| --- | --- | --- |
| InnoLive는 어떤 서비스인가요? | 미등록 얼굴의 실시간 블러, 등록한 출연자 공개 | `/ko`, `/ko/mosaic-software` |
| 라이브 방송에서 행인 얼굴을 자동으로 가릴 수 있나요? | 송출 전에 감지·블러 처리 | `/ko/live-mosaic`, `/ko/live-privacy` |
| InnoLive는 픽셀 모자이크를 지원하나요? | 현재 효과는 블러 | `/ko/live-mosaic#faq-1`, `/ko/face-mosaic` |
| 출연자의 얼굴만 공개할 수 있나요? | 공개 대상자 등록 | `/ko/live-mosaic#faq-2` |
| 유튜브 라이브에서 얼굴을 가리려면 어떻게 하나요? | 계정 연결·설정 저장·방송 준비·라이브 시작 | `/ko/youtube` |
| 치지직 방송에서 얼굴을 가리려면 어떻게 하나요? | RTMP 연결 전 등록·처리 결과 확인 | `/ko/chzzk` |
| 웹 체험으로 방송할 수 있나요? | 체험에서 결과 확인, 앱에서 송출 준비 | `/ko/live-mosaic#faq-3` |
| 녹화 영상과 라이브 방송의 얼굴 가리기는 어떻게 다른가요? | 녹화 후 검토·내보내기와 송출 전 처리 | `/ko/blog/automatic-mosaic` |

ChatGPT Search·Google AI 검색·Bing/Copilot·Perplexity에서 같은 질문을 확인한다.
질문, 서비스, 확인 시각, 언어·지역, 답변, 인용 URL, 제품 설명의 오류를 기록한다.
브랜드 언급과 공식 URL 인용을 구분하고, 인용률은 질문 수와 반복 횟수를 함께 기록한다.
같은 조건으로 배포 전후를 비교하며 한 번의 답변을 전체 노출의 증거로 사용하지 않는다.

- Google Search Console에서 사이트의 Search generative AI 포함 설정을 확인한다.
- Generative AI performance 보고서에서 AI Overviews·AI Mode의 페이지별 노출을 확인한다. 보고서 부재는 노출 0의 증거로 사용하지 않는다.
- 일반 Performance 보고서의 노출·클릭·CTR과 AI 노출 지표를 구분한다.
- Bing Webmaster Tools의 AI Performance에서 인용 수·인용 URL·grounding query를 확인한다.
- 유입 분석에서 AI 서비스 referrer와 체험·다운로드 전환을 확인한다. referrer가 없는 방문은 확인된 AI 유입으로 집계하지 않는다.

근거: [Google AI 포함 설정](https://support.google.com/webmasters/answer/16908024),
[Google AI 성과 보고서](https://support.google.com/webmasters/answer/16984139),
[Bing AI Performance](https://blogs.bing.com/webmaster/2026/2/Introducing-AI-Performance-in-Bing-Webmaster-Tools-Public-Preview/).

2026-10-03 Search Console의 `https://innolive.studio/` 속성에서 소유권 인증과
Search generative AI의 `Current control: Include`를 확인했다. 사이트는 부모 속성
`innolive.studio`의 설정을 상속한다. Overview의 검색 성과·색인 데이터는 처리 중으로
표시되었다. 이 확인은 페이지별 색인 완료나 AI 인용 횟수 측정에 해당하지 않는다.

실제 AI 인용 증가·색인 완료·운영 배포는 로컬 검증 결과에 포함하지 않는다.
