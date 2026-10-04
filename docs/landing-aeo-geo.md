# Landing AEO/GEO

## 범위와 목적

공개 웹사이트 `apps/landing`에서 제품 동작과 사용 방법을 설명하고, 검색·AI 답변에서
공식 페이지를 출처로 사용할 수 있도록 본문과 구조화 데이터를 정리한다.
대상은 한국어·영어·일본어 홈 3개와 한국어 기능·사용 방법 페이지 10개다.
HTTP API·WebRTC signaling 계약 영향은 없다.

사용자가 정한 목표는 제품명을 넣지 않은 질문에 ChatGPT·Gemini·Claude가 InnoLive를
라이브 얼굴 가리기 도구로 추천하는 것이다. 대표 질문은
“실시간 방송에서 행인 얼굴 자동으로 가리는 프로그램 있어?”다.

## 본문과 제품 정보

- 홈 첫 화면은 기존 한영일 카피를 유지한다. 기능·검색어 설명은 메타데이터와 사용 가이드에 둔다.
- 한국어 안내 페이지 첫 문장에서 기능 또는 사용 순서에 답한다. 현재 처리 방식은 블러로 명시한다.
- 홈 FAQ는 `lib/faq.ts`의 질문·답변을 화면과 JSON-LD에서 함께 사용한다. 개인정보 처리방침 링크를 유지한다.
- `lib/structured-data.ts`에서 Organization·WebSite·SoftwareApplication의 공통 `@id`를 사용한다.
- 13개 페이지의 WebPage를 사이트·제품과 연결하고, 안내 페이지의 BreadcrumbList를 표시한 탐색 경로와 일치시킨다.
- 사용 방법 글 2개는 Article로 연결한다. FAQ 질문에는 화면의 `#faq-N` 위치를 연결한다.
- 무료 이용 안내는 가격 페이지와 같은 요금제 데이터를 사용한다. 평점·출시일·성능 수치를 만들지 않는다.
- 다운로드 메뉴의 Android 최소 OS를 앱의 `minSdk=30`과 기존 FAQ에 맞춰 Android 11+로 정정한다.
- 무료 이용·휴대폰 야외방송 안내 2개를 추가하고 관련 안내·sitemap에 연결한다. 홈 푸터의 사용 가이드에서 안내 페이지로 이동한다.
- 공개 대상자 등록·버튜버 야방·녹화 파일 편집·초상권 질문에 현재 기능 범위를 설명한다.
- 한영일 홈 FAQ의 얼굴 등록 한도를 Spark·Glow 2명, Beam 5명, Plasma 10명으로 가격 안내와 일치시킨다.

기존 카피를 검색어 문장으로 교체하지 않는다. 이미지 alt는 화면 내용을 설명하고, 장식용
이미지의 빈 alt를 유지한다. 홈 FAQ는 기존 6개 항목으로 구성한다. SoftwareApplication의
기능 설명은 검색 메타데이터를 사용하고, 화면과 FAQ의 JSON-LD는 공개한 본문과 일치시킨다.

