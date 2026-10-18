# iOS 영상 업링크 송신 정책

관련: Android client #557(서버 AI 비트레이트), #580(송신 해상도 유지), #627(Android 송신 하한 하향). 측정 기록은
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

- 시작값은 Android `WebRtcUplinkBitratePolicy.kt`, 최대·fps·`scaleResolutionDownBy` 1.0은
  `WebRtcVideoTransportPolicy.kt`와 같다.
- 최소는 의도적으로 다르다. Android는 client #627부터 FHD 1Mbps, 720p 0.5Mbps로 낮춰 혼잡 구간에서
  추정기가 하한 아래로 내려갈 여지를 둔다. iOS는 3Mbps·1.5Mbps를 유지한다.

| 플랫폼 | 720p 최소 | FHD 최소 |
| --- | --- | --- |
| iOS | 1.5Mbps | 3Mbps |
| Android (#627 이후) | 0.5Mbps | 1Mbps |

- 서버 AI와 온디바이스 + 서버 송출에 같은 값을 쓴다. Android는 온디바이스면 항상 앱 직접 송출이라
  대응 경로가 없다. 서버가 두 경우 모두 같은 방식으로 재인코딩하므로 서버 AI 값을 따랐다.
- 앱 직접 송출 세션은 정책을 적용하지 않는다. 송출 중에는 기존대로 서버행 영상을 300kbps·15fps로
  제한하고(client #481), 제한을 풀면 라이브러리 기본값으로 돌아간다.
- Android는 이 부분도 다르다. 온디바이스(앱 직접 송출) 세션에도 최소·최대·30fps·해상도 유지를 그대로 넣고
  (`WebRtcConnection.addVideoTransceiver`), 추정기 시작값·상한(`setBitrate`)만 생략한다. 300kbps·15fps 제한은 없다.
  이 차이도 의도한 것으로 유지한다.
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

## 코덱 순서

`VideoUplinkCodecPreference.ordered`가 영상 transceiver의 코덱 순서를 정한다. 서버는 offer 순서대로 처음
받아들인 코덱 하나로 업링크와 반환 영상을 함께 묶는다(innolive-server `internal/session/video_codec.go`).

| 세션 | 순서 | 업링크 인코딩·반환 디코딩 |
| --- | --- | --- |
| 서버 AI | H.264 Constrained Baseline, VP8, 나머지 | VideoToolbox 하드웨어 |
| 온디바이스(서버 송출·앱 직접 송출) | VP8, 나머지 | libvpx 소프트웨어 |

- Baseline 조건은 `profile-level-id`가 `42e0`로 시작하고 `packetization-mode=1`, level 4.0 이상이다.
  pion v4.1.2는 profile 두 바이트만 비교하고 level은 보지 않는다. LiveKitWebRTC 144는 Baseline level을
  VideoToolbox 조회값(기기표 fallback 최소 4.1)으로 광고하므로 Android처럼 `42e01f`로 고정하지 않는다.
  level 3.1은 1080p를 약 13fps로 묶어 VideoToolbox가 1080p30을 거부할 수 있으므로 VP8로 넘긴다.
- Constrained High(`640c..`)가 앞에 오면 서버가 answer를 만든 뒤 RTP를 버려 OnTrack이 열리지 않는다
  (2026-10-18 서버 로컬 재현). 그래서 libwebrtc 기본 순서를 그대로 쓰지 않는다.
- 코덱은 offer에서 정해진다. 연결 중 AI 처리 위치를 바꾸면 다음 PeerConnection 재생성부터 새 순서를 쓴다.
- 변경 배경은 client #643이다. VP8 우선일 때 FHD 서버 세션에서 libvpx 인코딩과 1080×1920 반환 VP8 디코딩이
  모두 CPU에서 돌았고, 송신이 4~12fps로 떨어진 세션이 있었다.

## 진단 로그 (DEBUG 빌드만)

Console에서 subsystem `com.framework.innolive`, category `uplink`로 본다. Release 빌드는 기존 로그만 남긴다.

- `uplink send policy`: 적용 직후 sender 파라미터를 다시 읽어 degradation, 최소·최대 비트레이트,
  fps, 축소 배율과 `setBweMinBitrateBps` 결과를 남긴다.
- `uplink video`: 2초 샘플마다 `qualityLimitationReason`, 송신 크기, fps, `targetBitrate`,
  `encoderImplementation`, `qualityLimitationResolutionChanges`를 남긴다.

Release 빌드에도 `uplink quality`는 남는다. 제한 사유나 해상도가 바뀔 때, 제한이 이어지면 30초마다 한 줄이다.

## FHD 송신 프레임률 저하 판별

같은 기기·장소에서 FHD 서버 AI 세션을 조건 하나씩만 바꿔 2분 이상 비교한다. fps가 떨어진 구간의 값으로 가른다.

| 관찰 | 원인 |
| --- | --- |
| `reason=cpu`, `encoder=libvpx` | VP8 소프트웨어 인코딩 CPU 적응. `maintainResolution`이라 해상도 대신 fps를 깎는다 |
| `reason=bandwidth`, `targetBitrate`가 FHD 하한 근처(iOS 3Mbps, Android 1Mbps) | 업로드 대역폭 |
| `reason=none`인데 fps 저하 | 인코더 앞단(캡처 콜백)에서 프레임 유실 |

- 렌즈 프리셋: 색 조정이 중립이 아니면 캡처 콜백에서 CoreImage로 BGRA를 새로 그린다. 중립 프리셋과 비교한다.
- 회전: 서버가 video-orientation 확장을 협상하지 않아 세로(`size=1080x1920`)는 libwebrtc가 매 프레임 I420
  변환과 회전을 CPU로 한다. 가로 잠금과 비교한다.
- go-live 로컬 녹화: 녹화는 go-live 직전에 시작한다. 같은 세션의 준비 구간과 go-live 직후를 비교한다.
- 코덱: H.264 빌드에서 `encoder=VideoToolbox`로 같은 조건을 다시 잰다. VP8에서만 `reason=cpu`면 코덱 원인이다.
- 720p로 되돌린 뒤에도 `reason=cpu`가 남으면 CPU 적응의 fps 복구 대기가 길어진 상태다.

## 검증 상태

- 시뮬레이터 XCTest: 정책 값, 직접 송출 제한과의 합성, 실제 LiveKitWebRTC sender가 offer/answer와
  해상도 변경 뒤에도 값을 유지하는지 확인했다.
- 실기기 미검증: 목표 해상도 도달 시간, 목표 비트레이트, CPU·발열, 온디바이스 AI fps, 셀룰러 초기 손실.
