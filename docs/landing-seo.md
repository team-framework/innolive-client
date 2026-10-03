# Landing 검색 노출

## 대상과 검색 의도

`apps/landing`의 한국어 홈은 비식별화, 실시간 블러, 실시간 방송 블러,
라이브 방송 검색에서 서비스와 기능을 설명한다. 실시간 모자이크와 라이브 방송
모자이크를 찾는 사용자에게는 FAQ에서 현재 블러 방식과 모자이크의 차이를 안내한다.
현재 얼굴 비식별화는 블러 방식이며, 픽셀 블록 모자이크를 제공한다고 설명하지 않는다.

- 홈, 요금제, 체험의 제목·설명과 홈 FAQ는 `lib/seo-copy.ts`에서 언어별로 관리한다.
- `lib/seo.ts`에서 대표 사이트 주소와 페이지별 canonical·hreflang·공유 메타데이터를 생성한다.
- 한국어·영어·일본어 페이지는 해당 언어의 자기 URL을 canonical로 사용한다.
- `x-default`는 같은 경로의 한국어 페이지를 가리킨다.
- 제목과 설명은 해당 페이지의 내용을 설명한다. 검색어를 나열하는 keywords 메타 태그는 사용하지 않는다.
- 홈 h1은 초기 HTML에 전체 문구를 제공한다. 화면의 타이핑 애니메이션은 유지한다.

## 크롤링

`/robots.txt`는 공개 페이지를 허용하고 API 경로를 제외하며 `/sitemap.xml`을 안내한다.
sitemap에는 홈·요금제·체험 안내·고객 지원·개인정보 처리방침·이용약관의 3개 언어,
18개 URL과 라이브 블러 안내 3개, 한국어 검색 의도 페이지 11개를 포함해 총 32개 URL을 제공한다. 로그인·회원가입·카메라 체험 실행 경로는
사이트를 소개하는 sitemap에서 제외한다. 이 작업은 해당 페이지의 색인 정책을 바꾸지 않는다.
`lastModified`는 실제 콘텐츠 변경 시점을 관리할 때만 추가한다.

## 검증

- production build 후 sitemap의 공개 페이지 응답, canonical, 해당 언어의 hreflang, 공유 제목·설명을 검사한다.
- JavaScript 실행 전 홈 HTML에 제목과 3개 검색 의도 FAQ, h1 문구가 있는지 검사한다.
- sitemap의 32개 URL과 robots.txt의 sitemap 선언을 검사한다.
- 브라우저에서 FAQ를 열고 답변과 기존 타이핑 애니메이션을 확인한다.
- 배포 후 Google Search Console에서 sitemap과 주요 URL을 제출하고 색인 여부를 확인한다.
- 이후 검색어별 노출·클릭·CTR을 비교한다. 코드 반영만으로 순위나 노출을 보장하지 않는다.

기준 측정은 2026-10-02 운영 사이트의 27개 페이지에서 수행했다. 당시 canonical과
hreflang은 없었고 robots.txt·sitemap.xml은 404였다. Lighthouse SEO는 100점이었으나
위 부재를 점수에서 검출하지 않았다. 성능 측정에는 로딩 시간 초과 경고가 있었다.

