import XCTest
import WebKit
@testable import InnoLive

@MainActor
final class YouTubeViewerChatTests: XCTestCase {
    private let videoID = "dQw4w9WgXcQ"

    private func target(videoID: String? = "dQw4w9WgXcQ", provider: String = "youtube", phase: String = "live", status: String = "streaming") throws -> BroadcastTargetState {
        var json: [String: Any] = ["provider": provider, "stream": ["status": status, "publisher_active": true, "reconnect_attempts": 0, "broadcast_phase": phase]]
        if let videoID { json["video_id"] = videoID }
        return try JSONDecoder().decode(BroadcastTargetState.self, from: JSONSerialization.data(withJSONObject: json))
    }

    func testOptionalVideoIDIsBackwardCompatible() throws {
        XCTAssertNil(try target(videoID: nil).videoID)
        XCTAssertEqual(try target().videoID, videoID)
    }

    func testUnsafeIDsCannotChangeURL() {
        for id in ["", " ", "../live_chat", "abcdefghij&", "abcdefghij?", "動画abcdefghij", "dQw4w9WgXcQ&v=other"] {
            XCTAssertNil(YouTubeViewerChatPolicy.url(videoID: id), id)
        }
        XCTAssertEqual(YouTubeViewerChatPolicy.url(videoID: videoID)?.absoluteString,
                       "https://www.youtube.com/live_chat?is_popout=1&v=dQw4w9WgXcQ")
    }

    func testChatOnlyAppearsForLiveYouTubeSession() throws {
        XCTAssertNotNil(YouTubeViewerChatPolicy.activeURL(targets: [try target()], hasSession: true))
        XCTAssertNotNil(YouTubeViewerChatPolicy.activeURL(targets: [try target(status: "paused")], hasSession: true))
        XCTAssertNil(YouTubeViewerChatPolicy.activeURL(targets: [try target()], hasSession: false))
        for value in [try target(videoID: nil), try target(provider: "chzzk"), try target(phase: "prepared"), try target(status: "stopped")] {
            XCTAssertNil(YouTubeViewerChatPolicy.activeURL(targets: [value], hasSession: true))
        }
        XCTAssertNil(YouTubeViewerChatPolicy.activeURL(targets: [], hasSession: true))
    }

    func testUnavailableChatHidesPendingVideoIDAndExplainsOtherStates() throws {
        XCTAssertEqual(YouTubeViewerChatPolicy.availability(targets: nil, hasSession: false), .noSession)
        XCTAssertEqual(YouTubeViewerChatPolicy.availability(targets: nil, hasSession: true), .missingVideoID)
        XCTAssertEqual(YouTubeViewerChatPolicy.availability(targets: [try target(provider: "chzzk")], hasSession: true), .noYouTubeTarget)
        XCTAssertEqual(YouTubeViewerChatPolicy.availability(targets: [try target(phase: "prepared")], hasSession: true), .notLive)
        for id: String? in [nil, "invalid"] {
            XCTAssertEqual(YouTubeViewerChatPolicy.availability(targets: [try target(videoID: id)], hasSession: true), .missingVideoID)
        }
        for state: YouTubeViewerChatPolicy.Availability in [.noSession, .noYouTubeTarget, .notLive] {
            XCTAssertFalse(try XCTUnwrap(state.message).isEmpty)
        }
        XCTAssertNil(YouTubeViewerChatPolicy.Availability.missingVideoID.message)
    }

    func testChatBecomesAvailableWhenVideoIDArrives() throws {
        XCTAssertEqual(YouTubeViewerChatPolicy.availability(targets: [try target(videoID: nil)], hasSession: true), .missingVideoID)
        let expected = try XCTUnwrap(YouTubeViewerChatPolicy.url(videoID: videoID))
        XCTAssertEqual(YouTubeViewerChatPolicy.availability(targets: [try target()], hasSession: true), .ready(expected))
        XCTAssertNil(YouTubeViewerChatPolicy.Availability.ready(expected).message)
    }

