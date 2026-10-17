# iOS 영상 업링크 송신 정책

관련: Android client #557(서버 AI 비트레이트), #580(송신 해상도 유지). 측정 기록은
[Android 온디바이스 AI 문서](android-on-device-ai.md)의 "서버 AI 업링크 비트레이트와 해상도 고정"에 있다.

## 배경

iOS는 서버 세션 WebRTC 업링크에 비트레이트와 degradation을 설정하지 않아 libwebrtc 기본값을 썼다.
연결 직후 낮은 비트레이트로 램프업하는 동안 저해상도로 보냈고(2026-10-02 iPhone 16 기록 270×480),
그 뒤에도 기본 상한에 묶였으며, 대역폭이 흔들리면 해상도부터 내렸다. 서버가 이 입력을 세션 해상도로
재인코딩하므로 업링크에서 잃은 디테일은 반환 영상과 방송 출력에 남는다.

## 정책

`VideoUplinkSendPolicy.forSession`이 세션 용도와 서버가 확인한 `broadcast_resolution`으로 값을 고른다.

| 세션 해상도 | 추정기 시작 | encoding 최소~최대 | 최대 fps | degradation |
| --- | --- | --- | --- | --- |
| 720p | 2Mbps | 1.5~3Mbps | 30 | MAINTAIN_RESOLUTION |
| FHD | 4Mbps | 3~6Mbps | 30 | MAINTAIN_RESOLUTION |

- 시작값은 Android `WebRtcUplinkBitratePolicy.kt`, 최소·최대·fps·`scaleResolutionDownBy` 1.0은
  `WebRtcVideoTransportPolicy.kt`와 같다.
- 서버 AI와 온디바이스 + 서버 송출에 같은 값을 쓴다. Android는 온디바이스면 항상 앱 직접 송출이라
  대응 경로가 없다. 서버가 두 경우 모두 같은 방식으로 재인코딩하므로 서버 AI 값을 따랐다.
- 앱 직접 송출 세션은 정책을 적용하지 않는다. 송출 중에는 기존대로 서버행 영상을 300kbps·15fps로
  제한하고(client #481), 제한을 풀면 라이브러리 기본값으로 돌아간다.
- 서버가 `broadcast_resolution`을 주지 않으면 서버 기본값 720p로 시작한다. 방송 중 값이 빠진
  스냅샷은 무시한다.

## 적용 시점

- offer 전: `preparePeerConnection`이 영상 sender를 만든 직후 encoding 범위와 degradation을 넣고
  `setBweMinBitrateBps`로 추정기 시작값과 상한을 준다.
- PeerConnection 재생성: 네트워크 복구도 `start`에서 `preparePeerConnection`을 다시 거치므로 같은
  값을 적용한다. 재연결 때 현재 세션 해상도를 credentials로 넘긴다.
- 세션 해상도 변경: 세션 응답을 반영할 때마다 `setBroadcastResolution`을 부른다. 값이 바뀌면 같은
  연결의 encoding 범위와 추정기 상한만 바꾸고 시작값은 다시 주지 않는다. 쌓인 추정치를 버리지 않기
  위해서다.
- 코덱 순서(VP8 우선)는 바꾸지 않았다.

## 진단 로그 (DEBUG 빌드만)

Console에서 subsystem `com.framework.innolive`, category `uplink`로 본다. Release 빌드는 기존 로그만 남긴다.

- `uplink send policy`: 적용 직후 sender 파라미터를 다시 읽어 degradation, 최소·최대 비트레이트,
  fps, 축소 배율과 `setBweMinBitrateBps` 결과를 남긴다.
- `uplink video`: 2초 샘플마다 `qualityLimitationReason`, 송신 크기, fps, `targetBitrate`,
  `encoderImplementation`, `qualityLimitationResolutionChanges`를 남긴다.

## 검증 상태

- 시뮬레이터 XCTest: 정책 값, 직접 송출 제한과의 합성, 실제 LiveKitWebRTC sender가 offer/answer와
  해상도 변경 뒤에도 값을 유지하는지 확인했다.
- 실기기 미검증: 목표 해상도 도달 시간, 목표 비트레이트, CPU·발열, 온디바이스 AI fps, 셀룰러 초기 손실.
