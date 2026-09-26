# iOS 버전 지원

`apps/ios`는 iOS 18.0 이상을 지원한다. 프로젝트, 앱, 테스트의 Debug/Release 배포 타깃을 모두 18.0으로 맞춘다. 최신 Xcode SDK로 빌드하며 HTTP·WebRTC 계약은 변경하지 않는다.

## 버전별 동작

- iOS 26 이상: 기존 Liquid Glass 컨테이너, 배경, 버튼을 사용한다.
- iOS 18: 같은 화면 구성에 `regularMaterial` 배경과 `bordered` / `borderedProminent` 버튼을 사용한다. 공통 분기는 `InnoLiveGlass.swift`에서 관리한다.
- 흔들림 보정: iOS 26 이상에서 기기가 지원하면 `lowLatency`를 사용한다. iOS 18에서는 지원되는 `standard` 보정을 사용하고, 기기가 지원하지 않으면 기존 미지원 상태를 표시한다.
- 방송 화면 방향 고정: 지원 방향 마스크와 `requestGeometryUpdate`를 사용한다. iOS 26의 추가 방향 고정 갱신 API는 버전 확인 후 호출한다.
- Core ML 모델을 별도로 번들에 넣는 경우 모델의 최소 배포 버전도 iOS 18 이하여야 한다.

## 검증 방법

```sh
xcodebuild -project apps/ios/InnoLive/InnoLive.xcodeproj \
  -scheme InnoLive -configuration Debug \
  -destination 'generic/platform=iOS' build

xcodebuild -project apps/ios/InnoLive/InnoLive.xcodeproj \
  -scheme InnoLive -destination 'id=<connected-device-udid>' \
  -parallel-testing-enabled NO \
  -only-testing:InnoLiveTests/IOSCompatibilityRenderingTests \
  -only-testing:InnoLiveTests/BroadcastVideoQualitySettingsTests \
  -only-testing:InnoLiveTests/BroadcastOrientationLockTests \
  -only-testing:InnoLiveTests/BroadcastOrientationPolicyTests test
```

렌더링 테스트는 실기기의 UIKit/SwiftUI로 주요 화면을 표시하고 밝은·어두운 테마의 PNG를 xcresult에 첨부한다. 입력란 배치와 기본 컨트롤 가시성을 확인한다. 인증·방송 저장소는 격리하고 로그인, 공개 방송, 계정 삭제는 실행하지 않는다. 실제 카메라·서버·YouTube 송출 검증은 별도로 수행한다.
