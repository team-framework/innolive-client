# Android 시청자 채팅

관련 이슈: client #639. iOS 시청자 채팅(#575)과 같은 보기 전용 YouTube 라이브 채팅이다.
HTTP·signaling 계약은 바꾸지 않으며 응답 규칙은
[youtube-viewer-chat-v1.md](../contracts/api/youtube-viewer-chat-v1.md)를 따른다.

## 표시 조건

- `SessionTarget.videoId`는 `targets[].video_id`에서 읽는다. 필드가 없는 응답과 한 대상의 `stream`만 오는 부분 응답은 이전 값을 유지한다.
- 채팅은 YouTube 대상의 `broadcast_phase`가 `live`이고 `status`가 `stopped`가 아니며 11자 동영상 ID가 유효할 때만 방송 화면에 뜬다. iOS `hasStartedBroadcast`와 같은 규칙이며 직접 송출의 `idle`, `paused_*`, 알 수 없는 상태도 포함한다. `YouTubeViewerChatPolicy.activeUrl`이 담당하며 `broadcastState()`는 쓰지 않는다.
- 방송 종료, YouTube 대상 제거, 세션 종료로 주소가 사라지면 오버레이와 수신 WebView를 함께 없앤다. 동영상 ID가 바뀌면 새 상태로 새 문서를 읽고 이전 메시지는 남기지 않는다.
- 직접 송출은 방송 종료·실패로 세션을 지우거나 서버에서 세션이 사라지면 `onSessionSnapshotCleared`로 화면의 마지막 세션 상태와 업로드 화질을 비운다. 상단 방송 상태 칩도 이 세션 상태로 대상 행을 그리므로 함께 사라진다. iOS `clearSessionResponseState`와 같으며, 비우지 않으면 종료 뒤에도 대상 상태·업로드 화질·채팅이 남는다.

## 화면

- iOS `YouTubeViewerChatOverlay`와 같다. 기본 페이지 왼쪽 아래, 실제 하단 조작 영역 높이 위 12dp에 둔다. 세로 화면은 왼쪽 16dp, 가로 화면은 왼쪽 카메라 도구 띠 오른쪽 8dp에서 시작한다.
- 최대 폭 280dp, 메시지 영역 높이 세로 136dp·가로 106dp, 그 아래 4dp 간격으로 44dp 원형 채팅 메뉴를 둔다.
- 메시지 행은 26dp 이니셜 원(하늘색 70%), 굵은 작성자와 줄바꿈한 본문(흰 글자, `bodySmall`), 검정 30% 바탕과 4dp 모서리, 4dp 행 간격이다. 최근 50개를 아래부터 쌓고 새 메시지로 따라간다.
- 메시지가 없으면 "채팅을 연결하고 있습니다."(진행 표시) 또는 "새 채팅을 기다리고 있습니다.", 실패하면 "채팅을 불러오지 못했습니다."와 다시 시도를 보여 준다.
- 채팅 메뉴(접근성 이름 "채팅 메뉴")는 카메라 도구와 같은 반투명 어두운 원이다. 누르면 "채팅 숨기기"·"채팅 표시" 한 항목을 띄운다. 보일 때는 채운 말풍선, 숨겼을 때는 테두리 말풍선이다.
- 숨기면 메시지 영역만 접고 수신은 계속한다. 숨김 상태는 방송이 이어지는 동안 기억하고 방송이 끝나면 다시 보이게 한다.
- 얼굴을 고르는 동안 오버레이를 가린다. 작은 미리보기 창과 세로 화면 카메라 도구는 iOS처럼 채팅 높이와 12dp 간격을 하단 예약 높이에 더해 피한다.

## 수신

- 문서는 `https://www.youtube.com/live_chat?is_popout=1&v=<video_id>`다.
- 수신 WebView는 넘기는 페이지 밖, 방송 화면 맨 아래 층에 360×480dp로 두고 같은 크기의 검은 판으로 덮는다. 투명도 0인 뷰는 그리기를 건너뛰어 WebView가 갱신되지 않을 수 있어서다. 위의 영상·조작 화면이 가리고 터치를 받지 않으며 초점·화면 낭독기 대상에서 뺀다. 페이지를 넘기거나 얼굴을 골라도 다시 읽지 않는다.
- iOS가 데스크톱 Safari 식별자를 쓰는 것처럼 데스크톱 Chrome 식별자(`X11; Linux x86_64`, 기기 WebView의 Chrome 버전)를 쓴다.
- `onPageFinished`에서 채팅 문서일 때 iOS `YouTubeChatBridge.script`와 같은 추출 스크립트를 넣는다. 일반 메시지·유료 메시지·멤버십·유료 스티커 행을 `MutationObserver`로 100ms 모아 `{url, messages, ready}` JSON으로 보낸다. 같은 문서에 두 번 넣지 않는다.
- 메시지는 `WebViewCompat.addWebMessageListener`(`viewerChatMessages`, 허용 출처 `https://www.youtube.com`, 메인 프레임만)로 받는다. 이 기능이 없는 WebView에서만 같은 이름의 `@JavascriptInterface`를 쓴다.
- 512,000바이트를 넘거나 형식이 맞지 않거나 지금 동영상 문서가 아닌 메시지는 버린다. iOS `visibleMessages`처럼 최근 50개에서 빈·중복 ID와 빈 본문을 빼고 ID 256자, 작성자 100자, 본문 2,000자(글자 단위)로 자른다.
- 문서를 다 받은 뒤 12초 안에 `yt-live-chat-renderer`가 나타나지 않으면 실패 안내를 보여 준다. 준비된 상태를 받으면 실패 표시를 거둔다.
- 기기 확인용 디버그 로그(태그 `YouTubeViewerChat`): `viewer_chat_bridge=web_message_listener|javascript_interface`, `viewer_chat_messages count=N ready=true|false`, `viewer_chat_readiness_timeout`, `viewer_chat_load_failed`. 작성자와 내용은 기록하지 않는다.

