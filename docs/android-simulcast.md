# Android 복수 대상 방송

## 범위

- 기준: main `4222de1d`, iOS 구현 [client#422](https://github.com/team-framework/innolive-client/issues/422)과 `docs/ios-simulcast.md`
- 대상: 서버 중계(WebRTC) 방송의 YouTube·치지직 동시 송출. 직접 송출은 기존 구성 화면의 복수 대상을 그대로 쓰고 설정 전달만 공유
- HTTP·WebRTC signaling 계약 변경 없음. 서버·다른 플랫폼 변경 없음

## 선택과 준비

플랫폼 선택에 `YouTube + CHZZK`를 추가했다. 선택은 기존 `innolive_broadcast_provider`의
`selected`에 `SIMULCAST`로 저장한다. 세션은 YouTube를 기본 대상으로 만든다.

방송 준비는 YouTube 설정 → `다음: 치지직 설정` → 치지직 설정 순서로 연다. 치지직 화면의
방송 준비에서 하나의 세션과 WebRTC 업링크를 연결하고 대상마다 설정 PUT → prepare POST를
보낸다. 설정 저장에 실패한 대상에는 prepare를 보내지 않는다.

- 한 대상이 실패해도 다른 대상의 준비를 계속한다.
- 일부만 준비되면 `PREPARED`를 유지하고 실패한 플랫폼을 안내한다. 사용자는 준비된 플랫폼으로 시작하거나 준비를 취소한다.
- 일부 준비 상태에서 설정을 다시 확인해 준비하면 준비된 대상은 건너뛰고 남은 대상만 요청한다.
- YouTube의 `channel_already_live`는 기존 동시 방송 확인 후 같은 방식으로 남은 대상만 재요청한다.

## 라이브와 제어

- golive는 provider 없이 한 번 요청한다. `failed_targets`의 플랫폼만 안내하고 성공한 방송은 유지한다.
- 방송 상태는 대상 상태를 합친다. 하나라도 라이브면 라이브, 모두 멈췄을 때만 일시 중지다.
- pause·resume·stop은 현재 상태가 허용하는 대상마다 요청한다. 기본 대상은 기존 경로, 다른 대상은 `?provider=`를 붙인다.
- 한 대상의 제어 실패가 다른 대상의 요청을 막지 않는다. 대상별 응답은 해당 대상의 상태만 갱신한다.
- 전체 stop은 세션 DELETE가 아니다.

## iOS와의 차이

- 플랫폼별 개별 제어 메뉴와 부분 실패 화면의 `다시 준비`·`계속` 선택지는 아직 없다.
- Android에는 백그라운드 자동 일시 중지가 없어 대상별 자동 재개 기록도 없다.

## 자동 검증 기록

2026-10-13, Pixel_10 에뮬레이터(Android 17) 기준이다.

- `testDebugUnitTest` 전체 통과. 대상 합산 상태·대상별 부분 응답 테스트 추가
- `BroadcastApiFlowTest` 32개 통과. 동시 준비·제어, 일부 준비 실패 후 남은 대상 재요청, golive·pause 부분 실패 테스트 추가
- `BroadcastPreparationUiTest` 5개, `LiveBroadcastTransportSeamTest` 2개, `BroadcastGuideUiTest` 9개, `ChzzkLocalizationUiTest` 3개, `SessionRecoveryApiTest` 10개 통과
- `lintDebug`의 오류는 main과 같은 기존 항목만 남음

모의 API 테스트는 실계정 인증이나 실제 수신 영상·오디오를 입증하지 않는다. 실기기에서 두 시청 화면의
영상·오디오, 부분 실패, 대상별 상태, 시간 차감을 별도로 확인한다.
