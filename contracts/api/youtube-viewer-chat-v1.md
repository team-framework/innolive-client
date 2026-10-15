# YouTube viewer chat v1

- `targets[]` can include optional `video_id` for YouTube after preparation. CHZZK and unprepared targets omit the key. Older responses without the field remain valid.
- Source: server `internal/session/manager.go` at `c6276d9db8d519703e1ba0ae660a398e19e2ad79`, `StreamTargetState.VideoID` and snapshot assignment from `platformBroadcast.BroadcastID`. Server main provides the field; production deployment has not been checked.
- iOS shows the viewer-chat entry for a live YouTube target with a valid 11-character video ID. Paused live broadcasts retain chat. Missing/invalid IDs, stopped targets, removed YouTube targets, and cleared sessions hide and dismiss it.
- Chat loads `https://www.youtube.com/live_chat?is_popout=1&v=<video_id>` using URLComponents. A changed ID reloads the visible document. Dismissal stops loading and clears the WebView.
- The first version uses an ephemeral unauthenticated WKWebView. No InnoLive access token, owner token, or persistent browser cookies enter it. Login, links, forms, new windows, non-HTTPS and other-document navigations are blocked. Resource requests remain handled by WebKit.
- Private, age-restricted, disabled-chat, consent-gated, and region-dependent YouTube pages may not show messages. Live public/private broadcast display and real-device behavior remain manual verification items; tests do not assert live YouTube availability.
- Existing clients ignore `video_id`. This is an additive response-only change with no request or signaling changes.

Fixture: `fixtures/youtube-viewer-chat.json`.

## Manual acceptance

- Start a public YouTube broadcast, open Viewer chat, and confirm that messages appear without signing in.
- Pause and resume the broadcast; verify chat stays visible. Stop CHZZK in a simulcast session and verify YouTube chat remains available.
- Change broadcast resolution/mode so the server returns a new video ID; verify the open sheet loads that video's chat.
- End/remove YouTube or clear the session; verify the sheet closes. Retry a failed page load with the retry control.
- Verify private broadcasts and YouTube consent pages separately; the app intentionally does not authenticate the viewer WebView.
