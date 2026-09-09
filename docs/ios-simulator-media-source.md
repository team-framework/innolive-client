# iOS Simulator 테스트 영상

Debug Simulator에서는 `apps/ios/InnoLive/InnoLive/Resources/SimulatorFixture.mp4`를 카메라 대신 WebRTC에 넣는다. 실기기와 Release는 기존 카메라를 사용한다. 파일의 오디오는 사용하지 않으며 마이크 경로는 기존과 같다.

테스트 영상을 바꾸려면 같은 이름의 MP4로 파일을 교체하고 다시 빌드한다. 현재 파일은 얼굴 없는 320×180, 15 FPS, 2초 영상이다.

영상 디코딩과 반복 재생은 기존 LiveKit WebRTC의 `LKRTCFileVideoCapturer`가 담당한다. 별도 영상 생성기, 파일 선택 화면, 지표 대시보드, 커스텀 디코더는 두지 않는다.

앱의 로그인·서버 연결 흐름은 그대로 사용한다. HTTP 및 signaling 계약도 변경하지 않는다. 번들 파일이 없거나 읽기에 실패하면 오류를 표시한다.

## 검증

Xcode 26.6, iPhone 17 / iOS 26.5 Simulator에서 확인했다.

- 기존 테스트 21개와 `SimulatorVideoInputTests` 2개, 총 23개 통과
- 번들 MP4가 2초 분량을 넘어 반복되고 중지 후 프레임 전달을 멈추는지 확인
- 누락 파일 오류 callback 확인
- iOS Release 빌드 통과

실기기 카메라·마이크와 서버 비식별화·YouTube E2E는 이번 변경에서 실행하지 않았다. 파일 입력 검사와 실제 송출 검증은 구분한다.
