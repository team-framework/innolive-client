# Android 방송 준비 안내와 송출 상태 패널

작업 이슈: [#468](https://github.com/team-framework/innolive-client/issues/468). iOS 구현([#466](https://github.com/team-framework/innolive-client/issues/466), `docs/ios-broadcast-guide.md`)과 같은 동작을 Android 화면 구조에 맞춰 옮겼다. 서버 API, HTTP 필드, WebRTC signaling은 바꾸지 않는다.

## iOS와 다른 점

- 방송 설정은 시트가 아니라 `Dialog`다. Dialog 창은 내용 크기에 맞춰지므로 말풍선을 띄우지 않고, 안내 카드를 Dialog 맨 위에 고정하고 가리키는 요소에 강조 테두리를 그린다. 가리키는 요소가 스크롤 밖이면 화면 안으로 스크롤한다.
- 플랫폼을 고르지 않았다면 방송 준비 버튼이 플랫폼 선택 Dialog를 연다. 이 화면은 `방송할 플랫폼을 골라요` 단계로 안내하고, 단계 번호는 첫 단계와 같은 1로 센다.
- 방송 준비를 누르면 Dialog가 바로 닫히고 준비 진행은 홈 버튼(`방송 준비 중`)에 표시된다. 그래서 준비 대기 단계는 보통 홈에서 주 버튼을 가리킨다.
- 마지막 단계는 홈의 `방송 준비 완료` 버튼을 가리킨다. 이 버튼을 누르면 열리는 제어 창에서 `방송 시작`을 고른다.
- 남은 방송 시간과 방송 경과 시간은 기존처럼 우측 상단에 둔다. 상태 패널에는 다시 넣지 않는다.

## 방송 준비 안내

| 단계 | 화면 | 가리키는 요소 | 다음으로 넘어가는 조건 |
| --- | --- | --- | --- |
| 방송 준비부터 시작해요 | 홈 | 주 버튼 | 플랫폼 선택 또는 방송 설정 Dialog 열림 |
| 방송할 플랫폼을 골라요 | 플랫폼 Dialog | 플랫폼 목록 | 플랫폼 선택 |
| 방송할 계정을 연결해요 | 설정 Dialog | 계정 정보 영역(YouTube), 연결 버튼 줄(치지직) | 선택한 플랫폼 계정 연결 |
| 제목을 확인하고 준비해요 | 설정 Dialog | 하단 `방송 준비` 버튼 | 준비 시작 |
| 방송을 준비하고 있어요 | 홈 | 주 버튼 | `PREPARED` |
| 방송할 준비가 끝났어요 | 홈 | 주 버튼 | `알겠어요` 또는 라이브 시작 |

- 단계는 `BroadcastTutorialPolicy`가 현재 상태로 정한다. 입력은 열린 Dialog, 선택한 플랫폼 계정 연결 여부, `BroadcastState`, `isPreparingBroadcast`, 라이브 시작 여부다.
- 계정 연결 여부는 YouTube는 `hasYouTubeAccount && !isYouTubeReconnectRequired`, 치지직은 `ChzzkAccountVerification.canPrepare`다. 단계 수는 첫 단계에서 정하고 연결 후에도 바꾸지 않는다.
- `FAILED`이면 `준비가 잘 되지 않았어요`를 보여 준다. 홈에서는 주 버튼, 설정 Dialog에서는 `방송 준비` 버튼을 가리킨다.
- `GOING_LIVE`, `LIVE`, `PAUSED` 등 라이브 이후 상태가 되면 안내를 끝내고 완료로 기록한다. 준비 취소 중에는 첫 단계로 돌아간다.

### 화면 구성

- 홈: 화면을 45% 검은 막으로 덮고 가리키는 버튼 자리만 뚫는다. 막은 뚫린 자리를 뺀 네 영역으로 나눠 터치를 막으므로 강조된 버튼과 말풍선만 누를 수 있다. TalkBack의 접근성 동작은 막지 않는다.
- 말풍선은 실제 높이를 재서 가리키는 요소 위나 아래 중 들어가는 쪽에 놓는다. 가로 화면·큰 글꼴처럼 어느 쪽에도 들어가지 않으면 넓은 쪽에 놓고 설명만 스크롤하며, 건너뛰기·알겠어요 버튼은 항상 보인다. 단계가 바뀌면 제목과 설명을 TalkBack이 읽는다(`liveRegion`).
- Dialog 안내 카드는 Dialog 높이의 40%로 제한하고 설명을 스크롤해 큰 글꼴에서도 폼을 밀어내지 않는다.
- 카메라·마이크 권한 안내가 떠 있으면 안내를 띄우지 않는다. 권한이 모두 있으면 0.6초 뒤 자동 시작한다.

### 기록과 다시 보기

완료·건너뛰기는 기기 단위 SharedPreferences `broadcast_tutorial`의 `broadcast_preparation_v1`에 저장한다. 로그아웃·회원 탈퇴 때 지우지 않는다. 설정의 `방송 준비 안내 다시 보기`는 기록을 지우고 방송 화면으로 돌아가 지금 상태에 맞는 단계부터 보여 준다. 세션이 있거나 방송 상태가 `IDLE`이 아니면 비활성화한다.

## 송출 상태 패널

세션의 `visibleTargets`가 있으면 홈 하단 주 버튼 위에 반투명 카드를 표시한다.

- 플랫폼 줄: 점 색과 짧은 상태. 송출 중은 빨강, 시작 대기는 초록, 재연결·일시 중지는 주황, 준비·화질 전환·종료는 회색이다.
- 정보 칩: 방송 화질(`1080p`), 앱이 실제로 올리는 업로드 해상도·FPS(`720p · 30fps`). 칩이 한 줄을 넘으면 줄바꿈한다.
- 업로드 경고: `qualityLimitationReason`이 `bandwidth`면 인터넷, `cpu`면 기기 부하 경고를 표시한다. 2초 간격 표본에서 3번 연속 제한될 때 표시하고 2번 연속 정상일 때 지운다.

업로드 통계는 `WebRtcConnection`이 연결 완료 후 2초마다 영상 sender의 `outbound-rtp`를 읽어 `onUplinkVideoStats`로 전달한다. `WebRtcSessionViewModel.uplinkQuality`는 새 연결과 `close()`에서 측정 중 상태로 돌아간다.

처음 라이브가 시작되면 패널을 가리키는 말풍선을 한 번 보여 준다. 화면을 어둡게 하거나 터치를 막지 않는다. 키는 `live_status_v1`이고, 방송이 끝날 때까지 닫지 않으면 다음 라이브에서 다시 보여 준다.

## 문구

`res/values*/broadcast_guide.xml`에 한국어·영어·일본어 문구 35개를 추가했다. `LocalizationCatalogTest`가 세 언어의 키와 서식 인자가 같은지 검사한다.

## 자동 검증 범위

- `BroadcastTutorialTest`(JVM): 단계 판단, 실패·취소·라이브 처리, 단계 번호, 자동 시작 조건, 건너뛰기·완료 기록, 다시 보기, 상태 패널 안내 1회 표시
- `BroadcastLiveStatusTest`(JVM): 업로드 품질 표기, 경고 표시·해제 표본 수, 통계 파싱, 플랫폼 상태 색·문구
- `CalloutPlacementTest`(JVM): 말풍선 위·아래 배치, 공간 부족 시 넓은 쪽 선택과 최소 높이 확보
- `BroadcastGuideUiTest`(기기): 홈 막의 터치 차단과 강조 버튼 통과, 건너뛰기, 마지막 단계 완료, 상태 패널 안내가 조작을 막지 않음, 설정 Dialog 안내 표시, 가로 화면·글꼴 200%에서 닫기 버튼 표시와 동작. 화면을 앱 외부 파일 폴더 `broadcast-guide/`에 PNG로 남긴다.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.framework.innolive.feature.live.tutorial.BroadcastGuideUiTest
```

자동 테스트는 모의 상태를 쓰며 실제 계정 연결, 송출, 네트워크 제한 경고를 증명하지 않는다.

## 실기기 확인 항목

- 새로 설치 후 계정 미연결 상태에서 5단계 안내를 따라 준비 완료까지 진행
- 계정 연결 상태에서 4단계로 시작하고 계정 단계를 건너뜀
- Dialog 닫기·준비 취소·준비 실패 때 알맞은 단계로 복귀
- 건너뛰기 후 재실행 시 안내 미표시, 설정의 다시 보기로 다시 표시
- 첫 라이브 시작 때 상태 패널 안내 1회 표시, 방송 중 조작이 막히지 않음
- 대역폭을 낮췄을 때 업로드 경고 표시와 해제
- TalkBack에서 단계 안내 읽기와 버튼 조작
