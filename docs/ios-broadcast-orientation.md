# iOS YouTube 방송 방향 고정

대상은 `apps/ios`의 화면·미리보기·카메라 회전 처리다. HTTP/WebRTC signaling 계약 변경은 없다.

## 동작

홈 진입과 서버 카메라 연결 후에는 화면과 미리보기가 기기의 세로·가로 방향을 따른다. `HomeView.isBroadcasting`은 서버 업링크 연결 상태를 나타낸다.

작은 원본 미리보기는 세로 화면에서 약 120×213.33pt, 가로 화면에서 약 213.33×120pt다. 모서리 배치와 드래그 범위 계산에 같은 크기를 사용한다. 회전하면 드래그 이동량을 초기화하고 선택한 모서리를 유지한다.

설정 시트, `방송 준비`(prepare), 준비 완료(`prepared`) 대기 상태에서는 회전할 수 있다. 사용자가 `방송 시작`(goLive)을 누르면 입력 검증을 마친 뒤 첫 네트워크 요청 전에 현재 `UIWindowScene.effectiveGeometry.interfaceOrientation`으로 화면과 송출 프레임을 고정한다. 세로에서 시작하면 세로, 가로에서 시작하면 해당 가로 방향을 유지한다.

고정은 `going_live` / `live` / 일시 중지 / 업링크 재연결 / 종료 요청 중에도 유지한다. 같은 goLive 재시도는 처음 잠근 방향을 유지한다. goLive가 실패해 다시 `prepared`가 되면 잠금을 풀어 방향을 다시 고를 수 있다. 종료 성공은 업링크가 남아 있어도 잠금을 푼다. 종료 실패는 방송이 남아 있으므로 잠금을 유지한다. 리셋·로그아웃·업링크 최종 실패는 잠금을 지운다.

방송 중 카메라·렌즈 전환, 줌, 세로 스와이프는 기존 busy/rollback 규칙을 따른다. 잠근 방향은 유지하고, 전·후면이 바뀌면 송출 회전값만 그 방향과 카메라 위치에 맞게 다시 계산한다. 카메라 위치는 새 캡처를 켜기 전에 반영하고, 전환 실패로 되돌릴 때도 같다.

## 구현

- `BroadcastOrientationPolicy`가 미리보기 크기, 지원 마스크, WebRTC·프리뷰 회전 매핑을 맡는다. 수명주기는 `YouTubeIntegration`의 goLive/stop/reset 경로가 직접 적용한다.
- `BroadcastOrientationController`가 AppDelegate의 `application(_:supportedInterfaceOrientationsFor:)`로 지원 방향을 제한한다. 전경 씬의 뷰 컨트롤러에 `setNeedsUpdateOfSupportedInterfaceOrientations`와 `setNeedsUpdateOfPrefersInterfaceOrientationLocked`를 호출한 뒤 `requestGeometryUpdate`를 요청한다.
- 브리지 컨트롤러가 추적한 전경 씬의 화면 방향을 고정 기준으로 사용한다.
- iPad 창 모드와 Stage Manager에서는 시스템과 SwiftUI 호스팅 구조에 따라 화면 방향 고정에 제약이 있다. 전체 화면과 창 모드를 구분해 수동 확인한다.
- 송출 회전은 `RTCCameraVideoCapturer`와 같다. interface landscape left/right는 device landscape와 반대다. 전면 landscape left는 0°, 후면은 180°이고 landscape right는 그 반대다. 세로 90°, 거꾸로 270°. 로컬 `AVCaptureVideoPreviewLayer` 잠금 각도도 같은 카메라 위치 매핑을 쓴다. 잠금이 풀리면 `AVCaptureDevice.RotationCoordinator`가 센서 마운트를 따른다. 카메라가 바뀌면 이전 코디네이터 콜백은 세대/식별자로 버린다.
- `YouTubeIntegration`은 자신이 획득한 잠금 세대만 해제한다. 잠그지 않은 인스턴스는 공유 컨트롤러를 풀지 않는다. `endBroadcast`·`recoverFromVideoUplinkFailure`·`reset`은 진입 시 방송 작업을 무효화해, 진행 중인 goLive 재시도가 종료 뒤에 live를 복구하거나 추가 요청을 보내지 못하게 한다.
- `WebRTCCameraFrameRelay`가 잠금 중에만 `LKRTCVideoFrame` 회전을 덮어쓴다. 픽셀 버퍼, `timeStampNs`, 90kHz `timeStamp`, 얼굴 분석 주기는 유지한다. 재연결로 릴레이가 다시 만들어져도 업링크가 잠금 방향을 넘긴다.
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

실기기 전·후면 카메라, 실제 YouTube 송출 방향, iPad 창 모드는 별도 수동 검증이 필요하다.
