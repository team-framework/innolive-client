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
- [x] YouTube 실기기 해상도 변경 후 최신 링크·수신 해상도·카메라 입력 동기화
- [x] 부분 실패·전체 실패·취소·HTTP 거절·조회 실패의 자동 테스트·코드 확인
- [x] 방송 정보 수정과 방식 전환의 양방향 잠금의 자동 테스트·코드 확인
- [x] 계정·세션 변경과 늦은 응답 무시의 자동 테스트·코드 확인
- [x] YouTube 실제 계정·실기기 방송과 일시정지 유지

## 2026-10-02 검증 기록

- Xcode 27.0·iPhone 16 Simulator·iOS 27.0에서 집중 XCTest **120개 통과**, 실패 0개. 신규 방식 전환 테스트 13개·화면 렌더링 테스트 1개와 기존 회귀 106개 포함.
- 집중 suite: BroadcastModeTests, BroadcastModeRenderingTests, LiveBroadcastEditingTests, BroadcastPreparationFlowTests, BroadcastSessionPollingTests, YouTubeBackgroundPauseTests, YouTubeBroadcastOrientationLifecycleTests, BroadcastSettingsTests, PlanUsageTests.
- 설정 저장 실패에서 PUT 미호출, 설정 선저장, 전환 요청 직후 PATCH 차단·정보 저장 중 PUT 차단, 202 진행 잠금·부분 실패 복구, 응답 유실 뒤 조회까지 잠금 유지, 로그아웃 뒤 늦은 응답 차단, idle 전환 구간의 방향 잠금·시작 시각 유지, 전환 중 stop 확인.
- 밝은 화면과 accessibility3 큰 글씨·어두운 화면을 모의 세션으로 렌더링하고 PNG 2개 확인. 한국어·영어·일본어 24개 신규 문구와 가상 JSON fixture 3개 파싱 확인.
- 시뮬레이터 테스트 빌드 및 기기용 Debug 서명 빌드 성공. 생성 앱의 codesign --verify --deep --strict 통과.
- 결과 bundle: `/tmp/innolive-ios-mode-426-final.xcresult`. 기존 Swift actor 관련 경고 및 GoogleSignIn 리소스 로딩 경고는 남아 있음. 테스트 실패는 없음.

## 2026-10-02~03 실기기 검증 기록

- iPhone 16·iOS 27.0에 서명 앱을 설치하고 실행했다. 기존 로그인·Plasma 요금제 조회 및 YouTube 비공개 설정을 확인했다. 사용자 전송 허용 후 실제 계정에서 방송 준비·시작 요청을 실행했다.
- 앱 화면에서 해상도 변경 확인 취소 후 720p 유지, 720p→FHD 전환 진행·완료, 전환 중 수정 진입 차단·완료 후 재활성화, 방송 정보 저장 완료 안내를 확인했다. FHD 적용 후 카메라 설정은 1080p·30fps로 표시됐다.
- 전체 일시정지 후 FHD→720p 전환을 실행했고, 완료 후 일시정지·경과 시각을 유지했다. 전체 방송 종료와 준비 취소 후 홈 대기 화면을 확인했다.
- 실기기 집중 XCTest 120개에서 119개 통과·1개 파일 경로 오류가 발생했다. 계약 fixture를 테스트 번들에 포함하고 Mac 경로 조회를 교체했다. 실패 1개 및 같은 조회를 사용하는 추가 2개를 재실행해 3개 모두 통과했다. 자동 테스트는 mock API를 사용하며 플랫폼 송출 성공을 증명하지 않는다.
- 결과 bundle: `/tmp/innolive-ios-mode-426-physical-tests-results.xcresult`, `/tmp/innolive-ios-mode-426-physical-fixture-retest.xcresult`.
- 원격 기기 화면에서 원본 프리뷰와 서버 수신 영상이 검게 보였다. 전면 카메라 선택 후에도 같았으며, 같은 원격 화면에서 기본 Camera 앱의 전면·후면 영상도 검게 보였다. 본체 영상·외부 시청 화면 확인이 필요하다.
- 임시 DEBUG 진단에서 카메라 권한 허용, 전면 장치 탐색·선택, 입력 1개·캡처 세션 실행을 확인했다. 준비 중 WebRTC 첫 프레임은 1920×1080이었다. 송신 통계는 초기 대역폭 제한 270×480 뒤 제한 없음·720×1280·30fps로 바뀌었다. 실제 송신 프레임을 확인했지만 외부 영상 내용·수신 품질은 확인하지 못했다. 진단 코드를 제거하고 기존 서명 앱을 다시 설치했다.

위 원격 조작 당시에는 실제 세션 상태 변화와 앱 화면만 확인했다. 이후 직접 조작 검증과 외부 수신 확인 결과는 다음 기록에 구분한다.

## 2026-10-03 최종 실기기 확인

- 최신 main `2353763`을 PR 브랜치에 merge한 `f858bba`의 기기용 Debug 빌드·서명 검증을 통과하고 iPhone 16에 설치·실행했다.
- 사용자가 직접 조작해 영상·음성, 방송 제목 수정, 720p·FHD 전환 및 일시정지 유지가 정상 동작함을 확인하고 실기기 테스트 완료를 알렸다. 전환 해상도 불일치로 제기한 현상도 이후 정상 동작으로 확인했다.
- 외부 YouTube 수신에서 720×1280·30fps와 새 방송의 1080×1920·30fps를 확인했다. 해상도 변경 시 이전 방송 종료와 새 시청 링크 생성을 확인했다. 최종 제공 링크 `DgaYj4ySHTc`는 1080×1920·30fps를 수신했다.
- CHZZK 계정의 실제 대상 추가·제거 및 두 플랫폼 동시 송출은 실계정 검증을 수행하지 않았다. 자동 테스트 결과와 구분하며 이슈 #426에서 남은 범위를 추적한다.

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
