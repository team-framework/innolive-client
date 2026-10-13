# 랜딩 목업 영상

`apps/landing/components/hero-demo-video.tsx`는 왼쪽 휴대폰 목업을 MP4로 재생한다. 서버 HTML에는 영상 주소를 넣지 않고, 화면에 보이는 동안에만 주소를 연결한다. 화면 밖으로 이동하거나 탭을 숨기면 재생을 멈춘다. 동작 줄이기 설정에서는 JPG 포스터만 표시한다.

원본 `Phone1.gif`는 변환용으로 보존하며 랜딩 화면에서 요청하지 않는다. 해상도 600×1280과 재생 길이 5.8초를 유지했다. H.264 압축으로 용량을 43,991,025바이트에서 1,526,576바이트로 줄였다. 원본과 25fps로 비교한 SSIM은 0.963828이다.

저장소 루트에서 재생 파일과 첫 프레임 포스터를 생성한다.

```sh
ffmpeg -v error -i apps/landing/public/mockups/Phone1.gif -an -c:v libx264 -preset slow -crf 23 -pix_fmt yuv420p -movflags +faststart -vf 'fps=25' apps/landing/public/mockups/hero-live-demo.mp4
ffmpeg -v error -i apps/landing/public/mockups/Phone1.gif -frames:v 1 -q:v 3 apps/landing/public/mockups/hero-live-demo.jpg
```

검증 시 landing lint·build, 데스크톱 및 390×844 화면의 재생, 화면 밖 일시 정지, 동작 줄이기 설정의 영상 주소 미연결을 확인한다. 성능 비교는 운영 배포 후 같은 Lighthouse 버전과 모바일·데스크톱 설정으로 수행한다. 서버 API 및 공유 계약 변경은 없다.
