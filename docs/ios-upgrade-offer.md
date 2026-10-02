# iOS 업그레이드 제안

작업 이슈: [#434](https://github.com/team-framework/innolive-client/issues/434). 대상은 iOS UI·공통 세션 응답 모델·기존 서버 API 연결이다. 카메라 처리·HTTP 필드·WebRTC signaling 및 다른 플랫폼 구현 변경은 없다. 선행 설정·defaults는 #367, 방송 방식 전환은 #427을 사용한다.

## 사용자 흐름

방송 화면에 제안 진입 버튼을 표시한다. 상세 화면은 해상도·플랫폼·현재와 전환 후 차감 배수·전환 후 남은 시간·서버 만료 시각을 표시한다. 긴 옵션과 큰 글씨는 스크롤할 수 있다. 상세 화면을 닫아도 서버 제안을 거절하거나 시간을 연장하지 않는다.

재시작 옵션은 서버 `restart_effects`의 링크 유지 여부와 제공된 송출 공백을 표시한다. 확인 취소 시 요청은 없다. 재시작 없는 옵션은 바로 선택한다. 설정 불필요 옵션은 기존 broadcast-mode로 전환한다.

설정 필요 옵션은 select 응답의 selected와 expires_at을 먼저 적용하고 기존 추가 플랫폼 폼을 연다. 서버가 연장한 보류 시간을 설정 화면에도 표시한다. 설정 저장 성공 후 전환하고, 저장 실패 때는 폼·제목·카테고리·태그·검증 오류를 유지해 다시 저장할 수 있다. 선택을 복원하거나 설정 화면을 다시 열 때 select를 반복하지 않는다. 복원된 재시작 선택은 다시 확인을 받아야 저장·전환할 수 있다.

## 상태와 오류

- 서버 전체 스냅샷에서 제안이 없어지거나 만료 시간이 지나면 제안 상세와 그 제안의 설정 화면을 닫는다. 폴링 실패 중에도 마지막으로 확인한 서버 expires_at으로 만료한다.
- select 처리 중에는 기존 만료 예약을 취소하고 응답의 새 expires_at으로 예약한다. 응답 유실 시 전체 세션을 다시 조회해 서버 보류 상태를 확인한다.
- 수락·거절은 제안 전체를 닫는다. `upgrade_offer_not_found`는 제안만 닫고 세션·송출·영상 연결을 유지한다.
- 설정 저장 응답보다 제안 소멸·만료가 먼저 확인되면 broadcast-mode를 보내지 않는다. 소비하거나 만료한 제안은 오래된 응답으로 다시 표시하지 않는다.
- 선택·거절은 중복 요청과 방송 정보 수정·방식 전환·일시정지·계정 연결 변경을 잠근다. 방송 종료는 유지한다. 계정·세션 변경 뒤 도착한 응답은 반영하지 않는다.
- 추가 설정을 한 번 저장한 뒤 전환할 때 같은 저장 요청을 중복 전송하지 않는다. 기존 일반 방식 전환 흐름은 그대로 유지한다.

계약·호환성·가상 응답: [upgrade-offer-v1](../contracts/api/upgrade-offer-v1.md), `contracts/fixtures/upgrade-offer-*.v1.json`. 기존 서버가 재시작 없는 옵션에 주는 `restart_effects: null`도 빈 목록으로 파싱한다.

## 자동 검증 범위

UpgradeOfferTests는 만료·폴링 없이 만료·서버 선행 소멸·부분 응답 보존·재시작 확인 취소·즉시 전환·select 보류 연장·거절·404 제안 한정 처리·저장 실패 초안 유지·재시도·저장 중 만료·select 응답 유실 조회·늦은 응답·인증과 요청 payload를 검증한다. UpgradeOfferRenderingTests는 옵션과 설정 화면의 밝은 화면·어두운 화면·accessibility3 큰 글씨·스크롤 하단을 가상 데이터로 렌더링한다. 기존 방송 방식 전환·설정·세션·방향 잠금·요금제 suite도 함께 실행한다.

자동 테스트는 모의 API를 사용하며 실제 플랫폼 송출 성공을 증명하지 않는다.

## 2026-10-03 검증 기록

- Xcode 26.6·iPhone 17 Pro Simulator·iOS 26.5 집중 XCTest 182개 통과, 실패·스킵 0개. 신규 업그레이드 동작 25개·렌더링 1개와 기존 회귀 156개 포함.
- 결과 bundle: `/tmp/innolive-upgrade-434-final.xcresult`. 재시작 확인의 선택 데이터를 alert의 presenting 값으로 고정한 뒤 업그레이드 동작·렌더링 26개 재검증 통과, 실패·스킵 0개. 재검증 bundle: `/tmp/innolive-upgrade-434-ui-final.xcresult`.
- `xcodebuild build -project apps/ios/InnoLive/InnoLive.xcodeproj -scheme InnoLive -destination 'generic/platform=iOS' -derivedDataPath /tmp/innolive-upgrade-434-device CODE_SIGNING_ALLOWED=NO` 기기용 Debug 빌드 통과. 서명·설치·실기기 실행은 수행하지 않음. 최종 로그: `/tmp/innolive-upgrade-434-device-final.log`.
- `go test ./internal/session ./internal/server -run 'Test.*Upgrade' -count=1` 서버의 두 패키지 집중 테스트 통과. 서버 소스 변경 없음.
- 옵션·추가 설정 실패 화면의 밝은 화면, accessibility3 큰 글씨·어두운 화면, 스크롤 하단 PNG 6개 검토. 저장 실패 뒤 제목 초안·오류 안내·활성화된 저장 후 전환 버튼 확인. 화면은 모의 연결 계정과 HTTP 400 오류를 사용함.
- 한국어·영어·일본어 신규 문구 21개, 기존 공통 문구 453개, 가상 fixture 3개 JSON·format placeholder 검사 통과. `git diff --check` 통과.
- 기존 Swift actor 격리 경고와 테스트 URLProtocol의 Sendable 경고가 남아 있음. 이 검증에서 테스트 실패와 빌드 오류는 없음.

## 변경 파일

- `apps/ios/InnoLive/InnoLive/Features/Home/BroadcastControlViews.swift`
- `apps/ios/InnoLive/InnoLive/Features/Home/UpgradeOfferView.swift`
- `apps/ios/InnoLive/InnoLive/Features/Settings/BroadcastSettingsView.swift`
- `apps/ios/InnoLive/InnoLive/Features/YouTube/YouTubeAPI.swift`
- `apps/ios/InnoLive/InnoLive/Features/YouTube/YouTubeIntegration.swift`
- `apps/ios/InnoLive/InnoLive/Features/YouTube/YouTubeModels.swift`
- `apps/ios/InnoLive/InnoLive/Features/YouTube/UpgradeOfferPresentation.swift`
- `apps/ios/InnoLive/InnoLive/Resources/UpgradeOffer.xcstrings`
- `apps/ios/InnoLive/InnoLiveTests/UpgradeOfferTests.swift`
- `apps/ios/InnoLive/InnoLiveTests/UpgradeOfferRenderingTests.swift`
- `contracts/README.md`
- `contracts/api/upgrade-offer-v1.md`
- `contracts/fixtures/upgrade-offer-options.v1.json`
- `contracts/fixtures/upgrade-offer-selected.v1.json`
- `contracts/fixtures/upgrade-offer-not-found.v1.json`
- `docs/ios-broadcast-session-state.md`
- `docs/ios-upgrade-offer.md`

## 구현 참고

- [Apple TimelineView](https://developer.apple.com/documentation/SwiftUI/TimelineView): 서버 만료 시각의 초 단위 표시
- [Apple alert presenting](https://developer.apple.com/documentation/swiftui/view/alert%28_%3Aispresented%3Apresenting%3Aactions%3Amessage%3A%29-8584l): 재시작 확인 선택 데이터 유지

## 마지막 실기기 검증

- [ ] 실제 제안의 해상도·플랫폼·차감 배수·남은 시간·만료 시각 확인
- [ ] 만료 및 서버 선행 소멸 때 상세·설정 화면 닫힘과 기존 송출 유지 확인
- [ ] 재시작 확인 취소 시 API 요청과 송출 변화 없음 확인
- [ ] 즉시 전환 후 기존 방식 전환 진행·완료와 실제 영상·음성 확인
- [ ] 추가 플랫폼 select 후 약 3분 보류·설정 저장·실제 동시 송출 확인
- [ ] 설정 저장 실패·네트워크 복구 뒤 초안 유지와 재시도 성공 확인
- [ ] YouTube 새 링크 및 CHZZK 동일 링크·서버가 안내한 공백 확인
- [ ] 거절·수락·upgrade_offer_not_found 후 제안 전체 닫힘과 세션 유지 확인

CHZZK 일반 방식 전환 실계정 검증은 이슈 #426에서도 별도 추적한다. 이 기능의 실기기·실제 제안·송출 검증은 #434에 기록하고 완료 전에는 Draft PR을 유지한다.
