# YouTube viewer chat v1

- `targets[]` can include optional `video_id` for YouTube after preparation. CHZZK and unprepared targets omit the key. Older responses without the field remain valid.
- Source: server `internal/session/manager.go` at `c6276d9db8d519703e1ba0ae660a398e19e2ad79`, `StreamTargetState.VideoID` and snapshot assignment from `platformBroadcast.BroadcastID`. Server main provides the field; production deployment has not been checked.
- iOS renders viewer chat directly at the bottom-left of the broadcast preview, above the measured broadcast controls. There is no viewer-chat button or sheet. Before a YouTube broadcast starts, for unsupported targets, or while the video ID is missing/invalid, the panel explains availability. A valid live video ID automatically replaces the explanation with chat. Paused broadcasts retain chat; stopped/removed targets and cleared sessions remove the web content. Face selection temporarily hides the panel, and the local camera preview reserves space above it.
- Chat loads `https://www.youtube.com/live_chat?is_popout=1&v=<video_id>` using URLComponents. A changed ID replaces the receiver and clears the previous native messages. Removing the chat content stops loading and clears the WebView.
- An ephemeral unauthenticated WKWebView receives the YouTube chat document in desktop mode with a Safari user agent. The receiver is not visible or interactive. An isolated-world MutationObserver extracts message IDs, author names, text, and emoji alt text into native SwiftUI rows. No InnoLive access token, owner token, or persistent browser cookies enter it. Login, links, forms, new windows, non-HTTPS and other-document navigations are blocked. Resource requests remain handled by WebKit.
- Private, age-restricted, disabled-chat, consent-gated, and region-dependent YouTube pages may not show messages. Public/private broadcast display remains a manual verification item. Unlisted live reception was verified on the iPad below; fixture tests do not assert live YouTube availability.
- Existing clients ignore `video_id`. This is an additive response-only change with no request or signaling changes.

- The overlay shows individual translucent message rows, with a colored author name and wrapping body. It keeps the latest 50 messages and follows new arrivals. Empty/deleted entries are omitted; old-video callbacks cannot replace current messages. Text is rendered as text, never HTML. YouTube DOM selectors are an external dependency and need live checks after upstream changes.
- A loaded page that does not expose the chat renderer within 12 seconds shows a retry action instead of an indefinite spinner.

Fixture: `fixtures/youtube-viewer-chat.json`.

## Manual acceptance

- Confirm the chat panel appears at the bottom-left before broadcasting and no Viewer chat button remains in the controls.
- Start a public YouTube broadcast and verify messages appear automatically without opening a sheet.
- Pause/resume and confirm chat remains. End/remove YouTube and confirm the web content clears.
- Change video ID and confirm the panel reloads the new broadcast. Retry a failed load.
- Check iPhone 16/16 Pro sizes and iPad: chat must remain above broadcast controls and not cover the local camera preview.
- Verify private broadcasts and consent pages separately; the viewer WebView does not authenticate.

## Verification (2026-10-19)

- 10 chat policy/WebView tests and 2 message bridge tests passed on the simulator. The bridge test loads local HTML into a real WKWebView and checks body text, emoji, text updates, deletion, stale-video rejection, and cleanup. This fixture is not proof of live reception.
- Two rendering tests passed at 393×852pt (iPhone 16) and 402×874pt (16 Pro). Light/dark screenshots show separate native rows and long Korean text wrapping. Test messages are fixtures.
- Signed device build installed and launched on iPad Air 13-inch (M3), iPadOS 26.6. An unlisted YouTube broadcast received two real browser-posted messages as separate native rows: a short Korean message with an emoji and a long Korean message wrapping across four lines. A device UI test asserted both complete message bodies, ended the broadcast, and verified that all message rows cleared (1 test passed).
- Earlier WebView-only live checks exposed two real failures: YouTube rejected the default browser identity, then the small embedded page obscured message bodies. The Safari identity and native message rows address those separately.

- Live test preparation initially failed twice with `YouTube: Could not process the YouTube request.` After the user authorized deleting an existing PRISM broadcast, a new preparation and Go Live succeeded. The underlying error code was not captured, so the cause is not established.
