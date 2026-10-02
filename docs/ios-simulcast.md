# iOS 복수 대상 방송

## 범위와 선행 상태

- 이슈: [client#422](https://github.com/team-framework/innolive-client/issues/422), 담당자 천준범, P1
- 기준: PR #403의 병합 main `d032a343549e1a52c31acef34371ca35b136f68b`
- 대상: iOS의 플랫폼 선택·설정 UI, 기존 방송 API 소비, 방송 제어·앱 수명주기
- HTTP·WebRTC signaling 계약 변경 없음. 서버·다른 플랫폼·영상 처리 변경 없음
- PR #403의 실계정 검증 체크와 본문·Issue #391·문서의 미검증 기록 불일치 유지
- 사용자 추가 지시: 구현·자동 검증 후 Draft PR 직전에 실기기 검증이 필요하면 종료. 실계정 선행 검증·동시 송출 완료로 처리하지 않음

## 선택과 준비

계정 화면과 방송 설정 화면의 YouTube·치지직 스위치는 송출 대상 선택이다. 설정의
분할 선택기는 선택한 플랫폼 중 편집할 폼을 바꾼다. 두 폼의 초안·직전 값·저장은
기존 계정·채널별 편집기를 사용한다. 계정 연결이 필요한 대상은 새로 선택할 수 없고,
UI에서는 마지막 선택을 해제할 수 없다. 선택은 현재 앱 메모리에만 유지하며 초기화 시
YouTube 한 대상으로 돌아간다.

선택 대상의 계정·입력·동의·권한·요금제를 먼저 확인한다. 복수 선택의 요금제 검사는
아직 생성된 대상 수가 아니라 요청한 전체 대상 수로 판단한다. 거절되면 세션을 만들지
않는다. 하나의 세션과 WebRTC 업링크를 연결하고, 선택 대상별 직전 값을 조회한 후 각
대상에 설정 PUT → prepare POST 순서로 요청한다. 단독은 한 번, 동시는 대상마다
prepare를 호출한다. 설정 저장 실패 대상에는 prepare를 보내지 않는다.

준비 실패는 로컬의 대상별 `savingSettings`·`preparingStream` 실패로 기록한다.
서버의 `failed_targets`는 golive의 부분 실패이며 준비 실패로 사용하지 않는다. 한 대상
준비 실패 뒤에도 다른 대상 준비를 시도한다. 성공한 대상은 prepared로 유지하며 재시도는
아직 준비되지 않은 선택 대상만 처리한다. 중복 탭은 기존 준비 작업에 합쳐지고, 취소·초기화
이후 늦은 응답은 기존 세대·세션·계정 검사로 차단한다.

부분 실패 화면 상단에서 실패 대상·단계, 준비된 대상과 다음 선택을 표시한다.

- 다시 준비: 성공한 대상·세션·영상 연결을 유지하고 실패 대상만 설정 저장·prepare 재시도
- 준비된 플랫폼으로 계속: 준비된 대상이 있는 경우에만 허용, 바로 golive하지 않고 홈의 방송 시작 대기
- 준비 취소: 준비된 대상에 provider별 stop, WebRTC 정리 후 세션 DELETE와 로컬 미리보기 복귀

취소의 DELETE 실패 기록은 기존 Keychain 정리 경로로 보존한다. 사용자가 계속을
선택하기 전에는 부분 준비의 golive를 차단한다. 미리보기는 부분 실패에서도 서버 연결을
유지한다.

## 라이브 시작과 대상별 제어

사용자가 방송 시작을 누르면 provider 없이 golive를 **한 번** 요청한다. 서버가 준비된
전체 대상을 전환한다. `failed_targets`에 있는 대상만 라이브 시작 실패로 표시하며 성공
대상은 라이브·회전 잠금·방송 타이머를 유지한다. 실패 원문 메시지 대신 앱의 고정 안내를
사용한다. 부분 응답에 없는 시간·미디어·알림은 기존 snapshot reducer가 유지한다.

일부 대상이 prepared로 남았으면 홈의 ‘준비된 대상 다시 시작’으로 다시 golive할 수 있다.
서버가 이미 라이브인 대상을 다시 시작하지 않고 남은 prepared 대상만 전환한다. 전체
요청 실패도 이미 진행 중인 다른 방송의 회전 잠금을 해제하지 않는다.

방송 제어 메뉴는 전체 pause·resume·stop과 플랫폼별 제어를 제공한다. 각 대상의 현재
상태가 허용하는 요청만 보내고 `?provider=youtube|chzzk`를 전달한다. 한 요청 실패는
다른 대상의 제어를 중단하지 않는다. 대상별 제어 실패는 해당 플랫폼 이름으로 안내한다.
종료된 대상은 idle로 정규화하여 화면의 송출 대상 개수에서 제외한다.

대상 하나 또는 전체 방송의 stop은 **세션 DELETE가 아니다**. 다른 대상의 송출·회전
잠금·타이머, 세션 조회와 WebRTC는 유지한다. 마지막 활성 대상 종료 시 회전 잠금과
공개 방송 타이머를 해제한다. 준비 취소·명시적 연결 종료·기존 세션 정리에서만 DELETE한다.
방송 정보 수정은 기존 라이브 편집기를 그대로 사용하며 종료된 대상만 편집에서 제외한다.

## 백그라운드와 복귀

백그라운드 진입 시 live이고 pause 가능한 대상마다 요청한다. 실패해도 다른 대상의
pause를 계속 시도하고, 성공한 provider만 자동 중지 집합에 기록한다. 복귀는 진행 중
pause를 기다린 후 이 집합과 현재 resume 가능한 대상의 교집합에만 요청한다. 수동으로
중지했던 대상은 자동 재개하지 않는다. 대상 종료·수동 제어 성공은 그 대상의 자동 중지
기록만 제거한다. 일반 백그라운드 전환에서 세션을 삭제하지 않는다.

복귀할 때 아직 paused_reconnecting·paused_reconfiguring인 대상은 재개 가능 상태가
될 때까지 기존 폴링을 유지한다. 앱이 활성 상태일 때 조회로 paused가 확인되면 자동
중지 집합에 남은 대상만 재개한다. 실패한 자동 resume도 같은 세션의 다음 조회에서
재시도하며 수동으로 중지했던 다른 대상은 유지한다.

## 자동 검증 기록

2026-10-02, Xcode 26.6·iPhone 17 Pro·iOS 26.5 Simulator의 별도 작업 폴더 기준이다.

- 최종 집중 XCTest **205개 통과**, 실패·skip 0개. 테스트 앱과 시뮬레이터 앱 빌드 성공
- 준비·부분 실패·백그라운드·폴링·세션 정리·회전·계정·라이브 편집·요금제·번역 회귀 확인
- 재연결 복귀 누락·제어 실패의 타 대상 중단·수동 pause의 잘못된 자동 resume·서버 준비 확인 후 남은 실패 표시를 수정 전 실패/수정 후 통과로 확인
- 최종 bundle: `/tmp/innolive-simulcast-422-final-verified.xcresult`
- 새 복수 선택 밝은 화면·부분 실패 어두운 화면 렌더링과 상단 준비 완료·다시 준비·계속·취소 표시 확인
- 기기용 Debug 서명 빌드 성공, `codesign --verify --deep --strict` 통과
- 기기용 앱: `/tmp/innolive-simulcast-422-device-derived/Build/Products/Debug-iphoneos/InnoLive.app`
- 새 `Simulcast.xcstrings` 17개 사용 키와 ko/en/ja 번역 일치 확인, 기존 번역 파일 변경 없음
- `git diff --check` 통과, 기존 Swift actor 격리 및 AppIntents metadata 경고 유지

실행 명령은 아래와 같다. 최종 테스트는 나열한 집중 suite 17개에 `-only-testing:InnoLiveTests/<suite>`를 각각 지정했다.

```sh
xcodebuild test -project apps/ios/InnoLive/InnoLive.xcodeproj -scheme InnoLive \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -parallel-testing-enabled NO \
  -only-testing:InnoLiveTests/BroadcastPreparationFlowTests \
  -only-testing:InnoLiveTests/YouTubeBackgroundPauseTests \
  -only-testing:InnoLiveTests/BroadcastSessionPollingTests \
  SWIFT_EMIT_LOC_STRINGS=NO CODE_SIGNING_ALLOWED=NO

xcodebuild build -project apps/ios/InnoLive/InnoLive.xcodeproj -scheme InnoLive \
  -destination 'generic/platform=iOS' -allowProvisioningUpdates SWIFT_EMIT_LOC_STRINGS=NO
```

최종 suite: `BroadcastPreparationFlowTests`, `YouTubeBackgroundPauseTests`,
`YouTubeBroadcastOrientationLifecycleTests`, `BroadcastSessionStateTests`,
`BroadcastSessionPollingTests`, `BroadcastSessionLifecycleTests`, `CHZZKAccountTests`,
`CHZZKAuthorizationTests`, `BroadcastSettingsTests`, `LiveBroadcastEditingTests`,
`YouTubeBroadcastStatePolicyTests`, `YouTubeAccountAPITests`, `YouTubeIntegrationAccountTests`,
`PlanUsageTests`, `LocalizationTests`, `BroadcastFeedbackTests`, `YouTubeModelsTests`.
모의 API·SwiftUI 렌더링은 실계정 인증이나 실제 수신 영상·오디오를 입증하지 않는다.

## Draft PR 전 실기기 검증

최신 서명 앱으로 아래 순서대로 수행하고, 공급자 시청 화면과 시간 차감 기록을 남긴다.
계정 code·토큰·스트림 키·실제 API 주소는 검증 기록에 넣지 않는다.

1. 치지직 실계정 연결·재연결·앱 재실행과 YouTube 계정 유지 확인
2. YouTube 한 대상 선택 → prepare 한 번 → 사용자 시작 → 실제 영상·오디오 → pause·resume·stop 회귀
3. 치지직 한 대상 선택 → 동일한 단독 검증. 제목은 ingest 안정 8초 이후 반영되므로 즉시 실패로 판정하지 않음
4. 두 대상 선택 → 각 제목·설정 저장 → 두 prepare → 사용자 시작 → 두 시청 화면의 영상·오디오 확인
5. 준비의 한 대상 저장 실패·prepare 실패 → 다른 대상 준비 유지 → 재시도·계속·취소 각각 확인
6. golive의 한 대상 실패 → 실패한 플랫폼만 안내 → 성공 플랫폼 수신·회전 잠금·시간 유지
7. 한 대상 pause·resume·stop 및 네트워크 재연결 → 다른 플랫폼 실제 수신 유지와 idle 개수 제외 확인
8. 한 대상 수동 pause 후 홈 이탈·복귀 → 수동 대상 유지·자동 pause 대상만 resume 확인
9. 한 대상 재연결 중 홈 복귀 → 재개 가능한 상태 후 자동 resume 및 다른 플랫폼 유지 확인
10. 두 플랫폼 방송 정보 수정 → 각 시청 화면 반영·다른 방송 유지 확인
11. 단독·동시 시작/종료 전후 `/users/me/usage` 기록 → 서버가 제공한 해당 방식 배수와 실제 차감 비교
12. 전체 방송 stop 후 세션 조회 유지, 준비 취소에서만 DELETE 확인

실제 영상·오디오·시간 차감 및 위 장애 상황은 아직 미검증이다. 현재 실계정 시청 화면의 검증 결과가 없으며, 이 검증 전에는 Push·Draft PR 생성과 실제 동시 송출 완료 표시를 보류한다.

## 변경 파일

- `YouTubeIntegration.swift`: 복수 준비·실패·계속, provider 제어·자동 복귀 대상 기록
- `BroadcastPlatformSelectionView.swift`, `BroadcastSettingsView.swift`: 복수 선택·대상별 편집·부분 실패 선택
- `HomeView.swift`, `BroadcastControllsView.swift`, `BroadcastControlViews.swift`: 준비 연결·대상 제어·라이브 실패 표시
- `Resources/Simulcast.xcstrings`: 새 UI·안내의 한국어·영어·일본어 번역
- `BroadcastPreparationFlowTests.swift`, `BroadcastSessionPollingTests.swift`, `YouTubeBackgroundPauseTests.swift`: 요청·상태·폴링·화면 회귀
- `docs/ios-simulcast.md`, `docs/ios-broadcast-settings.md`, `docs/ios-connection-feedback.md`: 동작·검증·제한 기록
- `YouTubeAPI.swift`: 기존 provider·golive payload가 요구 계약을 이미 지원하여 변경 없음

## 참고

- [방송 세션 상태 계약](../contracts/api/broadcast-session-state-v1.md)
- [플랫폼별 설정 계약](../contracts/api/broadcast-settings-v1.md)
- [방송 준비 연결](ios-connection-feedback.md)
- 서버 `internal/server/server.go`: prepare는 대상별, golive는 모든 prepared 대상에 단일 요청
- 서버 `internal/server/chzzk_deferred_settings.go`: ingest가 안정적으로 8초 유지된 후 제목·카테고리·태그 적용
