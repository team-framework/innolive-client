#if DEBUG
import SwiftUI

/// Deterministic, network-free visual verification of production feedback views.
struct BroadcastFeedbackValidationView: View {
    @StateObject private var youtube = YouTubeIntegration(
        preferencesStore: YouTubePreferencesStore(userDefaults: UserDefaults(suiteName: "feedback-visual-fixture")!)
    )
    @StateObject private var authentication = AuthSession()
    @State private var seeded = false

    private var scenario: String {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "--feedback-case"), args.indices.contains(index + 1) else { return "notices" }
        return args[index + 1]
    }

    var body: some View {
        NavigationStack {
            if scenario == "bad_request" {
                BroadcastSettingsView(authentication: authentication, youtube: youtube, onPrepare: {})
            } else {
                VStack {
                    Spacer()
                    BroadcastControllsView(isBroadcasting: .constant(true), previewTransition: .constant(.none), authentication: authentication, youtube: youtube, isStartingServerConnection: false, onRetryConnection: {})
                }.padding()
            }
        }.task {
            guard !seeded else { return }
            seeded = true
            let stream = YouTubeStreamState(status: "idle", startedAt: nil, stoppedAt: nil, publisherActive: true, lastError: nil, reconnectAttempts: 0, stopReason: nil, pausedAt: nil, broadcastPhase: "idle")
            let session = YouTubeBroadcastSession(sessionID: "visual-fixture", ownerToken: "fixture-owner", stream: stream)
            youtube.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "fixture", title: "Fixture")), session: session, videoTrack: .init(id: "fixture", kind: "video", readyState: "live"))
            if scenario == "notices" {
                let snapshot = YouTubeSessionResponse(stream: stream, media: .init(anonymizationEnabled: false, rawVideoTrack: .init(id: "fixture", kind: "video", readyState: "live")), details: .init(notices: [.init(code: "broadcast_limit_10m", at: "fixture"), .init(code: "platform_broadcast_ended", at: "fixture")]))
                youtube.applyBroadcastSnapshot(snapshot, sessionID: session.sessionID)
            } else {
                youtube.showBroadcastProblem(.init(status: scenario == "bad_request" ? 400 : 409, code: scenario, message: "fixture", provider: "youtube", field: scenario == "bad_request" ? "title" : nil, reason: "required", helpURL: nil))
            }
        }
    }
}
#endif
