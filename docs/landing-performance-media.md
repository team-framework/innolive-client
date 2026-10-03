# Landing hero 영상 최적화

대상은 `apps/landing/public/mockups/hero-live-demo*.mp4`다. 두 영상 모두 H.264,
`yuv420p`, 25fps, 5.8초이며 MP4 헤더를 앞에 배치한다(`+faststart`).

| 파일 | 크기 | 해상도 | 실제 인코딩 입력 |
| --- | ---: | --- | --- |
| 기존 원본 | 1,526,576 bytes | 600×1280 | 변경 전 `hero-live-demo.mp4` |
| `hero-live-demo.mp4` | 1,034,419 bytes | 600×1280 | 기존 원본 |
| `hero-live-demo-mobile.mp4` | 534,549 bytes | 360×768 | 위의 1,034,419-byte 데스크톱 출력 |

현재 모바일 파일은 데스크톱 출력을 한 번 더 인코딩했다. 아래 재생성 명령은
두 출력 모두 별도로 보관한 원본에서 생성한다. 모바일 출력의 크기와 품질 지표는
현재 파일과 달라질 수 있다. 출력 파일을 다시 입력으로 사용하지 않는다.

## 원본에서 재생성

저장소 루트에서 실행한다. `HERO_SOURCE`를 출력 경로와 다른 원본 경로로 지정한다.

```sh
HERO_SOURCE="/absolute/path/to/original-hero-live-demo.mp4"

# 별도 보관본이 없으면 변경 전 Git blob을 원본 경로로 추출한다.
git show 783b2ba7aea50f277e4698be2299e7bc41dbed9d:apps/landing/public/mockups/hero-live-demo.mp4 > "$HERO_SOURCE"

ffmpeg -v error -i "$HERO_SOURCE" -an \
  -c:v libx264 -preset slow -crf 25 -pix_fmt yuv420p \
  -movflags +faststart apps/landing/public/mockups/hero-live-demo.mp4 -y

ffmpeg -v error -i "$HERO_SOURCE" -vf scale=360:-2 -an \
  -c:v libx264 -preset slow -crf 23 -pix_fmt yuv420p \
  -movflags +faststart apps/landing/public/mockups/hero-live-demo-mobile.mp4 -y
```

실제 작업에서는 두 번째 명령의 `-i`에 이미 압축한 데스크톱 파일을 지정했다.
인코더 버전은 FFmpeg 9.0.2, `libx264`이며 버전이 달라지면 결과도 달라질 수 있다.

## 품질 확인

SSIM은 프레임 수·시각·해상도를 맞춰 비교한다. 모바일 비교에서는 참조 영상을
같은 360×768 크기로 축소한다. 저장소 루트에서 실행한다.

```sh
ffmpeg -i "$HERO_SOURCE" \
  -i apps/landing/public/mockups/hero-live-demo.mp4 \
  -lavfi ssim -f null -

ffmpeg -i "$HERO_SOURCE" \
  -i apps/landing/public/mockups/hero-live-demo-mobile.mp4 \
  -filter_complex '[0:v]scale=360:768[ref];[ref][1:v]ssim' -f null -
```

현재 데스크톱의 원본 대비 전체 SSIM은 **0.982503**이다. 현재 모바일의
**데스크톱 출력 축소본 대비** 전체 SSIM은 **0.983515**다. 이 모바일 값은 원본과의
직접 비교 결과가 아니다. 두 영상의 2초 프레임을 시각 비교했고 UI 글자와 버튼을
확인했다. SSIM만으로 전체 재생 품질을 보장하지 않으므로 재생성 후에도 움직임과
UI 선명도를 확인한다.

## 로딩 및 파일 선택

`HeroDemoVideo`는 포스터를 `eager`와 높은 fetch priority로 먼저 요청한다.
포스터 준비와 페이지 load 완료 후, 화면에 보이며 문서가 활성 상태일 때 영상을
연결한다. 임의 지연 시간은 사용하지 않는다.

최초 연결 시 화면 너비가 1024px 미만이고
`video.getBoundingClientRect().width * window.devicePixelRatio <= 360`이면 모바일
파일을 사용한다. 더 많은 실제 픽셀이 필요한 기기는 600px 파일을 사용한다.
연결 후 창 크기가 바뀌어도 재다운로드하지 않는다. 화면 밖이나 백그라운드에서는
일시정지하고 복귀하면 재생한다. reduced-motion 설정에서는 영상 source를 제거하고
포스터를 표시한다.
