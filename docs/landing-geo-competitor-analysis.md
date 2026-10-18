# 방송 모자이크 앱의 검색 근거 비교

확인일: 2026-10-17. 대상은 PRISM Lens, DualMoza, InnoLive다.

## 핵심 관찰

Google 브라우저에서 `실시간 방송 모자이크 앱`을 검색하고 `Try without personalisation`을 선택했다. 화면에 `These results are not personalised`가 표시된 상태에서 InnoLive 제품 페이지가 첫 웹 결과에 나타났고 AI Overview도 InnoLive와 공식 제품 페이지를 인용했다. 같은 화면의 노출된 결과에는 PRISM Lens·DualMoza가 없었다. 이는 해당 시점·질문·지역의 한 번의 관찰이며 전체 순위나 추천율을 뜻하지 않는다.

따라서 InnoLive의 Google 노출이 전반적으로 경쟁 제품보다 낮다고 결론 낼 수 없다. 이전 ChatGPT 응답과 이번 검색 도구에서는 결과가 달랐다. 개선 목표는 기존 Google 노출을 보존하면서 다른 검색·답변 경로에서 쓸 수 있는 비교 근거를 제공하는 것이다.

## 관찰 조건과 결과

같은 세션에서 웹 검색 도구로 `실시간 방송 모자이크 애플리케이션 알려줘`, 제품명과 얼굴 블러·방송 검색어, `site:innolive.studio 실시간 방송 모자이크`, `"InnoLive" "모자이크"`를 검색했다. 국가·로그인·검색엔진별 순위를 고정한 순위 조사가 아니다. 아래는 반환된 결과와 읽을 수 있었던 공식 근거의 비교다.

| 제품 | 이번에 확인한 검색 근거 | 답변 작성에 쓸 수 있는 정보 |
|---|---|---|
| PRISM Lens | 공식 랜딩·FAQ·시작 가이드 | PC 가상 카메라 연결, 얼굴 효과와 대상 선택, 모바일 연동 절차 |
| DualMoza | Google Play·App Store·개발사 제품 페이지 | 모바일 직접 송출, 대상 선택, 방송 목적지, Pro 조건 |
| InnoLive | App Store | 얼굴 등록·블러·YouTube/CHZZK 송출·앱 이용 전제 |

InnoLive의 두 사이트 한정/브랜드 모자이크 검색은 이번 웹 검색 도구에서 결과가 없었다. 이는 위 Google 브라우저 결과와 구분한다. 제품 페이지·한국어 홈·robots.txt 직접 열기도 해당 웹 도구에서 접근 불가로 반환됐다. 같은 시점 로컬 HTTPS 요청은 robots.txt와 제품 페이지를 HTTP 200으로 받았다. 따라서 **외부 검색 도구의 발견·가져오기 경로에 차이가 있다는 관찰**까지 가능하다. 특정 방화벽 규칙, 도메인 제재, 전체 미색인 또는 검색 순위 하락을 원인으로 확정하지 않았다. 실제 검색 봇 IP의 서버 로그를 확인하지 않았다.

이전 ChatGPT 1회 응답은 PRISM·스토어 출처를 인용하고 InnoLive는 추천하지 않았다. 검색 가능한 설명의 범위와 질문 적합도가 후보 선정에 영향을 주었을 가능성은 있지만 내부 검색 순위와 선택 이유는 확인할 수 없다. 단순히 리뷰나 인지도 차이 때문이라고 판단하지 않았다.

## 적용한 개선

- 한영 `/compare/live-face-blur-apps`에 3개 제품의 기기·얼굴 공개 기준·효과·송출 방식·이용 조건을 비교하는 HTML 표 추가
- InnoLive 팀의 작성 사실, 공식 설명 기준, 성능 미측정, 출처 확인일 표시
- 공식 출처 링크와 구조화 데이터 citation·author·dateModified 일치
- 모바일 직접 송출과 가상 카메라 흐름 구분, 얼굴 등록 방식 설명, 기기·송출·성능 관련 FAQ 제공
- 기존 제품 페이지에서 연결하고 sitemap·한영 hreflang에 새 경로 추가
- 날짜는 실제 확인일로 고정하고 빌드 시각으로 갱신하지 않음

## 스토어 설명의 후속 개선안

InnoLive App Store에는 현재 `실시간 비식별화 솔루션` 부제와 라이브 방송 스튜디오 설명이 있다. DualMoza 스토어는 얼굴을 가린 모바일 방송 용도를 첫머리에 제시한다. 아래는 다음 스토어 메타데이터 수정 때 사용할 수 있는 초안이며 **스토어에 게시하지 않았다**. 출시 상태·지역별 필드 제한·현재 앱 기능을 검토하고 적용해야 한다.

- 한국어 부제 후보: `실시간 방송 얼굴 모자이크`
- 한국어 설명 첫 문단: `InnoLive는 Android와 iOS에서 사용하는 실시간 방송 모자이크 앱입니다. 공개할 출연자의 얼굴을 등록하면 미등록 얼굴을 블러로 가리고, 처리한 영상을 유튜브·치지직으로 송출할 수 있습니다.`
- iOS 스토어 설명에서는 첫 문장의 기기 범위를 `iPhone과 iPad`로 조정
- 영어 첫 문단 후보: `InnoLive is a live face blur app for iPhone and iPad. Register guests to keep their faces visible, blur unregistered faces, and broadcast the processed video to YouTube or CHZZK.`
- 방송 시간·연결 조건·감지 한계와 실제 지원 기능 유지. 비교 제품의 기능을 InnoLive 기능으로 옮기지 않음

외부 리뷰·백링크·독립 실측 자료는 이번 웹 수정으로 생성할 수 없다. 제3자 리뷰 작성, 허위 평점, 자동 추천 지시문은 추가하지 않았다. 재수집 접수와 실제 색인·인용은 따로 기록한다.

## 공식 출처

- PRISM 기능 FAQ: https://prismlive.com/en_us/faq/faq.html
- PRISM Lens 기기·연결: https://prismlive.com/en_us/lens.html
- PRISM 이용 조건: https://prismlive.com/en_us/plus.html
- PRISM 시작 가이드: https://guide.prismlive.com/lens/guides/getting-started-with-prism-lens
- DualMoza Google Play: https://play.google.com/store/apps/details?id=com.dualmoza.app
- DualMoza App Store: https://apps.apple.com/kr/app/dualmoza/id6756903535
- DualMoza 개발사: https://b19.co.jp/works/dualmoza/
- InnoLive App Store: https://apps.apple.com/kr/app/innolive/id6810674918

## 구현 검증

- build·lint·구조화 데이터 4개·번역 3개 테스트 통과
- 로컬 production 43 URL·25 구조화 페이지·67 FAQ·7 크롤러 정책 통과
- 비교표·출처 링크·작성 주체·날짜의 HTML/schema 일치와 한영 canonical·hreflang 확인
- 운영 배포·재수집 접수는 배포 후 별도 기록
