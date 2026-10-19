import XCTest
import WebKit
@testable import InnoLive

@MainActor
final class YouTubeChatMessageTests: XCTestCase {
    func testSnapshotKeepsRepeatedTextFromDifferentMessagesAndBoundsInput() {
        let messages = [
            YouTubeChatMessage(id: "1", author: "viewer", text: "hello"),
            YouTubeChatMessage(id: "2", author: "viewer", text: "hello"),
            YouTubeChatMessage(id: "2", author: "viewer", text: "duplicate"),
            YouTubeChatMessage(id: "3", author: "viewer", text: "  \n "),
            YouTubeChatMessage(id: "4", author: String(repeating: "a", count: 200), text: String(repeating: "b", count: 3_000))
        ]
        let snapshot = YouTubeChatSnapshot(url: "", messages: messages, ready: true)
        XCTAssertEqual(snapshot.visibleMessages.map(\.id), ["1", "2", "4"])
        XCTAssertEqual(snapshot.visibleMessages.last?.author.count, 100)
        XCTAssertEqual(snapshot.visibleMessages.last?.text.count, 2_000)
        let many = (0..<100).map { YouTubeChatMessage(id: String($0), author: "viewer", text: "hello") }
        XCTAssertEqual(YouTubeChatSnapshot(url: "", messages: many, ready: true).visibleMessages.count, 50)
    }

    func testDOMBridgeExtractsBodyEmojiUpdatesAndDeletions() async throws {
        let url = try XCTUnwrap(URL(string: "about:blank"))
        let state = YouTubeViewerChatLoadState()
        let coordinator = YouTubeViewerChatWebView.Coordinator(document: url, state: state)
        let configuration = WKWebViewConfiguration()
        let webView = YouTubeViewerChatWebView.makeWebView(coordinator: coordinator, configuration: configuration)
        // Render local HTML through the real production script bridge, without a network request.
        webView.navigationDelegate = nil
        webView.frame = CGRect(x: 0, y: 0, width: 340, height: 240)
        let window = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.windows.first)
        window.addSubview(webView)
        defer { webView.removeFromSuperview() }
        webView.loadHTMLString(Self.fixtureHTML, baseURL: nil)
        try await waitUntil { state.messages.count == 1 }
        XCTAssertTrue(state.chatReady)
        XCTAssertEqual(state.messages.first?.author, "viewer")
        XCTAssertEqual(state.messages.first?.text, "안녕하세요 😊 긴 댓글입니다")

        _ = try await webView.evaluateJavaScript("""
        document.querySelector('#message').textContent = '수정된 댓글';
        """)
        try await waitUntil { state.messages.first?.text == "수정된 댓글" }
        _ = try await webView.evaluateJavaScript("document.querySelector('yt-live-chat-text-message-renderer').setAttribute('is-deleted', '')")
        try await waitUntil { state.messages.isEmpty }
        _ = try await webView.evaluateJavaScript("document.querySelector('yt-live-chat-text-message-renderer').removeAttribute('is-deleted')")
        try await waitUntil { state.messages.count == 1 }
        coordinator.document = URL(string: "chat-fixture://test/another-video")!
        _ = try await webView.evaluateJavaScript("document.querySelector('#message').textContent = 'stale message'")
        try await Task.sleep(for: .milliseconds(250))
        XCTAssertEqual(state.messages.first?.text, "수정된 댓글", "Old video callbacks must not replace current messages")
        YouTubeViewerChatWebView.dismantleUIView(webView, coordinator: coordinator)
        XCTAssertFalse(coordinator.isActive)
    }

    private func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<300 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTFail("Timed out waiting for chat DOM update")
        throw NSError(domain: "ChatBridgeTest", code: 1)
    }
    private static let fixtureHTML = """
    <html><body><yt-live-chat-renderer>
    <yt-live-chat-text-message-renderer id="message-1">
    <span id="author-name">viewer</span>
    <span id="message">안녕하세요 <img alt="😊"> 긴 댓글입니다</span>
    </yt-live-chat-text-message-renderer>
    </yt-live-chat-renderer></body></html>
    """
}
