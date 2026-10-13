import SwiftUI
import UIKit
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastModeRenderingTests: XCTestCase {
    func testModeScreenRendersAtLargeTextSizes() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousWindow = scene.windows.first { $0.isKeyWindow }
        let suite = "mode-render-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let authentication = AuthSession(api: AuthenticationAPI(serverURLProvider: { _ in nil }),
            tokenStore: ModeEmptyTokenStore(), consentStore: ConsentAcknowledgementStore(userDefaults: defaults))
        let integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults))
        let json = """
        {"session_id":"mode-render","owner_token":"fake-owner","broadcast_resolution":"720p",
        "stream":{"status":"streaming","broadcast_phase":"live","publisher_active":true,"reconnect_attempts":0},
        "targets":[{"provider":"youtube","stream":{"status":"streaming","broadcast_phase":"live","publisher_active":true,"reconnect_attempts":0}}],"media":{}}
        """
        let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data(json.utf8))
        integration.seedPreparedVideoSessionForTesting(connection: .init(provider: "youtube", channel: .init(id: "fake-channel", title: "YT")),
            session: session, videoTrack: .init(id: "video", kind: "video", readyState: "live"))
        defer { integration.reset() }
        for large in [false, true] {
            let window = UIWindow(windowScene: scene)
            window.frame = scene.coordinateSpace.bounds
            window.overrideUserInterfaceStyle = large ? .dark : .light
            window.rootViewController = UIHostingController(rootView: NavigationStack {
                BroadcastModeView(authentication: authentication, youtube: integration)
            }.dynamicTypeSize(large ? .accessibility3 : .large))
            window.makeKeyAndVisible()
            try await Task.sleep(for: .milliseconds(400))
            window.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
            }
            XCTAssertGreaterThan(try XCTUnwrap(image.pngData()).count, 10_000)
            let attachment = XCTAttachment(image: image)
            attachment.name = large ? "broadcast-mode-large-dark" : "broadcast-mode-light"
            attachment.lifetime = .keepAlways
            add(attachment)
            window.isHidden = true
            window.rootViewController = nil
            previousWindow?.makeKeyAndVisible()
        }
    }
}

private final class ModeEmptyTokenStore: AuthenticationTokenStoring {
    nonisolated deinit {}
    func load() -> AuthenticationTokenPair? { nil }
    func save(_ tokens: AuthenticationTokenPair) throws { XCTFail("Rendering must not sign in") }
    func remove() { XCTFail("Rendering must not sign out") }
}