    func testChangedVideoSelectsNewDocument() throws {
        let before = YouTubeViewerChatPolicy.activeURL(targets: [try target()], hasSession: true)
        let after = YouTubeViewerChatPolicy.activeURL(targets: [try target(videoID: "abcdefghijk")], hasSession: true)
        XCTAssertNotEqual(before, after)
    }

    func testDirectLiveIdleStatusAndOtherTargetStopKeepChat() throws {
        let direct = try target(status: "idle")
        let stoppedCHZZK = try target(provider: "chzzk", status: "stopped")
        XCTAssertNotNil(YouTubeViewerChatPolicy.activeURL(targets: [stoppedCHZZK, direct], hasSession: true))
    }

    func testWebViewReloadsOnlyForNewVideoOrExplicitRetryAndClearsOnDismissal() throws {
        let initial = try XCTUnwrap(YouTubeViewerChatPolicy.url(videoID: videoID))
        let next = try XCTUnwrap(YouTubeViewerChatPolicy.url(videoID: "abcdefghijk"))
        let state = YouTubeViewerChatLoadState()
        let coordinator = YouTubeViewerChatWebView.Coordinator(document: initial, state: state)
        let webView = ChatRecordingWebView(frame: .zero, configuration: WKWebViewConfiguration())
        coordinator.load(initial, retry: 0, in: webView)
        coordinator.load(initial, retry: 0, in: webView)
        XCTAssertEqual(webView.requests.map(\.url), [initial])
        coordinator.load(next, retry: 0, in: webView)
        coordinator.load(next, retry: 1, in: webView)
        XCTAssertEqual(webView.requests.map(\.url), [initial, next, next])
        YouTubeViewerChatWebView.dismantleUIView(webView, coordinator: coordinator)
        XCTAssertEqual(webView.clearCount, 1)
        XCTAssertNil(webView.navigationDelegate)
        XCTAssertEqual(webView.stopCount, 4)
    }

    func testChatWebViewReportsDesktopSafariToPageJavaScript() async throws {
        let document = try XCTUnwrap(YouTubeViewerChatPolicy.url(videoID: videoID))
        let coordinator = YouTubeViewerChatWebView.Coordinator(document: document, state: YouTubeViewerChatLoadState())
        let webView = YouTubeViewerChatWebView.makeWebView(coordinator: coordinator)
        let reportedAgent = try await webView.evaluateJavaScript("navigator.userAgent")
        let agent = try XCTUnwrap(reportedAgent as? String)
        XCTAssertTrue(agent.contains("Macintosh"))
        XCTAssertTrue(agent.contains("Version/"))
        XCTAssertTrue(agent.contains("Safari/"))
        XCTAssertFalse(agent.contains("Mobile/"))
        XCTAssertFalse(webView.configuration.websiteDataStore.isPersistent)
        XCTAssertTrue(webView.navigationDelegate === coordinator)
    }

    func testNavigationCannotLeaveCurrentChatOrAuthenticate() throws {
        let document = try XCTUnwrap(YouTubeViewerChatPolicy.url(videoID: videoID))
        XCTAssertTrue(YouTubeViewerChatPolicy.allowsNavigation(to: document, document: document))
        for value in ["https://accounts.google.com/", "https://www.youtube.com/signin", "https://evil.example/live_chat?v=\(videoID)", "http://www.youtube.com/live_chat?v=\(videoID)", "https://www.youtube.com:444/live_chat?v=\(videoID)", "https://www.youtube.com/live_chat?v=abcdefghijk", "https://www.youtube.com/live_chat?v=\(videoID)&v=abcdefghijk", "youtube://\(videoID)"] {
            XCTAssertFalse(YouTubeViewerChatPolicy.allowsNavigation(to: URL(string: value), document: document), value)
        }
    }
}

@MainActor
private final class ChatRecordingWebView: WKWebView {
    var requests: [URLRequest] = []
    var stopCount = 0
    var clearCount = 0
    override func load(_ request: URLRequest) -> WKNavigation? {
        requests.append(request)
        return nil
    }
    override func stopLoading() { stopCount += 1 }
    override func loadHTMLString(_ string: String, baseURL: URL?) -> WKNavigation? {
        if string.isEmpty { clearCount += 1 }
        return nil
    }
}
