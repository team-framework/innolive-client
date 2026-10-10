# iOS 방송 준비 안내와 송출 상태 패널

작업 이슈: [#466](https://github.com/team-framework/innolive-client/issues/466). 대상은 iOS 홈·방송 설정 시트·설정 화면이다. 서버 API, HTTP 필드, WebRTC signaling, 다른 플랫폼 구현은 바꾸지 않는다.

## 방송 준비 안내

처음 쓰는 사용자가 실제 화면을 누르며 방송 준비를 마칠 수 있게 안내한다. 말풍선은 누를 요소를 가리키고, 사용자가 그 요소를 실제로 눌러 상태가 바뀌면 다음 단계로 넘어간다.

| 단계 | 화면 | 가리키는 요소 | 다음으로 넘어가는 조건 |
| --- | --- | --- | --- |
| 방송 준비부터 시작해요 | 홈 | `방송 준비` 버튼 | 방송 설정 시트 열림 |
| 방송할 계정을 연결해요 | 시트 | `플랫폼 계정 연결` | 선택한 플랫폼 계정이 모두 연결됨 |
| 제목을 확인하고 준비해요 | 시트 | 하단 `방송 준비` 버튼 | 준비 시작 |
| 방송을 준비하고 있어요 | 시트 | 준비 진행 영역 | `prepared` 확인 후 시트 닫힘 |
| 방송할 준비가 끝났어요 | 홈 | 초록색 `방송 시작` 버튼 | `알겠어요` 또는 라이브 시작 |

- 선택한 플랫폼 계정이 이미 연결돼 있으면 계정 단계를 건너뛰고 전체 4단계로 표시한다. 단계 수는 첫 단계에서 정하고, 연결을 마친 뒤에도 바꾸지 않는다.
- 준비가 실패하면 같은 자리에서 `준비가 잘 되지 않았어요`를 표시한다. 홈에서는 `다시 시도` 버튼, 시트에서는 준비 진행 영역을 가리킨다.
- 마지막 단계는 `방송 시작`을 누르라고 강요하지 않는다. 라이브 공개와 방송 시간 차감은 사용자가 정한다.

### 단계 판단

`BroadcastTutorialPolicy`는 기록이 아니라 현재 앱 상태로 단계를 정한다. 입력은 시트 표시 여부, 선택한 계정 연결 여부, `preparationStatus`, 서버 `broadcast_phase`, 라이브 시작 여부다. 사용자가 시트를 닫거나 준비를 취소하면 첫 단계로 돌아가고, `going_live`나 라이브가 시작되면 안내를 끝내고 완료로 기록한다.

`BroadcastControllsView`가 시트 상태를 알기 때문에 이 화면이 상태를 모아 `BroadcastTutorialCoordinator`에 전달한다. 홈은 카메라 권한 응답이 끝나고 권한 알림이 없을 때 0.6초 뒤 안내를 시작한다. 진행 중인 세션이 있으면 자동으로 시작하지 않는다.

### 화면 구성

- 홈: 화면을 45% 검은 막으로 덮고 가리키는 버튼 자리만 뚫는다. 뚫린 버튼과 말풍선만 누를 수 있다. VoiceOver가 켜져 있으면 터치를 막지 않는다.
- 방송 설정 시트: 입력을 막지 않도록 막 없이 강조색 테두리와 말풍선만 표시한다. 시트는 홈과 다른 화면 계층이라 오버레이를 따로 붙인다.
- 말풍선은 기존 `innoLiveGlassBackground` 위에 94% 불투명 바탕을 깔아 뒤 화면이 비치지 않게 한다. 가리키는 요소가 화면 아래쪽이면 위에, 위쪽이면 아래에 놓는다. 가리킬 요소가 화면 밖이면 하단 안전 영역 위에 놓는다.
- 단계가 바뀌면 제목과 설명을 VoiceOver로 읽는다. 동작 줄이기가 켜져 있으면 전환 애니메이션을 쓰지 않는다.

### 기록과 다시 보기

완료·건너뛰기는 기기 단위 UserDefaults에 저장한다. 키는 `com.framework.innolive.tutorial.broadcast-preparation.v1`이다. 설정 화면의 `방송 준비 안내 다시 보기`는 기록을 지우고 홈으로 돌아가 지금 상태에 맞는 단계부터 보여 준다. 보통은 첫 단계이고, 세션 없이 준비가 실패한 상태라면 다시 시도 단계다. 방송 세션이 있으면 이 메뉴를 비활성화한다.

## 송출 상태 패널

방송 중 하단 컨트롤 위에 Liquid Glass 카드 하나로 상태를 모은다. 기존의 플랫폼별 캡션 줄과 해상도·남은 시간 줄을 이 카드로 바꿨다. 업그레이드 제안, 방식 변경, 방송 정보 수정, 화질 전환·실패 안내는 그대로 둔다.

- 플랫폼 줄: 점 색과 짧은 상태로 표시한다. 송출 중은 빨강, 시작 대기는 초록, 재연결·일시 중지는 주황, 준비·화질 전환·종료는 회색이다.
- 정보 칩: 방송 화질(`1080p`), 앱이 실제로 올리는 업로드 해상도·FPS(`720p · 30fps`), 남은 방송 시간을 표시한다. 칩이 한 줄에 들어가지 않으면 세로로 쌓는다.
- 업로드 경고: WebRTC `qualityLimitationReason`이 `bandwidth`면 인터넷, `cpu`면 기기 부하 경고를 표시한다. 연결 직후 대역폭 추정 때문에 경고가 깜빡이지 않도록 2초 간격 표본에서 3번 연속 제한될 때 표시하고, 2번 연속 정상일 때 지운다. 경고가 나타나면 VoiceOver로 읽는다.
- 서버 응답에 `targets`가 없으면 단일 `stream` 상태를 YouTube 한 줄로 표시한다.

업로드 품질은 기존 진단 루프가 2초마다 읽는 통계를 재사용한다. 업링크를 정리하면 측정 중 상태로 되돌린다.

처음 라이브가 시작되면 패널을 가리키는 말풍선을 한 번 보여 준다. 방송 중에는 종료·일시 중지를 바로 눌러야 하므로 화면을 어둡게 하거나 터치를 막지 않는다. 키는 `com.framework.innolive.tutorial.live-status.v1`이고, 방송이 끝날 때까지 닫지 않으면 다음 라이브에서 다시 보여 준다.

## 문구

새 문구 33개는 `BroadcastGuide.xcstrings`에 한국어·영어·일본어로 넣었다. 남은 방송 시간 문구는 기존 `Localizable.xcstrings` 키를 그대로 쓴다. 영어는 `Prepare Broadcast`, `Go Live`, 일본어는 `配信を準備`, `配信開始`처럼 기존 버튼 번역과 맞춘다.

## 자동 검증 범위

- `BroadcastTutorialTests`: 단계 판단, 실패·취소·라이브 시작 처리, 단계 번호, 자동 시작 조건, 건너뛰기·완료 기록, 다시 보기, 상태 패널 안내 1회 표시를 검증한다.
- `BroadcastLiveStatusTests`: 업로드 품질 표기, 경고 표시·해제 표본 수, 짧은 제한 무시, 진단 표본 반환, 플랫폼 상태 색 분류, `targets` 없는 응답을 검증한다.
- `BroadcastGuideRenderingTests`: 홈 안내, 시트 계정 안내, 상태 패널, 상태 패널 안내를 밝은 화면·어두운 화면·큰 글씨로 그려 xcresult에 PNG로 남긴다.
- `LocalizationTests`: `BroadcastGuide` 표의 세 언어 키가 같고 영어·일본어 값에 한글이 없는지 확인한다.

자동 테스트는 모의 상태를 쓰며 실제 계정 연결, 송출, 네트워크 제한 경고를 증명하지 않는다.

## 실기기 확인 항목

- 앱을 새로 설치한 뒤 계정 미연결 상태에서 5단계 안내를 따라 준비 완료까지 진행
- 계정 연결 상태에서 4단계로 시작하고 계정 단계를 건너뜀
- 시트 닫기·준비 취소·준비 실패 때 알맞은 단계로 돌아감
- 건너뛰기 후 앱 재실행 시 안내 미표시, 설정의 다시 보기로 첫 단계부터 표시
- 첫 라이브 시작 때 상태 패널 안내 1회 표시, 방송 중 컨트롤 조작이 막히지 않음
- 셀룰러 또는 Network Link Conditioner로 대역폭을 낮췄을 때 업로드 경고 표시와 해제
- VoiceOver에서 단계 안내 읽기와 버튼 조작

## 변경 파일

- `apps/ios/InnoLive/InnoLive/Features/Home/Tutorial/BroadcastTutorialPolicy.swift`
- `apps/ios/InnoLive/InnoLive/Features/Home/Tutorial/BroadcastTutorialCoordinator.swift`
- `apps/ios/InnoLive/InnoLive/Features/Home/Tutorial/BroadcastTutorialOverlay.swift`
- `apps/ios/InnoLive/InnoLive/Features/Home/LiveStatus/BroadcastLiveStatusPanel.swift`
- `apps/ios/InnoLive/InnoLive/Features/Home/LiveStatus/BroadcastTargetStatus.swift`
- `apps/ios/InnoLive/InnoLive/Features/Home/LiveStatus/BroadcastUplinkQuality.swift`
- `apps/ios/InnoLive/InnoLive/Features/Home/BroadcastControlViews.swift`
- `apps/ios/InnoLive/InnoLive/Features/Home/BroadcastControllsView.swift`
- `apps/ios/InnoLive/InnoLive/Features/Home/HomeView.swift`
- `apps/ios/InnoLive/InnoLive/Features/Home/WebRTC/WebRTCVideoUplink.swift`
- `apps/ios/InnoLive/InnoLive/Features/Home/WebRTC/WebRTCVideoUplink+Diagnostics.swift`
- `apps/ios/InnoLive/InnoLive/Features/Settings/BroadcastSettingsView.swift`
- `apps/ios/InnoLive/InnoLive/Features/Settings/SettingsView.swift`
- `apps/ios/InnoLive/InnoLive/Resources/BroadcastGuide.xcstrings`
- `apps/ios/InnoLive/InnoLiveTests/BroadcastTutorialTests.swift`
- `apps/ios/InnoLive/InnoLiveTests/BroadcastLiveStatusTests.swift`
- `apps/ios/InnoLive/InnoLiveTests/BroadcastGuideRenderingTests.swift`
- `apps/ios/InnoLive/InnoLiveTests/LocalizationTests.swift`
- `docs/ios-broadcast-guide.md`
