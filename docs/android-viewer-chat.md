# Android 시청자 채팅

관련 이슈: client #639. iOS 시청자 채팅(#575)과 같은 보기 전용 YouTube 라이브 채팅이다.
HTTP·signaling 계약은 바꾸지 않으며 응답 규칙은
[youtube-viewer-chat-v1.md](../contracts/api/youtube-viewer-chat-v1.md)를 따른다.

## 표시 조건

- `SessionTarget.videoId`는 `targets[].video_id`에서 읽는다. 필드가 없는 응답과 한 대상의 `stream`만 오는 부분 응답은 이전 값을 유지한다.
- 방송 화면 오른쪽 조작 열의 채팅 버튼은 YouTube 대상의 `broadcast_phase`가 `live`이고 `status`가 `stopped`가 아니며 11자 동영상 ID가 유효할 때만 보인다. iOS `hasStartedBroadcast`와 같은 규칙이며 직접 송출의 `idle`, `paused_*`, 알 수 없는 상태도 포함한다. `YouTubeViewerChatPolicy.activeUrl`이 담당하며 `broadcastState()`는 쓰지 않는다.
- 방송 종료, YouTube 대상 제거, 세션 종료로 주소가 사라지면 `YouTubeViewerChatHost`가 열려 있던 채팅 창을 닫는다. 동영상 ID가 바뀌면 새 문서를 읽는다.

## WebView 정책

- 문서는 `https://www.youtube.com/live_chat?is_popout=1&v=<video_id>`다.
- 앱의 액세스 토큰과 세션 토큰은 WebView에 넣지 않는다. 메인 프레임에서 같은 동영상의 `live_chat`으로 가는 HTTPS 이동만 허용한다. 사용자 동작으로 시작한 링크 이동(`hasGesture`), 로그인·다른 문서·새 창·하위 프레임 이동은 막는다. 서드파티 쿠키는 끈다.
- `shouldOverrideUrlLoading`은 POST에서 불리지 않으므로 메인 프레임의 GET 외 요청(폼 제출)은 `shouldInterceptRequest`에서 내용 없는 204 응답으로 취소한다. 채팅 메시지를 받는 하위 리소스 POST는 막지 않는다. WebView가 204를 빈 문서로 표시하면 실패 화면으로 바꾼다.
- Android WebView는 iOS의 비영속 데이터 저장소와 달리 앱 전체 쿠키·WebStorage를 공유한다. 채팅 창을 닫을 때와 로그아웃·계정 삭제 시 `YouTubeViewerChatWebData`가 YouTube 도메인 쿠키를 만료시키고 YouTube origin의 WebStorage를 지운다. 치지직 OAuth 등 다른 WebView 쿠키는 남긴다. 닫을 때 WebView는 빈 문서를 읽은 뒤 파괴한다.
- 30초 안에 문서를 받지 못하거나 메인 프레임 오류·SSL 오류·렌더러 종료가 나면 실패 문구와 다시 시도 버튼을 보여 준다. 문서를 읽는 중 동의 화면 등으로의 리디렉션이 막히면 시간 초과를 기다리지 않고 바로 실패 화면을 보여 준다.
- 비공개·연령 제한·채팅 비활성·동의 화면·지역 제한 동영상은 메시지가 보이지 않을 수 있다. 창 안의 안내 문구로 알린다.

## 검증

- 단위 테스트: `YouTubeViewerChatPolicyTest` 8개(ID 검증, 대상 상태별 표시, 직접 송출 `idle`, 새 동영상 ID·종료·제거, 동시 송출, 이동 허용·차단·실패 전환, 폼 제출 차단), `YouTubeViewerChatWebViewTest` 5개(시간 초과·다시 시도, 지난 콜백 무시, 정리 순서, 쿠키·WebStorage 삭제 범위), `SessionSnapshotTest`의 `video_id` 파싱·보존 1개.
- androidTest: `LiveControlsLayoutTest`의 채팅 버튼 표시·위치, `YouTubeViewerChatHostTest`의 주소 변경·자동 닫힘. 기기 실행은 수동 항목이다.
- 실제 YouTube 공개·비공개 방송에서 메시지 표시, 일시 중지·재개 중 유지, 해상도 변경으로 바뀐 동영상 ID 반영은 실기기 수동 검증 항목이다.
