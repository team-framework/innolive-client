# iOS 테스트 영상 생성

`generate.py`는 사용 권한이 있는 정면 얼굴 이미지 한 장에서 6초·24 FPS·640×640 MP4 7개를 만든다. Python 3, FFmpeg(`libx264` 포함), ffprobe가 필요하다. 결과는 앱 번들에 자동으로 포함하지 않는다.

```bash
python3 apps/ios/TestVideos/generate.py \
  --portrait /absolute/path/portrait.png \
  --output /tmp/innolive-test-videos
```

출력 폴더는 새 폴더나 빈 폴더여야 한다. 생성기는 각 영상의 해상도·FPS·144개 프레임을 ffprobe로 검사하고, 원본 및 출력 SHA-256과 FFmpeg 버전을 `manifest.json`에 기록한다. 같은 FFmpeg 버전과 원본으로 재생 조건을 반복할 수 있다. 인코더 버전이 달라지면 파일 바이트는 달라질 수 있다.

| 파일 | 구성 | 확인할 동작 |
| --- | --- | --- |
| `single-face.mp4` | 원본 한 장 | 디코딩·기본 프레임 전달 |
| `multiple-faces.mp4` | 같은 인물 이미지 두 장 | 다중 얼굴이 있는 입력 전달 |
| `moving-face.mp4` | 느린 이동 crop | 프레임 갱신·타이밍 |
| `partial-face.mp4` | 화면 오른쪽 절반 가림 | 가림 입력·회귀 비교 |
| `low-light.mp4` | 밝기·대비 감소 | 어두운 입력 전달 |
| `fast-motion.mp4` | 빠른 이동 crop | 반복 재생·드롭 관찰 |
| `no-face.mp4` | FFmpeg 색상 패턴 | 얼굴 없는 기본 경로 |

얼굴이 중앙에 있는 입력을 사용한다. 가림 위치와 crop은 고정되어 있어 다른 사진에서는 얼굴이 가려지는 비율이 달라질 수 있다. 두 얼굴은 같은 사진의 복제이며 서로 다른 신원을 의미하지 않는다. 이동은 정지 이미지의 crop이고 실제 인물 움직임·모션 블러를 재현하지 않는다. 탐지 수와 비식별화 정답은 포함하지 않으며, 이 영상으로 모델 정확도나 실기기 성능을 주장하지 않는다.

공개 예시로 [scikit-image의 astronaut 이미지](https://scikit-image.org/docs/0.19.x/api/skimage.data.html#skimage.data.astronaut)를 사용할 수 있다. 해당 문서는 NASA 촬영 사진을 public domain으로 설명한다. [v0.19.3 원본](https://raw.githubusercontent.com/scikit-image/scikit-image/v0.19.3/skimage/data/astronaut.png)을 별도로 받아 `--portrait`에 전달한다. 예시 사진은 앱이나 이 저장소에 포함하지 않는다.

실제 등록 인물·미등록 인물·입퇴장 시나리오는 동의를 받은 개발용 영상으로 별도 확인한다. 생성한 파일은 Debug 입력 선택에서 가져와 사용한다. 실제 비식별화 결과는 서버 연결 후 확인하고 로컬 프리뷰 결과와 구분한다.
