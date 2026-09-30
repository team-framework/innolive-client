# iOS 방송 오류·알림

대상은 iOS 서버 API 오류와 세션 피드백이다. 치지직 OAuth, 복수 플랫폼 선택,
플랜 구매, 방식 전환과 업그레이드 제안 기능 자체는 후속 작업이다.

서버 오류는 플랫폼·HTTP 상태·code·필드 정보를 유지한 뒤 안내 모달,
확인 모달, 인라인 오류, 로딩으로 나눈다. 계정 연결과 읽기 전용 플랜 정보로
이동할 수 있다. 플랜 정보 조회 실패 시 해당 화면에서 재시도한다.
기존 401 갱신·broadcast_not_ready 재시도를 유지한다.

서버 notices와 준비 warnings는 기존 일반 오류 배너와 분리한다. 배너를 닫아도
표시 기록은 유지한다. 공통 세션 모델의 세션 생성 후 2초 조회를 사용해 준비 단계의 경고와
서버 상태를 반영한다. 여러 대상이 응답에 있을 때 YouTube가 종료되어도 다른
대상이 활성 상태이면 조회를 유지한다.

## 검증 경로

- 집중 XCTest: BroadcastProblemTests, BroadcastProblemAPITests, BroadcastSessionStateTests,
  BroadcastSessionPollingTests, BroadcastFeedbackTests,
  YouTubeAccountAPITests, YouTubeBroadcastOrientationLifecycleTests,
  YouTubeBackgroundPauseTests, LocalizationTests.
- Debug 전용 `--broadcast-feedback-validation --feedback-case <scenario>`로 네트워크
  호출 없이 실제 피드백 컴포넌트를 표시한다. scenario는 notices, bad_request,
  channel_already_live, broadcast_busy, streaming_not_connected 등이다.
- iPhone 시뮬레이터에서 배너, 확인·취소 모달, 필드 오류, 로딩과 버튼 잠금의
  표시를 확인한다. 취소·동의 요청 및 닫기 후 반복 조회는 XCTest로 확인한다. 한국어·영어·일본어 문구를 번들에 포함한다.
- 실제 서버 한도 소진·YouTube 스튜디오 종료 감지·치지직 단독/동시 송출은
  fixture 검증과 구분하여 이후 해당 플랫폼 송출 QA에서 확인한다.

동작 계약은 [방송 피드백 계약](../contracts/api/broadcast-feedback-v1.md)을 따른다.

## 2026-09-30 검증 결과

- 공통 세션 모델 PR #359의 `63bb7be` 위에 오류·알림 변경을 통합했다.
- iPhone 16 / iOS 27 시뮬레이터 집중 XCTest: 89개 통과, 실패 0개.
  오류·알림 신규 테스트 24개, 공통 상태·조회 및 기존 회귀 테스트 65개를 포함한다.
- Debug 테스트 빌드와 Release 시뮬레이터 빌드가 통과했다. 서명 빌드·실기기 검증은 수행하지 않았다.
- 앱 설치·실행 후 한국어 알림 배너, 계속·취소 모달, 제목 인라인 오류,
  처리 중 표시와 준비 버튼 잠금 화면을 확인했다. UI 버튼을 직접 눌러 검증하지 않았으며,
  취소 후 요청 0회·동의 후 prepare 1회·닫기 후 알림 재등장 방지는 XCTest로 확인했다.
- 실제 서버 한도 소진·YouTube/치지직 송출·플랫폼 종료 감지는 검증하지 않았다.
  검증 화면은 Debug에서만 사용하며 네트워크 호출 없이 합성 응답을 사용한다.
- 계약 영향은 추가형이다. 서버 변경 없이 기존 오류·상태 필드를 읽으며,
  사용자 동의 후 기존 prepare 요청에 allow_concurrent=true를 보낸다.
