# Landing 로딩 성능

대상은 `apps/landing`의 Web 랜딩이다. HTTP·signaling 계약 변경은 없다.

## 첫 화면

- 얼굴 등록 이미지와 후면 폰 프레임은 WebP와 반응형 `next/image`로 제공한다.
  첫 화면 이미지는 eager로 요청한다. 전면 폰 프레임은 1080px WebP를 초기
  HTML에 포함해 별도 요청을 없앤다. HTML과 클라이언트 JS 중복으로 약 49KB를
  추가 전송하지만 모바일 LCP와 점수 편차가 개선돼 이 방식을 적용했다.
- 영상 포스터는 초기 HTML에 포함하고 높은 우선순위로 요청한다.
  포스터와 페이지 로딩을 완료한 뒤 화면에 보이는 영상을 자동재생한다.
- 화면 밖이나 백그라운드에서는 영상을 멈춘다. 동작 줄이기 설정에서는
  영상 소스를 제거하고 포스터를 표시한다.
- 모바일 영상은 표시 폭에 기기 픽셀 비율을 곱한 값이 360px 이하일 때 사용한다.
  큰 화면과 높은 픽셀 비율에서는 기존 600px 영상을 사용한다.
- 모바일과 동작 줄이기 설정에서는 인트로를 숨겨 사용하지 않는 장면 이미지를
  요청하지 않는다. GSAP는 첫 화면을 그린 뒤 불러오며, 아래쪽 섹션의 애니메이션은
  화면 접근 시 초기화한다. 초기화 실패 시 콘텐츠와 기본 스크롤을 유지한다.

## 아래쪽 이미지와 폰트

- 개인정보 안내 이미지는 섹션이 화면 400px 앞에 도달하면 낮은 우선순위로
  요청한다. 기존 슬라이드 전환과 스크롤 동작을 유지한다.
- Wanted Sans는 Latin, 자주 쓰는 문자, 나머지 Unicode 범위로 분할한다.
  한국어·일본어 페이지는 공통 문자 청크도 preload하며 CSS와 같은 URL을 사용한다.
- 원본 글자 12,032개와 굵기 400–1000을 보존한다. 원본이 지원하지 않는 일본어
  글자는 기존 system fallback으로 표시한다.

## 재생성과 검증

`apps/landing`에서 실행한다.

```sh
node scripts/optimize-landing-images.mjs
npm run test:i18n
node scripts/check-intro.cjs
node scripts/check-privacy.cjs
node scripts/check-animation-loading.cjs
npm run build
```

폰트 재생성은 `apps/landing/app/fonts/wanted-sans/README.md`, 영상 인코딩은
`docs/landing-performance-media.md`를 따른다. 원본 PNG와 폰트는 보존한다.

production build를 실행한 서버에서 Lighthouse 모바일·데스크톱을 각각 3회
측정한다. 같은 Chrome·Lighthouse·throttling 설정을 사용하고 중앙값과 범위를
함께 기록한다. 브라우저에서는 한·영·일 화면, 이미지 로딩, 영상 재생과 정지,
동작 줄이기 설정, console 오류를 확인한다. 로컬 점수와 운영 배포 후 점수는
별도로 기록한다.

## 측정 결과

[SEO 조치 전·기존 기준·최종 비교](landing-performance-results.md)와
`landing-performance-evidence/`의 요약 JSON을 참고한다.
