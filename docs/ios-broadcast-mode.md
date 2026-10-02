# iOS 방송 중 방식 전환

대상은 `apps/ios`의 방송 중 해상도·송출 대상 변경과 방송 정보 수정 간 요청 순서다. 서버 API 의미와 signaling 필드를 변경하지 않는다. [공유 계약](../contracts/api/broadcast-mode-v1.md)의 기존 PUT 및 세션 상태를 사용한다.

## 동작

- 라이브 상태에서 현재 서버 해상도와 실제 라이브 대상으로 선택값을 초기화한다. 해상도는 `720p`·`fhd`, 대상은 최소 한 개다.
- 새 대상의 계정 연결과 설정 검증을 확인한다. 해당 플랫폼 설정 저장 성공 후 방식 전환을 요청한다. 실패하면 입력을 유지한다.
- 해상도 변경에는 방송 재시작, 유튜브 링크 변경, 치지직 방송 공백을 안내한다. 같은 해상도의 대상 변경에서는 유지 대상이 계속 송출함을 안내한다.
- 방식 전환 요청 시작부터 방송 정보 수정 저장을 잠근다. 정보 저장 중에는 방식 전환을 시작하지 않는다. 진행 중 서버 상태에도 같은 제한을 적용한다.
- `202` 후 기존 세션 폴링을 이어간다. `done`의 부분 실패와 `failed`·`canceled`를 구분하며 최종 `targets[]`의 상태·링크를 표시한다.
- 서버가 확인한 `broadcast_resolution`을 카메라 입력 해상도와 동기화한다. 단말 입력 변경 실패는 서버 송출 성공과 구분해 표시한다. 저장한 선호 해상도만으로 서버 적용을 판단하지 않는다.
- 세션·계정 변경 후 늦은 응답을 무시한다. 전환 중 일시적인 대상 종료가 기존 WebRTC 세션 종료나 방향 잠금 해제로 이어지지 않도록 한다.
- 전환 뒤 최신 세션 스냅샷을 적용하고 실제 라이브 대상의 방송 정보 수정 버튼을 복구한다.

## 구현과 검증 상태

[이슈 #426](https://github.com/team-framework/innolive-client/issues/426)의 iOS 구현·자동 검증 결과다. 서버 계약 변경 없이 기존 API를 소비한다.

- [x] iOS focused build 및 관련 테스트의 자동 테스트·코드 확인
- [x] 동일 구성 `200`, 신규 전환 `202` 및 폴링 완료의 자동 테스트·코드 확인
- [x] 같은 해상도 대상 추가·제거, 유지 대상 송출 상태의 자동 테스트·코드 확인
- [ ] 실기기 해상도 변경 후 최신 링크·수신 해상도·카메라 입력 동기화
- [x] 부분 실패·전체 실패·취소·HTTP 거절·조회 실패의 자동 테스트·코드 확인
- [x] 방송 정보 수정과 방식 전환의 양방향 잠금의 자동 테스트·코드 확인
- [x] 계정·세션 변경과 늦은 응답 무시의 자동 테스트·코드 확인
- [ ] 실제 계정·실기기 방송과 일시정지 유지

## 2026-10-02 검증 기록

- Xcode 27.0·iPhone 16 Simulator·iOS 27.0에서 집중 XCTest **120개 통과**, 실패 0개. 신규 방식 전환 테스트 13개·화면 렌더링 테스트 1개와 기존 회귀 106개 포함.
- 집중 suite: BroadcastModeTests, BroadcastModeRenderingTests, LiveBroadcastEditingTests, BroadcastPreparationFlowTests, BroadcastSessionPollingTests, YouTubeBackgroundPauseTests, YouTubeBroadcastOrientationLifecycleTests, BroadcastSettingsTests, PlanUsageTests.
- 설정 저장 실패에서 PUT 미호출, 설정 선저장, 전환 요청 직후 PATCH 차단·정보 저장 중 PUT 차단, 202 진행 잠금·부분 실패 복구, 응답 유실 뒤 조회까지 잠금 유지, 로그아웃 뒤 늦은 응답 차단, idle 전환 구간의 방향 잠금·시작 시각 유지, 전환 중 stop 확인.
- 밝은 화면과 accessibility3 큰 글씨·어두운 화면을 모의 세션으로 렌더링하고 PNG 2개 확인. 한국어·영어·일본어 24개 신규 문구와 가상 JSON fixture 3개 파싱 확인.
- 시뮬레이터 테스트 빌드 및 기기용 Debug 서명 빌드 성공. 생성 앱의 codesign --verify --deep --strict 통과. 기기에 설치하거나 실제 방송을 실행하지 않음.
- 결과 bundle: `/tmp/innolive-ios-mode-426-final.xcresult`. 기존 Swift actor 관련 경고 및 GoogleSignIn 리소스 로딩 경고는 남아 있음. 테스트 실패는 없음.

서버 소스 계약 확인은 완료했다. 운영 배포, 실계정 송출, 실기기 동작은 아직 검증하지 않았다. 모의 상태·요청 테스트와 사용자 확인 모달의 코드 검토를 실제 송출 성공으로 기록하지 않는다.

## 실기기 확인 경로

1. YouTube·치지직 계정을 연결하고 단독 송출에서 방식 변경 화면 진입.
2. FHD 선택 후 확인 취소 시 요청·수신 해상도 변경 없음 확인. 동의 후 새로운 YouTube 링크 또는 치지직 같은 링크, 실제 영상·오디오·1920×1080 입력 확인.
3. 같은 해상도에서 추가 대상 설정 저장 후 전환, 기존 시청 화면 수신 유지 확인. 저장 실패 시 전환 요청 없음과 초안 유지 확인.
4. 두 대상 중 하나 제거, 일시정지 상태 유지, 전체 실패·부분 실패 후 실제 대상 표시와 편집 가능 상태 확인.
5. 전환 중 정보 저장 차단과 정보 저장 중 전환 차단, 전환 중 방송 종료, 네트워크 응답 유실·복구 확인.

## 변경 파일

- 상태·API: YouTubeIntegration.swift, YouTubeAPI.swift.
- 화면: BroadcastModeView.swift, BroadcastControlViews.swift, BroadcastSettingsView.swift, CameraAudioSettingsView.swift, AISettingsView.swift.
- 번역·테스트: BroadcastMode.xcstrings, BroadcastModeTests.swift, BroadcastModeRenderingTests.swift.
- 계약·문서: broadcast-mode-v1.md, 가상 fixture 3개, contracts/README.md, 이 문서.
