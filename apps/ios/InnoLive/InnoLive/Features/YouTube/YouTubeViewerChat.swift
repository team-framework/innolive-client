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
        guard hasSession, let target = targets?.first(where: { $0.provider == "youtube" }),
              YouTubeBroadcastStatePolicy(stream: target.stream, isChangingStreamState: false).hasStartedBroadcast
        else { return nil }
        return url(videoID: target.videoID)
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

struct YouTubeViewerChatControl: View {
    @ObservedObject var youtube: YouTubeIntegration
    @State private var isPresented = false

    private var chatURL: URL? {
        YouTubeViewerChatPolicy.activeURL(targets: youtube.responseState.details.targets, hasSession: youtube.session != nil)
    }

    var body: some View {
        Group {
            if chatURL != nil {
                Button { isPresented = true } label: {
                    Label(String(localized: "시청자 채팅", table: "ViewerChat"), systemImage: "bubble.left.and.bubble.right")
                }
                .accessibilityIdentifier("youtube-viewer-chat")
            }
        }
        .sheet(isPresented: $isPresented) {
            NavigationStack {
                VStack(spacing: 8) {
                    Text(String(localized: "보기 전용 · 비공개 방송의 채팅은 표시되지 않을 수 있습니다.", table: "ViewerChat"))
                        .font(.caption).foregroundStyle(.secondary).padding(.horizontal)
                    if let chatURL {
                        YouTubeViewerChatContent(url: chatURL)
                    }
                }
                .navigationTitle(String(localized: "시청자 채팅", table: "ViewerChat"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(String(localized: "닫기")) { isPresented = false }
                    }
                }
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .onChange(of: chatURL) { _, url in
            if url == nil { isPresented = false }
        }
    }
}

final class YouTubeViewerChatLoadState: ObservableObject {
    @Published var isLoading = true
    @Published var failed = false
    @Published var retry = 0
}

struct YouTubeViewerChatContent: View {
    let url: URL
    @StateObject private var state = YouTubeViewerChatLoadState()

    var body: some View {
        ZStack {
            YouTubeViewerChatWebView(url: url, state: state)
            if state.isLoading { ProgressView() }
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

struct YouTubeViewerChatWebView: UIViewRepresentable {
    let url: URL
    @ObservedObject var state: YouTubeViewerChatLoadState

    func makeCoordinator() -> Coordinator { Coordinator(document: url, state: state) }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.allowsInlineMediaPlayback = false
        configuration.mediaTypesRequiringUserActionForPlayback = .all
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.allowsLinkPreview = false
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        context.coordinator.load(url, retry: state.retry, in: webView)
    }

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.loadHTMLString("", baseURL: nil)
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var document: URL
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
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard navigation === self.navigation else { return }
            state.isLoading = false
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
