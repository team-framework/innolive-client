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
총 18개 URL과 언어별 대체 URL을 포함한다. 로그인·회원가입·카메라 체험 실행 경로는
사이트를 소개하는 sitemap에서 제외한다. 이 작업은 해당 페이지의 색인 정책을 바꾸지 않는다.
`lastModified`는 실제 콘텐츠 변경 시점을 관리할 때만 추가한다.

## 검증

- production build 후 27개 페이지의 응답, canonical, hreflang, 공유 제목·설명을 검사한다.
- JavaScript 실행 전 홈 HTML에 제목과 3개 검색 의도 FAQ, h1 문구가 있는지 검사한다.
- sitemap의 18개 URL과 robots.txt의 sitemap 선언을 검사한다.
- 브라우저에서 FAQ를 열고 답변과 기존 타이핑 애니메이션을 확인한다.
- 배포 후 Google Search Console에서 sitemap과 주요 URL을 제출하고 색인 여부를 확인한다.
- 이후 검색어별 노출·클릭·CTR을 비교한다. 코드 반영만으로 순위나 노출을 보장하지 않는다.

기준 측정은 2026-10-02 운영 사이트의 27개 페이지에서 수행했다. 당시 canonical과
hreflang은 없었고 robots.txt·sitemap.xml은 404였다. Lighthouse SEO는 100점이었으나
위 부재를 점수에서 검출하지 않았다. 성능 측정에는 로딩 시간 초과 경고가 있었다.

근거: [Google SEO 기본 가이드](https://developers.google.com/search/docs/fundamentals/seo-starter-guide),
[언어별 페이지 안내](https://developers.google.com/search/docs/specialty/international/localized-versions).

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
