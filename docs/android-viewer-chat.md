# Android 시청자 채팅

관련 이슈: client #639. iOS 시청자 채팅(#575)과 같은 보기 전용 YouTube 라이브 채팅이다.
HTTP·signaling 계약은 바꾸지 않으며 응답 규칙은
[youtube-viewer-chat-v1.md](../contracts/api/youtube-viewer-chat-v1.md)를 따른다.

## 표시 조건

- `SessionTarget.videoId`는 `targets[].video_id`에서 읽는다. 필드가 없는 응답과 한 대상의 `stream`만 오는 부분 응답은 이전 값을 유지한다.
- 방송 화면 오른쪽 조작 열의 채팅 버튼은 YouTube 대상이 라이브 또는 일시 중지이고 11자 동영상 ID가 유효할 때만 보인다. `YouTubeViewerChatPolicy.activeUrl`이 이 규칙을 담당한다.
- 방송 종료, YouTube 대상 제거, 세션 종료로 주소가 사라지면 열려 있던 채팅 창을 닫는다. 동영상 ID가 바뀌면 새 문서를 읽는다.

## WebView 정책

- 문서는 `https://www.youtube.com/live_chat?is_popout=1&v=<video_id>`다.
- 앱의 액세스 토큰과 세션 토큰은 WebView에 넣지 않는다. 메인 프레임에서 같은 동영상의 `live_chat`으로 가는 HTTPS 이동만 허용하고 링크·폼·로그인·다른 문서·새 창·하위 프레임 이동은 막는다. 서드파티 쿠키는 끈다.
- Android WebView는 iOS의 비영속 데이터 저장소와 달리 앱 전체 쿠키 저장소를 공유한다. 이 창은 쿠키를 추가로 쓰지 않으며 닫을 때 빈 문서를 읽고 WebView를 파괴한다.
- 30초 안에 문서를 받지 못하거나 메인 프레임 오류·SSL 오류·렌더러 종료가 나면 실패 문구와 다시 시도 버튼을 보여 준다.
- 비공개·연령 제한·채팅 비활성·동의 화면·지역 제한 동영상은 메시지가 보이지 않을 수 있다. 창 안의 안내 문구로 알린다.

## 검증

- 단위 테스트: `YouTubeViewerChatPolicyTest` 4개(ID 검증, 대상 상태별 표시, 동시 송출, 이동 허용 규칙), `SessionSnapshotTest`의 `video_id` 파싱·보존 1개.
- 실제 YouTube 공개·비공개 방송에서 메시지 표시, 일시 중지·재개 중 유지, 해상도 변경으로 바뀐 동영상 ID 반영은 실기기 수동 검증 항목이다.
