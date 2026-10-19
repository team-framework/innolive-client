import SwiftUI
import UIKit
import XCTest
@testable import InnoLive

@MainActor
final class StudioDetailsRenderingTests: XCTestCase {
    func testDetailsRenderIdleAndLiveAtLargeTextSizes() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousWindow = scene.windows.first { $0.isKeyWindow }
        defer { previousWindow?.makeKeyAndVisible() }
        let suite = "studio-details-render-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let authentication = AuthSession(api: AuthenticationAPI(serverURLProvider: { _ in nil }),
            tokenStore: StudioDetailsEmptyTokenStore(), consentStore: ConsentAcknowledgementStore(userDefaults: defaults))

        for live in [false, true] {
            let integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults))
            defer { integration.reset() }
            if live {
                let json = """
                {"session_id":"studio-details-render","owner_token":"fake-owner","broadcast_resolution":"720p",
                "stream":{"status":"streaming","broadcast_phase":"live","publisher_active":true,"reconnect_attempts":0},
                "targets":[{"provider":"youtube","stream":{"status":"streaming","broadcast_phase":"live","publisher_active":true,"reconnect_attempts":0}}],"media":{}}
                """
                let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data(json.utf8))
                integration.seedPreparedVideoSessionForTesting(
                    connection: .init(provider: "youtube", channel: .init(id: "fake-channel", title: "YT")),
                    session: session, videoTrack: .init(id: "video", kind: "video", readyState: "live"))
            }
            let sessionID = integration.session?.sessionID
            for (large, targetsOnly) in [(false, false), (true, false), (false, true), (true, true)] {
                if targetsOnly && !live { continue }
                let window = UIWindow(windowScene: scene)
                window.frame = scene.coordinateSpace.bounds
                window.overrideUserInterfaceStyle = .dark
                window.rootViewController = UIHostingController(rootView:
                    Group {
                        if targetsOnly {
                            StudioTargetControls(authentication: authentication, youtube: integration).padding(24)
                        } else {
                            StudioDetailsView(authentication: authentication, youtube: integration)
                        }
                    }
                        .background(Color.gray)
                        .dynamicTypeSize(large ? .accessibility3 : .large))
                window.makeKeyAndVisible()
                defer {
                    window.isHidden = true
                    window.rootViewController = nil
                }
                try await Task.sleep(for: .milliseconds(400))
                window.layoutIfNeeded()
                let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                    XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
                }
                XCTAssertGreaterThan(try XCTUnwrap(image.pngData()).count, 10_000)
                XCTAssertEqual(integration.session?.sessionID, sessionID, "Rendering must preserve the broadcast session")
                let attachment = XCTAttachment(image: image)
                attachment.name = "studio-\(targetsOnly ? "target-controls" : "details")-\(live ? "live" : "idle")-\(large ? "large-text" : "default")"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
        }
    }
}

private final class StudioDetailsEmptyTokenStore: AuthenticationTokenStoring {
    nonisolated deinit {}
    func load() -> AuthenticationTokenPair? { nil }
    func save(_ tokens: AuthenticationTokenPair) throws { XCTFail("Rendering must not sign in") }
    func remove() { XCTFail("Rendering must not sign out") }
}