근거: [Google SEO 기본 가이드](https://developers.google.com/search/docs/fundamentals/seo-starter-guide),
[언어별 페이지 안내](https://developers.google.com/search/docs/specialty/international/localized-versions).

AI 검색을 위한 본문·구조화 데이터와 인용 측정 기준은 [Landing AEO/GEO](landing-aeo-geo.md)를 따른다.

## 2026-10-02 로컬 검증 결과

- 수정 파일 ESLint, production build와 TypeScript 검사 통과.
- 기존 locale·번역 테스트 3개 통과.
- production 서버에서 27개 페이지의 HTTP 200, 자기 URL canonical,
  ko·en·ja·x-default 링크, OG 제목·설명·URL 확인.
- 한국어 홈 초기 HTML에 요청한 6개 검색어 포함. 3개 언어 홈의 h1 텍스트 확인.
- sitemap의 고유 URL 18개와 각 언어 링크, robots의 sitemap 안내 확인.
- 데스크톱과 390px 모바일 브라우저에서 새 FAQ 질문·답변 표시와 열기 확인.
  홈 타이핑 완료 문구와 접근성 heading 확인. 브라우저 page error·console error 없음.
- HTTP·HTML 결과는 `output/seo-2026-10-02/local-after-verification.json`,
  화면은 같은 폴더의 `local-faq.png`, `local-faq-mobile.png`에 기록.
- 운영 배포·검색엔진 색인·검색어별 노출은 미검증. API·signaling 계약 영향 없음.

## 라이브 블러 안내와 네이버 등록

- `/ko/guides/live-face-blur` 및 영어·일본어 대응 페이지에서 라이브 모자이크와 블러의 차이, 얼굴 등록·방송 준비·웹 체험 안내 제공
- 현재 효과는 Gaussian blur 방식이며 픽셀 모자이크 선택 기능으로 설명하지 않음
- 홈 FAQ의 안내 링크, 각 안내 페이지의 체험·고객지원 링크 추가
- 한국어 홈·요금제·체험 설명을 80자 이내로 조정, 홈 제목에 이노라이브 표기 추가
- 사이트맵 21개 URL, 각 페이지 self canonical과 4개 언어 alternate 유지
- 네이버 계정에서 발급한 공개 소유확인 메타 태그 적용. 실제 소유확인·사이트맵 제출 상태는 Search Advisor에서 별도 확인 필요

2026-10-02 검색 관측: Google 브랜드 검색에서 공식 사이트가 첫 번째 웹 검색 결과와 AI 개요에 노출. Naver는 `innolive`를 `innolife`로 자동 교정하며 원래 검색어로 조회해도 첫 페이지에 공식 사이트 미노출. `라이브 모자이크`, `라이브 블러`, `방송 모자이크`의 두 검색 엔진 첫 페이지에서도 공식 사이트 미노출. Google 맞춤설정 제외 결과를 확인했으며 지역·시점에 따라 검색 결과가 달라질 수 있음. 검색 화면 관측은 Search Console·Search Advisor의 노출수·클릭수 측정과 구분.

서비스 정의 근거: Framework Wiki `innolive.md`의 화이트리스트 얼굴 비식별화 정의(2026-08-15, partial), 현재 AI `service/mosaic.py`의 GaussianBlur 처리 및 랜딩 고객지원의 얼굴 등록·웹 체험 안내. 위키의 전체 기능·정확도·지연 시간 검증을 의미하지 않음.


## 한국어 검색 의도 페이지 (2026-10-03)

대상은 공개 웹사이트 `apps/landing`이며 API·시그널링 계약은 바꾸지 않는다.
기존 홈·요금제·체험 등의 다국어 페이지와 진행 중인 변경을 유지한다.

- `/ko/live-mosaic`: 실시간 라이브 처리 흐름, 대표 얼굴 등록 화면, FAQ.
- `/ko/face-mosaic`: 얼굴 감지, 공개 대상자 등록, 블러 방식.
- `/ko/live-privacy`: 야외·IRL 방송의 행인 얼굴 처리와 송출 전 확인.
- `/ko/youtube`: 유튜브 계정 연결, 설정 저장, 준비 후 라이브 시작.
- `/ko/chzzk`: 치지직(CHZZK) 계정 연결, 방송 설정, 송출 연결 전 확인.
- `/ko/mosaic-software`: 방송 도구의 기능과 체험·송출의 차이.
- `/ko/blog/live-face-blur`: 라이브 방송에서 행인 얼굴을 가리는 순서.
- `/ko/blog/automatic-mosaic`: 녹화 영상 편집과 라이브 처리의 차이.

한국어 홈의 title과 h1을 라이브 방송 얼굴 가리기로 수정하고 설명형 링크를 추가한다.
각 페이지는 고유한 제목·설명·본문과 h1 하나를 제공한다. 모자이크 검색 의도를
다루되, 현재 제품이 블러 방식이라는 설명을 도입과 본문에 표시한다.
녹화 파일 편집 기능이나 성능 수치를 추가하지 않는다. SOOP·아프리카TV 페이지는 만들지 않는다.

구현 근거:

- `innolive-server/README.md`: 미등록 얼굴 블러 처리, 처리된 영상 송출,
  유튜브 계정 연결·방송 준비·라이브 전환 흐름.
- `innolive-server/internal/streaming/chzzk.go`: 치지직(CHZZK) 준비 단계의 설정 반영과
  송출 정보 확인, RTMP 연결 시 방송 시작. 운영 계정 송출 E2E는 별도 확인 대상이다.

대표 도메인은 기존 `lib/seo.ts`의 `https://innolive.studio`를 사용한다.
신규 페이지의 canonical은 `/ko/...`이며 trailing slash를 붙이지 않는다.
언어 접두사 없는 8개 경로는 쿠키·브라우저 언어에 관계없이 한국어 canonical로
308 이동한다. 영어·일본어 접두사로 접근해도 한국어 페이지로 이동하며,
페이지의 언어 선택은 선택한 언어의 홈으로 이동한다. 번역이 없는 페이지의
hreflang을 생성하지 않는다.

sitemap은 기존 21개 URL에 한국어 신규 페이지 8개를 더해 총 29개 URL을 제공한다.
기존 robots의 공개 페이지 허용과 API 차단 규칙을 유지한다.
홈에는 Organization·WebSite, 신규 페이지에는 화면과 일치하는 BreadcrumbList,
라이브 안내에는 실제 표시한 FAQPage, 제품 소개에는 SoftwareApplication을 적용한다.
검증하지 않은 평점·가격·출시 날짜는 구조화 데이터에 넣지 않는다.

### 검색엔진 등록 후속 작업

배포 후 운영 URL의 응답·메타데이터·sitemap·robots를 다시 확인한다.

- Google Search Console에서 사이트 소유권 인증 후 `/sitemap.xml`을 제출한다.
- 네이버 Search Advisor에서 사이트 소유권 인증 후 `/sitemap.xml`을 제출한다.
- 대표 신규 URL의 수집·색인 상태를 확인한다.
- 검색어별 노출·클릭·CTR을 확인한다. 로컬 코드 검증은 색인이나 검색 노출 증거가 아니다.


### 플랫폼 표기

한국어 사용자 문구와 문서에는 `치지직(CHZZK)`을 사용한다.
영어·일본어 문구의 영문 브랜드명 `CHZZK`와 `/chzzk` 경로·코드 식별자는 유지한다.
[공식 개발자 문서](https://chzzk.gitbook.io/chzzk)의 영문 제목과 한국어 본문에서
각각 `CHZZK`와 `치지직`을 확인했으며, 이 프로젝트의 한국어 문구에는 두 이름을 병기한다.

### 최신 main 기준 로컬 검증

- `pnpm build`와 빌드 내 TypeScript 검사, `pnpm lint` 통과.
- `pnpm test:i18n` 3개 테스트 통과.
- 홈·신규 페이지 9개에서 고유 title·description·본문, H1 하나,
  canonical·OG, 크롤링 허용, 구조화 데이터와 내부 링크 응답 확인.
- 접두사 없는 경로와 영어·일본어 경로의 24개 308 redirect 확인.
- sitemap 29개 URL 전부 HTTP 200과 자기 canonical 확인.
- robots의 공개 허용·API 차단·sitemap 선언, 미등록 경로 404 확인.
- 홈·플랫폼 안내·개인정보 처리방침의 치지직(CHZZK) 표기 확인.
- 신규 8개 페이지의 320px 화면에서 H1 하나, 가로 넘침과 브라우저 콘솔 오류 없음 확인.

운영 배포·검색엔진 등록·색인·검색 노출과 실제 플랫폼 송출 E2E는 검증하지 않았다.
