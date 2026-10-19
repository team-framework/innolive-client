#if DEBUG
import SwiftUI

/// Local UI inspection without signing in or starting capture and broadcast lifecycle.
struct StudioValidationView: View {
    @StateObject private var authentication = AuthSession(api: AuthenticationAPI(serverURLProvider: { _ in nil }))
    @StateObject private var youtube: YouTubeIntegration

    init() {
        let integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(), api: YouTubeAPI(serverURLProvider: { _ in nil }))
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--studio-live-preview") || arguments.contains("--studio-multi-preview") || arguments.contains("--studio-ready-preview") {
            let phase = arguments.contains("--studio-ready-preview") ? "prepared" : "live"
            let status = phase == "prepared" ? "idle" : "streaming"
            let stream = "{\"status\":\"\(status)\",\"broadcast_phase\":\"\(phase)\",\"publisher_active\":true,\"reconnect_attempts\":0}"
            let providers = arguments.contains("--studio-multi-preview") ? ["youtube", "chzzk"] : ["youtube"]
            let targets = providers.map { "{\"provider\":\"\($0)\",\"stream\":\(stream)}" }.joined(separator: ",")
            let json = "{\"session_id\":\"studio-preview\",\"owner_token\":\"preview-only\",\"stream\":\(stream),\"targets\":[\(targets)],\"media\":{}}"
            if let session = try? JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data(json.utf8)) {
                integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "preview", title: "Preview")), session: session, videoTrack: .init(id: "video", kind: "video", readyState: "live"))
            }
        }
        _youtube = StateObject(wrappedValue: integration)
    }

    private var initialPage: StudioPage {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--studio-page-preview") { return .preview }
        if arguments.contains("--studio-page-details") { return .details }
        return .basic
    }

    var body: some View {
        NavigationStack {
            HomeView(authentication: authentication, youtube: youtube,
                     initialPage: initialPage, startsLifecycle: false)
        }
        .preferredColorScheme(.dark)
    }
}
#endif
