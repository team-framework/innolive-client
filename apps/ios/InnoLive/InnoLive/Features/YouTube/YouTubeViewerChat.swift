import Combine
import SwiftUI
import WebKit

/// A chat document is scoped to one live YouTube video, never to an account token.
enum YouTubeViewerChatPolicy {
    static func url(videoID: String?) -> URL? {
        guard let videoID, videoID.count == 11,
              videoID.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0)
                  || (48...57).contains($0) || $0 == 45 || $0 == 95 }) else { return nil }
        var components = URLComponents()
        components.scheme = "https"
        components.host = "www.youtube.com"
        components.path = "/live_chat"
        components.queryItems = [URLQueryItem(name: "is_popout", value: "1"), URLQueryItem(name: "v", value: videoID)]
        return components.url
    }

    static func activeURL(targets: [BroadcastTargetState]?, hasSession: Bool) -> URL? {
        guard case let .ready(url) = availability(targets: targets, hasSession: hasSession) else { return nil }
        return url
    }

    enum Availability: Equatable {
        case ready(URL)
        case noSession
        case noYouTubeTarget
        case notLive
        case missingVideoID

        var message: String? {
            switch self {
            case .ready: return nil
            case .noSession, .notLive:
                return String(localized: "YouTube 방송을 시작하면 시청자 채팅을 볼 수 있습니다.", table: "ViewerChat")
            case .noYouTubeTarget:
                return String(localized: "시청자 채팅은 YouTube 방송에서 지원합니다.", table: "ViewerChat")
            case .missingVideoID:
                return nil
            }
        }
    }

    static func availability(targets: [BroadcastTargetState]?, hasSession: Bool) -> Availability {
        guard hasSession else { return .noSession }
        guard let targets else { return .missingVideoID }
        guard let target = targets.first(where: { $0.provider == "youtube" }) else { return .noYouTubeTarget }
        guard YouTubeBroadcastStatePolicy(stream: target.stream, isChangingStreamState: false).hasStartedBroadcast
        else { return .notLive }
        guard let url = url(videoID: target.videoID) else { return .missingVideoID }
        return .ready(url)
    }

    static func allowsNavigation(to candidate: URL?, document: URL) -> Bool {
        guard let candidate, let parts = URLComponents(url: candidate, resolvingAgainstBaseURL: false),
              parts.scheme == "https", parts.host == "www.youtube.com", parts.port == nil,
              parts.user == nil, parts.password == nil, parts.path == "/live_chat",
              let expected = URLComponents(url: document, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "v" })?.value
        else { return false }
        let ids = parts.queryItems?.filter { $0.name == "v" } ?? []
        return ids.count == 1 && ids.first?.value == expected
    }
}

struct YouTubeViewerChatOverlay: View {
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var isChatHidden = false
    let availability: YouTubeViewerChatPolicy.Availability

    var body: some View {
        Group {
            if case let .ready(url) = availability {
                VStack(alignment: .leading, spacing: 4) {
                    YouTubeViewerChatContent(url: url, isChatHidden: isChatHidden)
                        .id(url)
                        .frame(height: verticalSizeClass == .compact ? 106 : 136)
                        .opacity(isChatHidden ? 0 : 1)
                        .frame(height: isChatHidden ? 0 : nil)
                        .clipped()
                        .allowsHitTesting(!isChatHidden)
                        .accessibilityHidden(isChatHidden)
                    Menu {
                        Button {
                            isChatHidden.toggle()
                        } label: {
                            Label(String(localized: isChatHidden ? "채팅 표시" : "채팅 숨기기", table: "ViewerChat"),
                                  systemImage: isChatHidden ? "bubble.left" : "bubble.left.fill")
                        }
                    } label: {
                        Image(systemName: isChatHidden ? "bubble.left" : "bubble.left.fill")
                            .font(.body)
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                            .innoLiveGlassSurface(in: Circle(), interactive: true, overVideo: true)
                    }
                    .accessibilityLabel(String(localized: "채팅 메뉴", table: "ViewerChat"))
                    .accessibilityIdentifier("youtube-chat-menu")
                }
            } else if let message = availability.message {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(10)
                    .innoLiveGlassBackground(cornerRadius: 10, overVideo: true)
            }
        }
        .frame(maxWidth: 280, alignment: .leading)
        .accessibilityIdentifier("youtube-viewer-chat-overlay")
    }
}

final class YouTubeViewerChatLoadState: ObservableObject {
    @Published var isLoading = true
    @Published var failed = false
    @Published var retry = 0
    @Published var messages: [YouTubeChatMessage] = []
    @Published var chatReady = false
}

struct YouTubeViewerChatContent: View {
    let url: URL
    var isChatHidden = false
    @StateObject private var state = YouTubeViewerChatLoadState()

