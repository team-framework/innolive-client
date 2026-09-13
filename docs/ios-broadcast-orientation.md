# iOS YouTube 방송 방향 고정

대상은 `apps/ios`다. HTTP와 WebRTC signaling 계약은 바꾸지 않는다. `Info.plist`의 지원 방향은 그대로 둔다.

## 동작

홈에 들어오거나 서버 카메라 업링크가 연결돼도 방향을 고정하지 않는다. `HomeView.isBroadcasting`은 업링크 연결이지 YouTube 공개 방송이 아니다. 미리보기와 UI는 기기 세로·가로 왼쪽·오른쪽을 따른다.

작은 원본 미리보기는 화면이 세로면 120×213.33, 가로면 213.33×120이다. 모서리 스냅·드래그 클램프는 같은 실제 크기를 쓴다. 회전 중 드래그는 버리고 모서리는 유지한다.

설정 시트와 `방송 준비`(prepare)와 `prepared` 대기는 돌릴 수 있다. 사용자가 `방송 시작`(goLive)을 누른 뒤에만, 검증이 끝난 직후·첫 goLive 요청 전에 그때의 `UIWindowScene.interfaceOrientation`으로 UI와 송출 프레임을 고정한다. 세로에서 시작하면 세로, 가로에서 시작하면 가로로 남는다.

고정은 `going_live` / `live` / 일시 중지 / 업링크 재연결 / 종료 요청 중에도 유지한다. 같은 goLive 재시도는 처음 잠근 방향을 유지한다. goLive가 실패해 다시 `prepared`가 되면 잠금을 풀어 방향을 다시 고를 수 있다. 종료 성공은 업링크가 남아 있어도 잠금을 푼다. 종료 실패는 방송이 남아 있으므로 잠금을 유지한다. 리셋·로그아웃·업링크 최종 실패는 잠금을 지운다.

방송 중 카메라·렌즈 전환, 줌, 세로 스와이프는 기존 busy/rollback 규칙을 따른다. 잠근 방향은 유지하고, 전·후면이 바뀌면 송출 회전값만 그 방향과 카메라 위치에 맞게 다시 계산한다.

## 구현

- `BroadcastOrientationPolicy`가 미리보기 크기, 지원 마스크, WebRTC 회전 매핑, 수명주기 결정을 맡는다.
- `BroadcastOrientationController`가 AppDelegate 마스크, `requestGeometryUpdate`, presented VC `setNeedsUpdateOfSupportedInterfaceOrientations`, iPad 윈도우 모드용 `prefersInterfaceOrientationLocked`를 적용한다. 잠금 기준은 기기 raw orientation이 아니라 scene interface orientation이다.
- 송출 회전은 `RTCCameraVideoCapturer`와 같다. interface landscape left/right는 device landscape와 반대다. 전면 landscape left는 0°, 후면은 180°이고 landscape right는 그 반대다. 세로 90°, 거꾸로 270°.
- `WebRTCCameraFrameRelay`가 잠금 중에만 `LKRTCVideoFrame` 회전을 덮어쓴다. 픽셀 버퍼와 타임스탬프, 얼굴 분석 주기는 유지한다. 재연결로 릴레이가 다시 만들어져도 업링크가 잠금 방향을 넘긴다.
- Debug Simulator 파일 입력은 카메라 릴레이가 없다. UI 잠금은 적용되지만 파일 프레임 회전은 capturer 값을 그대로 보낸다.

## 수동 확인

| 장면 | 기대 |
| --- | --- |
| 업링크만 연결 | 자유롭게 회전, 작은 미리보기 가로/세로 크기 변경 |
| 방송 준비 / prepared | 계속 회전 가능 |
| 세로에서 방송 시작 | UI와 송출이 세로로 고정 |
| 가로 왼쪽/오른쪽에서 시작 | 해당 가로로 고정, 전·후면 전환 후에도 같은 방향 |
| 일시 중지, 재연결, 종료 실패 | 잠금 유지 |
| 종료 성공, goLive 실패, 리셋 | 다시 회전 |

실기기 전·후면과 YouTube 실제 송출은 시뮬레이터만으로 확인하지 않는다.
