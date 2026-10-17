# Android 방송 녹화와 다운로드

관련 이슈: client #640. iOS 원본 저장([ios-broadcast-recording.md](ios-broadcast-recording.md))을 참고하되
Android는 블러 전 영상과 실제 송출 영상을 함께 저장한다. HTTP·WebRTC 계약은 바꾸지 않는다.

## 녹화 구간과 입력

- 방송 시작(golive) 요청 직전에 녹화를 시작하고, 라이브·일시 중지를 확인한 뒤 마지막 대상 종료·연결 실패·세션 종료에서 저장한다. 시작을 확인하지 못한 녹화는 버린다. `BroadcastRecordingCoordinator`가 이 전이를 맡고 녹화 오류는 방송 상태를 바꾸지 않는다.
- 블러 전 영상은 `CameraFrameAnalyzer`가 비식별화·색 보정 전 카메라 프레임을 I420으로 복사해 넘긴다. 서버 AI와 온디바이스 모드가 같은 경로를 쓴다.
- 블러 후 영상은 실제 송출 프레임이다. 온디바이스(WebRTC·직접 송출)는 분석기의 가림 처리 출력(가림을 끈 경우 보정 프레임), 서버 AI는 서버가 돌려주는 원격 트랙을 받는다. 서버 반환 영상은 서버 송출 화질을 따르며 재협상으로 트랙이 바뀌면 입력을 옮긴다.
- 음성은 송출 경로의 마이크 PCM이다. 서버 중계는 WebRTC 녹음 콜백(에코 제거 전 입력), 직접 송출은 엔진에 넣는 PCM이며 대기 화면 중 무음과 음소거를 그대로 담는다.
- 영상은 첫 프레임 크기(긴 변 최대 1920)로 H.264 인코더를 만들고 이후 프레임은 비율을 유지해 맞춘다. 블러 전·후 인코더 둘과 AAC 인코더 하나를 추가로 쓰므로 송출 인코더까지 포함해 기기 인코더 세션이 늘어난다. 인코더를 만들지 못하면 녹화만 실패한다.
- 시간축은 카메라 타임스탬프를 `System.nanoTime` 기준으로 옮긴 값과 누적 샘플 수로 만든 음성 시각이다. 250ms 넘는 음성 공백은 도착 시각으로 다시 맞춘다.

## 저장 파일

- 위치는 앱 no-backup 저장소의 `recordings/<id>/`이며 `original.mp4`(블러 전 + 음성), `processed.mp4`(블러 후 + 음성), `meta.json`이다. 시스템 백업과 기기 이전에서 제외된다.
- `MediaMuxer`는 영상·음성 형식을 모두 받은 뒤 시작하고 첫 키프레임부터 기록한다. 종료 시 인코더 입력 종료를 알려 마지막 출력까지 받은 뒤 먹서를 멈추고 길이·트랙을 확인해 `.partial.mp4`에서 최종 이름으로 바꾼다.
- 원본 파일을 만들지 못하면 녹화 실패로 폴더를 지운다. 블러 후 파일만 실패하면 원본만 있는 녹화로 저장한다.
- 메타데이터가 없는 폴더는 먹서를 멈추지 못한 파일이라 재생할 수 없으므로 목록을 읽을 때 지운다. iOS와 달리 중단된 MP4 복구는 하지 않는다.
- 녹화 시작 전 여유 공간 512MB를 확인한다. 저장 중 공간 부족은 녹화 실패로 표시한다.

## 다운로드

설정의 `저장된 방송`에서 항목마다 다섯 형식 중 하나를 골라 `Download/InnoLive`에 저장한다. 모두 트랙 복사이며 재인코딩하지 않는다.

| 형식 | 원본 파일 | 트랙 | 파일 |
| --- | --- | --- | --- |
| 블러 전 영상 + 오디오 | original.mp4 | 복사 | `InnoLive_<시각>_original.mp4` |
| 블러 전 영상, 오디오 없음 | original.mp4 | 영상 | `InnoLive_<시각>_original-muted.mp4` |
| 블러 후 영상 + 오디오 | processed.mp4 | 복사 | `InnoLive_<시각>_blurred.mp4` |
| 블러 후 영상, 오디오 없음 | processed.mp4 | 영상 | `InnoLive_<시각>_blurred-muted.mp4` |
| 오디오만 | original.mp4 | 음성 | `InnoLive_<시각>_audio.m4a` |

- 블러 후 파일이 없는 녹화는 해당 두 형식을 고를 수 없다.
- `MediaStore.Downloads`에 `IS_PENDING`으로 만든 뒤 복사하고 공개한다. 실패하면 만들다 만 항목을 지운다. 별도 저장소 권한은 필요 없다.
- 방송 중에는 내보내기를 막고 목록 확인과 삭제만 허용한다. 삭제는 앱 안의 파일만 지우며 다운로드 폴더의 사본은 유지한다.

## 검증

- 단위 테스트: `BroadcastRecordingCoordinatorTest` 4개(확인 뒤 저장, 미확인 폐기, 연결 종료, 시작 거절), `RecordingAudioTimelineTest` 3개, `BroadcastRecordingEncodersTest` 2개, `BroadcastRecordingExportFormatTest` 3개, `BroadcastRecordingStoreTest` 3개.
- 실제 기기에서 서버 AI·온디바이스·직접 송출 각 모드의 녹화 파일 재생, 블러 전·후 영상의 A/V 동기, 인코더 세션 한도, 장시간 녹화 발열과 용량, 다운로드 폴더 저장은 실기기 검증 항목이다.