    var body: some View {
        ZStack {
            // Keep the receiver running at a normal page size, behind native message rows.
            YouTubeViewerChatWebView(url: url, state: state)
                .opacity(0)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            if !isChatHidden {
                YouTubeChatMessageList(messages: state.messages)
                if state.messages.isEmpty, !state.failed {
                    VStack(spacing: 8) {
                        if !state.chatReady { ProgressView().tint(.white) }
                        Text(state.chatReady
                             ? String(localized: "새 채팅을 기다리고 있습니다.", table: "ViewerChat")
                             : String(localized: "채팅을 연결하고 있습니다.", table: "ViewerChat"))
                            .font(.caption)
                    }
                    .foregroundStyle(.white)
                    .padding(10)
                    .innoLiveGlassBackground(cornerRadius: 10, overVideo: true)
                }
                if state.failed {
                    VStack(spacing: 12) {
                        Text(String(localized: "채팅을 불러오지 못했습니다.", table: "ViewerChat"))
                        Button(String(localized: "다시 시도")) { state.retry += 1 }
                    }
                    .padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                }
            }
        }
    }
}

struct YouTubeViewerChatWebView: UIViewRepresentable {
    let url: URL
    @ObservedObject var state: YouTubeViewerChatLoadState

    func makeCoordinator() -> Coordinator { Coordinator(document: url, state: state) }

    func makeUIView(context: Context) -> WKWebView {
        Self.makeWebView(coordinator: context.coordinator)
    }

    static func makeWebView(coordinator: Coordinator, configuration: WKWebViewConfiguration = WKWebViewConfiguration()) -> WKWebView {
        configuration.websiteDataStore = .nonPersistent()
        configuration.userContentController.add(coordinator, contentWorld: .defaultClient, name: YouTubeChatBridge.name)
        configuration.userContentController.addUserScript(WKUserScript(
            source: YouTubeChatBridge.script, injectionTime: .atDocumentEnd,
            forMainFrameOnly: true, in: .defaultClient
        ))
        configuration.defaultWebpagePreferences.preferredContentMode = .desktop
        configuration.allowsInlineMediaPlayback = false
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        let webView = WKWebView(frame: .zero, configuration: configuration)
        // Desktop content mode alone leaves out Safari's browser/version tokens.
        // YouTube rejects that default WKWebView identity as an outdated browser.
        let version = ProcessInfo.processInfo.operatingSystemVersion
        webView.customUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
            + "Version/\(version.majorVersion).\(version.minorVersion) Safari/605.1.15"
        webView.navigationDelegate = coordinator
        webView.allowsLinkPreview = false
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.load(url, retry: state.retry, in: webView)
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        coordinator.isActive = false
        coordinator.readinessTask?.cancel()
        webView.stopLoading()
        webView.configuration.userContentController.removeScriptMessageHandler(forName: YouTubeChatBridge.name, contentWorld: .defaultClient)
        webView.navigationDelegate = nil
        webView.loadHTMLString("", baseURL: nil)
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var document: URL
        var isActive = true
        var readinessTask: Task<Void, Never>?
        var loadedDocument: URL?
        private var loadedRetry: Int?
        private var navigation: WKNavigation?
        let state: YouTubeViewerChatLoadState

        init(document: URL, state: YouTubeViewerChatLoadState) {
            self.document = document
            self.state = state
        }

        func load(_ url: URL, retry: Int, in webView: WKWebView) {
            guard loadedDocument != url || loadedRetry != retry else { return }
            readinessTask?.cancel()
            webView.stopLoading()
            document = url
            loadedDocument = url
            loadedRetry = retry
            navigation = webView.load(URLRequest(url: url))
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            guard navigation === self.navigation else { return }
            state.failed = false
            state.isLoading = true
            state.messages = []
            state.chatReady = false
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard navigation === self.navigation else { return }
            state.isLoading = false
            readinessTask?.cancel()
            readinessTask = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(12))
                guard !Task.isCancelled, let self, self.isActive,
                      !self.state.chatReady else { return }
                self.state.failed = true
            }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            didFail(navigation, error: error)
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            didFail(navigation, error: error)
        }

        private func didFail(_ navigation: WKNavigation?, error: Error) {
            guard navigation === self.navigation, (error as NSError).code != NSURLErrorCancelled else { return }
            state.isLoading = false
            state.failed = true
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard isActive, message.frameInfo.isMainFrame,
                  message.name == YouTubeChatBridge.name,
                  let json = message.body as? String, json.utf8.count <= 512_000,
                  let data = json.data(using: .utf8),
                  let snapshot = try? JSONDecoder().decode(YouTubeChatSnapshot.self, from: data),
                  snapshot.url == document.absoluteString
                    || YouTubeViewerChatPolicy.allowsNavigation(to: URL(string: snapshot.url), document: document)
            else { return }
            state.messages = snapshot.visibleMessages
            state.chatReady = snapshot.ready
            if snapshot.ready {
                readinessTask?.cancel()
                state.isLoading = false
                state.failed = false
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            // Keep login, links, new windows and custom schemes out of this viewing surface.
            let allowed = navigationAction.targetFrame?.isMainFrame == true
                && navigationAction.navigationType != .linkActivated
                && navigationAction.navigationType != .formSubmitted
                && YouTubeViewerChatPolicy.allowsNavigation(to: navigationAction.request.url, document: document)
            decisionHandler(allowed ? .allow : .cancel)
        }
    }
}
