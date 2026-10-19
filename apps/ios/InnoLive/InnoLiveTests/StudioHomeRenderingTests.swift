import AVFoundation
import SwiftUI
import UIKit
import XCTest
@testable import InnoLive

@MainActor
final class StudioHomeRenderingTests: XCTestCase {
    func testThreePagesRenderInPortraitAndLandscapeWithoutStartingMedia() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousWindow = scene.windows.first { $0.isKeyWindow }
        defer { previousWindow?.makeKeyAndVisible() }
        let suite = "studio-home-render-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let authentication = AuthSession(api: AuthenticationAPI(serverURLProvider: { _ in nil }),
            tokenStore: StudioHomeEmptyTokenStore(), consentStore: ConsentAcknowledgementStore(userDefaults: defaults))

        for state in ["idle", "live", "preparing"] {
            let live = state == "live"
            let preparing = state == "preparing"
            let integration = YouTubeIntegration(preferencesStore: YouTubePreferencesStore(userDefaults: defaults))
            defer { integration.reset() }
            if live || preparing {
                let json = """
                {"session_id":"studio-home-render","owner_token":"fake-owner","broadcast_resolution":"720p",
                "stream":{"status":"streaming","broadcast_phase":"live","publisher_active":true,"reconnect_attempts":0},
                "targets":[{"provider":"youtube","stream":{"status":"streaming","broadcast_phase":"live","publisher_active":true,"reconnect_attempts":0}}],"media":{}}
                """
                let session = try JSONDecoder().decode(YouTubeBroadcastSession.self, from: Data(json.utf8))
                integration.seedPreparedVideoSessionForTesting(
                    connection: .init(provider: "youtube", channel: .init(id: "fake-channel", title: "YT")),
                    session: session, videoTrack: .init(id: "video", kind: "video", readyState: "live"),
                    preparationStatus: preparing ? .init(phase: .connectingServer) : nil)
            }
            let sessionID = integration.session?.sessionID
            for configuration in [(landscape: false, large: false), (landscape: true, large: false), (landscape: false, large: true)] {
                let landscape = configuration.landscape
                for page in StudioPage.allCases {
                    let size = landscape ? CGSize(width: 844, height: 390) : CGSize(width: 390, height: 844)
                    let camera = CameraManager()
                    let window = UIWindow(windowScene: scene)
                    window.frame = CGRect(origin: .zero, size: size)
                    window.overrideUserInterfaceStyle = .dark
                    window.rootViewController = UIHostingController(rootView:
                        NavigationStack {
                            HomeView(authentication: authentication, youtube: integration,
                                     initialPage: page, startsLifecycle: false)
                        }
                        .environment(camera)
                        .dynamicTypeSize(configuration.large ? .accessibility3 : .large)
                        .environment(\.verticalSizeClass, landscape ? .compact : .regular))
                    window.makeKeyAndVisible()
                    defer {
                        window.isHidden = true
                        window.rootViewController = nil
                    }
                    try await Task.sleep(for: .milliseconds(500))
                    window.layoutIfNeeded()
                    let probes = descendants(of: window).compactMap { $0 as? StudioPagerScrollLock.ProbeView }
                    XCTAssertFalse(probes.isEmpty, "The native pager must host a lock probe")
                    for probe in probes { probe.applyLock() }
                    let pager = try XCTUnwrap(probes.compactMap(\.pagingScrollView).first,
                                              "Native pager ancestry: \(probes.map { ancestorNames(of: $0) })")
                    probes.forEach { $0.isLocked = true; $0.applyLock() }
                    XCTAssertFalse(pager.isScrollEnabled)
                    probes.forEach { $0.isLocked = false; $0.applyLock() }
                    XCTAssertTrue(pager.isScrollEnabled)

                    let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                        XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
                    }
                    XCTAssertGreaterThan(try XCTUnwrap(image.pngData()).count, 0)
                    XCTAssertEqual(integration.session?.sessionID, sessionID)
                    XCTAssertFalse(integration.videoUplink.isCapturingMedia)
                    XCTAssertFalse(camera.session.isRunning)
                    let attachment = XCTAttachment(image: image)
                    attachment.name = "studio-home-\(state)-\(page)-\(landscape ? "landscape" : "portrait")-\(configuration.large ? "large-text" : "default")"
                    attachment.lifetime = .keepAlways
                    add(attachment)
                }
            }
        }
    }

    private func ancestorNames(of view: UIView) -> String {
        var ancestor = view.superview
        var names: [String] = []
        while let current = ancestor {
            names.append(String(describing: type(of: current)))
            ancestor = current.superview
        }
        return names.joined(separator: " > ")
    }

    private func descendants(of view: UIView) -> [UIView] {
        view.subviews.flatMap { [$0] + descendants(of: $0) }
    }
}

private final class StudioHomeEmptyTokenStore: AuthenticationTokenStoring {
    nonisolated deinit {}
    func load() -> AuthenticationTokenPair? { nil }
    func save(_ tokens: AuthenticationTokenPair) throws { XCTFail("Rendering must not sign in") }
    func remove() { XCTFail("Rendering must not sign out") }
}