검색 안내는 이용자가 할 일과 제공하는 기능을 직접 설명한다. “서비스로 소개하지 않습니다”,
“요금제에도 0원을 표시하지만” 같은 평가·메타 설명을 제거한다. 가격·한도·베타 기간·미지원
기능·플랫폼별 방송 시작 조건은 유지한다. `humanize`와 ASD-STE100의 짧은 문장·한 문장에
한 주제·능동형 원칙을 참고했다. 한국어에 STE의 영어 어휘 규칙을 적용하거나 표준 준수를
주장하지 않는다. [ASD-STE100 적용 원칙](https://www.asd-ste100.org/STE_faq.html)

구조화 데이터는 본문의 의미와 관계를 설명한다. 추가만으로 AI 인용이나 검색 노출이
늘었다고 판정하지 않는다. Google은 AI 검색에 별도 schema나 AI 파일을 요구하지 않는다.
`llms.txt`는 Google 검색 노출이나 순위에 영향을 주지 않는다는 공식 안내에 따라 이번 범위에
추가하지 않는다. [Google 생성형 AI 검색 가이드](https://developers.google.com/search/docs/fundamentals/ai-optimization-guide)

FAQPage는 공개한 질문·답변을 설명하는 schema.org 형식으로 사용한다. Google은
2026-05-07부터 FAQ rich result를 표시하지 않으며 관련 문서를 제거했다.
[Google 변경 기록](https://developers.google.com/search/updates#june-2026),
[schema.org FAQPage](https://schema.org/FAQPage)

## 크롤러 접근

기존 robots.txt의 공개 페이지 허용·API 경로 제외 규칙을 유지하고 sitemap은 29개에서
31개 URL로 늘린다. Googlebot·Google-Extended·bingbot·OAI-SearchBot·Claude-SearchBot·
Claude-User·PerplexityBot에 대해 공개 페이지 허용과 API 제외를 검사한다.
OAI-SearchBot user agent로 운영 HTML의 HTTP 200을 확인했다. 실제 크롤러 IP에서의
접근과 수집 여부는 서버·CDN 로그로 별도 확인한다.

ChatGPT 검색용 OAI-SearchBot과 학습용 GPTBot 설정은 독립적이다.
이번 작업은 기존 학습 크롤러 정책을 변경하지 않는다.
[OpenAI 크롤러 문서](https://developers.openai.com/api/docs/bots)

Claude-SearchBot은 검색 색인을, Claude-User는 사용자 질문에 따른 페이지 조회를 지원한다.
Google-Extended는 Gemini의 검색 grounding과 학습 사용을 함께 제어하며 Google 검색
순위 신호는 아니다. 기존 공개 허용 정책을 유지했고 학습 정책을 새로 변경하지 않았다.
[Anthropic 크롤러 문서](https://support.claude.com/en/articles/8896518-does-anthropic-crawl-data-from-the-web-and-how-can-site-owners-block-the-crawler),
[Google 크롤러 문서](https://developers.google.com/crawling/docs/crawlers-fetchers/google-common-crawlers#google-extended)

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
사이트맵 전체의 HTTP 200·canonical·언어·h1·색인 차단 지시를 검사하고, 대상 13개 페이지의
schema와 본문 설명, FAQ 질문·답변·앵커의 일치를 검사한다. 43개 질문의 제품명 미포함과
근거 페이지의 sitemap 등록도 검사한다.

2026-10-03 로컬 결과:

- production build·TypeScript·수정 파일 ESLint 통과.
- 구조화 데이터·경로 일치 테스트 3개·기존 번역 테스트 3개 통과.
- sitemap 31개 URL의 HTTP 200·canonical·언어·h1 검사 통과.
- 대상 13개 페이지의 구조화 데이터와 FAQ 답변 33개 일치.
- 7개 크롤러의 robots 규칙 검사 통과.
- 브라우저에서 한영일 홈의 FAQ 열기와 일본어 개인정보 처리방침 링크 유지 확인.
- 무료 안내의 요금 표와 휴대폰 안내 이미지 표시 확인. 축소한 화면에서 무료·휴대폰 안내의 가로 넘침과 관측된 콘솔 오류 없음 확인.

2026-10-03 카피 복구·윤문 결과:

- 한국어 메인 문구 “주인공이 아니라면 과감하게 가리기.” 및 한영일 소개 문구를 SEO 변경 전 원문으로 복구.
- 홈의 검색어 설명 섹션·추가 FAQ 3개 제거. 별도 안내 페이지와 메타데이터 유지.
- 수정한 한국어 문구 94개 구간을 원문과 비교한 `humanize` 변경률 16.97%, 경고 기준 30% 미만 확인.
- 요금제 가격·한도, 블러 방식, 등록 얼굴 공개, 앱 송출·웹 체험 범위, 감지 조건의 의미 대조 완료.

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

주요 측정 대상은 ChatGPT·Gemini·Claude다. Google AI 검색·Bing/Copilot·Perplexity는
추가 지표로 구분한다. 같은 질문을 확인한다.
질문, 서비스, 확인 시각, 언어·지역, 답변, 인용 URL, 제품 설명의 오류를 기록한다.
브랜드 언급과 공식 URL 인용을 구분하고, 인용률은 질문 수와 반복 횟수를 함께 기록한다.
같은 조건으로 배포 전후를 비교하며 한 번의 답변을 전체 노출의 증거로 사용하지 않는다.

### 43개 검색어와 질문 목록

[질문 목록](fixtures/landing-aeo-queries.ko.json)에 현재 측정 대상인 검색어 43개를 원문 그대로
저장하고, 제품명을 넣지 않은 자연어 질문과 공식 근거 페이지를 연결했다.

| 검색 의도 | 근거 페이지 | 추천 시 확인할 범위 |
| --- | --- | --- |
| 라이브·실시간·AI 모자이크·자동 블러 | `/ko/live-mosaic`, `/ko/mosaic-software` | 미등록 얼굴의 실시간 블러와 앱 송출 |
| 무료·공짜 모자이크·블러 | `/ko/free-face-blur` | Spark 한도, 베타 기간 가격, 정가 구분 |
| 휴대폰·야외방송·야방 | `/ko/mobile-live-face-blur` | Android·iOS 앱과 플랫폼별 방송 시작 순서 |
| 나만 빼고·스트리머 얼굴 제외 | `/ko/face-mosaic` | 공개 대상자 등록, 실제 촬영 장소에서 결과 확인 |
| 유튜브·치지직 | `/ko/youtube`, `/ko/chzzk` | 앱에서 처리 후 플랫폼 송출 |
| 버튜버 야방 | `/ko/face-mosaic` | 카메라 얼굴 처리 범위, 아바타 합성 미포함 |
| 초상권·소송 | `/ko/live-privacy` | 얼굴 처리 기능 범위, 법률 판단·결과 보장 미포함 |
| 캡컷·트래킹·온라인 영상 편집 | `/ko/blog/automatic-mosaic` | 라이브 처리와 녹화 파일 편집 구분 |

30개 질문은 현재 제품 기능과 직접 맞는다. 범위가 넓은 질문 6개와 녹화 편집·법률·아바타 사용
질문 7개는 답변의 조건과 기능 범위를 함께 확인한다. 검색어마다 비슷한 페이지를 만들거나,
지원하지 않는 기능을 추가했다고 설명하지 않는다.

### 성공 판정과 배포 전 기준

- 제품명을 넣지 않은 답변에서 InnoLive를 해결 도구로 추천한 횟수를 주요 지표로 기록한다.
- 단순 브랜드 언급·공식 URL 인용·사실에 맞는 추천을 각각 기록한다.
- 무료 무제한, 픽셀 모자이크, 지원하지 않는 연동 기능, 파일 편집을 잘못 소개한 답변은 기능 오류로 기록한다.
- 전체 43개 질문 × 3개 서비스 × 반복 횟수와 실제 측정 수를 함께 표시한다. 미측정은 0으로 집계하지 않는다.
- 새 대화에서 측정하고 모델 표시·검색 사용 근거·개인화 상태·언어·확인 날짜를 남긴다. 확인하지 못한 조건은 미확인으로 기록한다.

[2026-10-03 배포 전 기록](fixtures/landing-aeo-baseline-2026-10-03.json): 대표 질문을
ChatGPT·Gemini에서 각각 1회 측정했다. 측정한 답변 2개에서 추천·브랜드 언급·공식 URL
인용은 각각 0개였다. ChatGPT는 임시 채팅의 Unpersonalized, Gemini는 임시 채팅의 Flash를
사용했다. Claude는 로그인 화면에서 진행하지 못해 미측정이다. 질문 목록 전체나 전체 서비스의
추천률을 측정한 결과로 해석하지 않는다.

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
