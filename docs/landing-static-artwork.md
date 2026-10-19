# Landing 정적 반응형 이미지

휴대폰 프레임과 YouTube·CHZZK 로고는 빌드 전에 WebP 변형을 생성한다.
운영 Lighthouse에서 이 세 이미지의 `/_next/image` 요청이 완료되지 않아
45초 제한 경고가 발생한 것을 확인했다. 휴대폰 프레임은 별도 HTTP 요청에서도
응답이 끝나지 않았다. 이 변경은 해당 요청을 정적 파일로 전환하며 이미지 변환
서버 내부의 지연 원인까지 진단한 것은 아니다.

## 재생성

저장소 루트에서 실행한다.

```sh
node apps/landing/scripts/optimize-static-artwork.mjs
```

- 입력: `public/landing/{hero-phone.webp,youtube-logo.png,chzzk-logo.png}`.
- 출력: `public/landing/optimized/`의 WebP 파일과 `lib/static-artwork.json`.
- 크기: 256·384·640·1080px와 원본 너비. 원본보다 큰 변형은 생성하지 않는다.
- 인코딩: Next.js 의존성의 Sharp, WebP quality 75·effort 6.
- 파일명: 이미지 이름·너비·콘텐츠 SHA-256 앞 12자. 파일 내용이 바뀌면 URL도 바뀐다.

`StaticArtwork`는 manifest의 `srcSet`과 기존 `sizes`를 사용해 브라우저가
해상도를 선택하게 한다. 로고의 lazy loading과 전면 프레임의 높은 fetch priority를
유지한다. 이미지 크기·alt·레이아웃을 호출 위치에서 확인한다.

`Hero`와 `TypewriterHeroHeading`은 Server Component이며, 영상·다운로드 메뉴·
포인터 흐림 효과는 Client Component로 유지한다. 전면 프레임의 Base64 내장과
해당 JavaScript 모듈은 제거했다.

## 검증 기록

- 15개 변형의 파일 존재·너비·콘텐츠 해시 일치 확인.
- 프로덕션 빌드·TypeScript·변경 파일 ESLint 통과.
- 서버 HTML에서 전면 이미지의 responsive preload와 Base64 제거 확인.
- 한국어→영어 전환 후 제목·체험 링크 갱신, 정적 프레임·로고 로딩 확인.
- gzip 기준 초기 HTML 46,390→23,538 bytes, 초기 script 합계 239,853→214,637 bytes.

마지막 수치는 같은 로컬 빌드 조건에서 `0e16ad7a`와 이 변경을 비교한 값이다.
실제 HTTP 전송량이나 Lighthouse 점수를 대신하지 않는다. 운영 측정은 #669에 기록한다.
