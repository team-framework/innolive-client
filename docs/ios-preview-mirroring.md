# iOS 전면 카메라 미리보기 좌우 반전

대상은 `apps/ios`의 원본 미리보기, 서버 반환 영상, 얼굴 등록 미리보기다. HTTP/WebRTC signaling 계약 변경은 없다. 캡처·업로드 프레임은 반전하지 않는다.

## 동작

전면 카메라일 때 아래 세 화면은 같은 가로 반전을 쓴다.

- 홈 원본 작은 미리보기(`WebRTCLocalPreviewView`)
- 서버가 돌려준 비식별화 영상(`WebRTCRemoteVideoView`)
- 얼굴 등록 미리보기(`WebRTCFaceRegistrationPreview`)

후면 카메라는 반전하지 않는다. 연결 전 `AVCaptureVideoPreviewLayer`는 시스템이 전면만 자동 반전한다. 카메라 전환 후 전·후면이 바뀌면 반환 화면의 반전도 같이 바뀐다.

## 구현

`BroadcastOrientationPolicy.previewDisplayTransform(isUsingFrontCamera:)`가 화면용 `CGAffineTransform`을 만든다. 원본·반환·얼굴 등록 미리보기가 이 값을 공유한다.

## 수동 확인

| 장면 | 기대 |
| --- | --- |
| 전면, 서버 연결 후 | 원본 작은 화면과 반환 영상의 좌우가 같다 |
| 후면 | 원본과 반환 모두 반전하지 않는다 |
| 전면→후면 전환 | 반환 화면 반전이 바로 풀린다 |
| 얼굴 등록 | 전면 미리보기 좌우가 홈 원본과 같다 |
