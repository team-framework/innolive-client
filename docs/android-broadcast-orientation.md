# Android YouTube 방송 방향 고정

대상은 `apps/android/InnoLive/app`의 방송 화면, CameraX 미리보기와 WebRTC 송출 프레임이다. HTTP/WebRTC signaling 계약은 변경하지 않는다. iOS 기준 동작은 [iOS 방송 방향 고정](ios-broadcast-orientation.md)을 따른다.

## 동작

방송 준비 및 `prepared` 상태에서는 Android의 화면 회전 설정에 따라 화면과 미리보기가 회전한다. `goLive` 작업이 수락되면 첫 방송 시작 요청 전에 현재 디스플레이 회전을 저장하고, 화면 방향과 CameraX `Preview` 및 `ImageAnalysis` 대상 회전을 고정한다. 세로와 좌우 가로 방향을 구분한다.

`going_live`, `live`, 일시 중지, 재개, 종료 요청 중에는 같은 방향을 유지한다. 중복 `goLive` 요청은 기존 잠금을 변경하지 않는다. 방송 시작이 실패해 `prepared`로 돌아가거나 종료가 성공하면 잠금을 해제한다. 종료 요청 실패 시에는 기존 라이브 또는 일시 중지 상태로 복귀해 방향을 유지하고 재시도할 수 있다. 연결 최종 실패, 세션 교체, 로그아웃으로 인한 `close`도 잠금을 해제한다.

카메라 전환과 해상도 변경으로 CameraX use case가 다시 바인딩되면 저장된 대상 회전을 다시 적용한다. CameraX가 카메라 센서와 전·후면을 고려해 산출한 `ImageProxy.imageInfo.rotationDegrees`를 기존 `VideoFrame`에 전달한다. 픽셀 버퍼와 타임스탬프는 변경하지 않는다. 가로 화면에서는 메인 및 작은 미리보기의 가로세로 비율을 16:9로 사용한다.

일시적인 `DISCONNECTED`에서는 서버 설정의 디바운스 시간 동안 기존 연결의 자연 회복을 기다린다. 회복되지 않으면 네트워크가 사용 가능해질 때까지 새 시도를 보류하고, 같은 세션과 PeerConnection에서 ICE restart를 시도한다. 네트워크 대기 시간에는 시도 횟수를 소진하지 않지만 서버 복구 창은 계속 흐른다. 재연결 중 방향 잠금, CameraX use case와 방송 상태를 유지하며, 라이브 시작·재개만 막고 준비 취소·종료는 허용한다. 이전 협상의 늦은 answer·후보는 협상 ID로 무시한다. 협상 실패로 ID를 폐기한 뒤 PeerConnection이 다시 `CONNECTED`가 되어도 새 협상을 계속 시도한다.

복구 성공은 PeerConnection과 오디오 입력이 준비되고, 재연결 이후 영상 송신 RTP 패킷 수가 증가하며, 서버 세션의 `media.raw_video_track.ready_state`가 `live`일 때 확정한다. 영상 확인이 10초 안에 끝나지 않으면 현재 협상을 정리하고 복구 창 안에서 다시 시도한다. 복구 창 또는 시도 횟수를 소진해 최종 실패로 전환될 때 잠금을 해제한다. 방송 종료·로그아웃도 복구 작업을 취소하며, 종료 시 미디어가 여전히 끊겼다면 연결을 정리한다.

## 검증

- 단위 테스트: `./gradlew :app:testDebugUnitTest --offline`
- 기기 API 흐름 테스트: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.framework.innolive.feature.live.BroadcastApiFlowTest --offline`
- 실제 방송 수동 확인: 세로, 가로 왼쪽, 가로 오른쪽에서 각각 비공개 방송을 시작한다. 방송 중 기기를 돌리고 전·후면 카메라를 전환한 뒤 로컬 미리보기와 YouTube 수신 화면의 방향을 확인한다. 일시 중지·재개, 방송 시작 실패, 종료 실패·재시도도 확인한다.
- 태블릿 창 모드 및 화면 회전 제한 설정에서는 Android가 Activity 방향 요청을 제한할 수 있으므로 별도 확인한다.