## WebView 정책

- 앱의 액세스 토큰과 세션 토큰은 WebView에 넣지 않는다. 메인 프레임에서 같은 동영상의 `live_chat`으로 가는 HTTPS 이동만 허용한다. 사용자 동작으로 시작한 링크 이동(`hasGesture`), 로그인·다른 문서·새 창·하위 프레임 이동은 막는다. 서드파티 쿠키는 끈다.
- `shouldOverrideUrlLoading`은 POST에서 불리지 않으므로 메인 프레임의 GET 외 요청(폼 제출)은 `shouldInterceptRequest`에서 내용 없는 204 응답으로 취소한다. 채팅 메시지를 받는 하위 리소스 POST는 막지 않는다. WebView가 204를 빈 문서로 표시하면 실패 안내로 바꾼다.
- Android WebView는 iOS의 비영속 데이터 저장소와 달리 앱 전체 쿠키·WebStorage를 공유한다. 수신 WebView가 사라질 때(방송 종료 등)와 로그아웃·계정 삭제 시 `YouTubeViewerChatWebData`가 YouTube 도메인 쿠키를 만료시키고 YouTube origin의 WebStorage를 지운다. YouTube는 방문자 쿠키(`YSC`, `VISITOR_INFO1_LIVE` 등)를 `Partitioned`로 내려 WebView가 `youtube.com` 파티션에 저장하므로 일반·`Partitioned` 만료 쿠키를 모두 쓴다. 조회에 보이지 않는 알려진 이름도 `.youtube.com`에서 만료한다. 모든 `setCookie` 결과를 받은 뒤 flush하고 남은 쿠키를 다시 조회해 `viewer_chat_cookies_cleared reason=<close|logout|account_deletion> requested=N failed=N remaining=N` 디버그 로그를 남긴다(값은 기록하지 않음). 수신기가 사라질 때는 WebView 파괴 뒤 1초 기다려 실행하고, 그때 바뀐 동영상의 WebView가 읽는 중이면 `viewer_chat_cookies_clear_skipped`를 남기고 그 WebView가 사라질 때 지운다. 치지직 OAuth 등 다른 WebView 쿠키는 남긴다. WebView는 메시지 통로와 클라이언트를 떼고 빈 문서를 읽은 뒤 파괴한다.
- 문서를 읽는 중 동의 화면 등으로의 리디렉션이 막히면 시간 초과를 기다리지 않고 바로 실패 안내를 보여 준다. 메인 프레임 오류·SSL 오류·렌더러 종료도 실패로 본다.
- 비공개·연령 제한·채팅 비활성·동의 화면·지역 제한 동영상은 메시지가 보이지 않을 수 있다.

## 검증

- 단위 테스트: `YouTubeChatMessagesTest`(iOS `YouTubeChatMessageTests`의 정리 규칙, 이모지 경계, JSON 파싱·크기 제한, 다른 문서 거부, 추출 스크립트 선택자, 데스크톱 식별자), `YouTubeViewerChatPolicyTest`(ID 검증, 대상 상태별 표시, 직접 송출 `idle`, 이동 허용·차단·실패 전환, 폼 제출 차단), `YouTubeViewerChatWebViewTest`(문서 완료 뒤 12초 준비 제한, 메시지 수신·지난 시도 무시, 정리 순서, 쿠키·WebStorage 삭제 범위, 파티션 쿠키 만료, flush·결과 집계), `SessionSnapshotTest`의 `video_id` 파싱·보존, `DirectBroadcastControllerTest`의 직접 송출 종료·서버 세션 소멸 시 세션 상태 비움.
- androidTest: `YouTubeViewerChatOverlayTest`(주소가 있을 때만 표시·새 동영상은 빈 상태, 메시지 행 표시와 메뉴로 숨기기·표시, 연결 중·대기·실패·다시 시도, 고정 문서로 실제 추출 스크립트와 `WebMessageListener` 수신·수정·삭제). 컴파일만 확인했고 기기 실행은 수동 항목이다.
- 실제 YouTube 공개·비공개 방송에서 오버레이 메시지 표시, 숨기기·표시 전환, 일시 중지·재개 중 유지, 해상도 변경으로 바뀐 동영상 ID 반영, 가로 화면 배치는 실기기 수동 검증 항목이다.
