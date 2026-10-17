import SwiftUI
import UIKit
import XCTest

@testable import InnoLive

@MainActor
final class BroadcastEndOverlayRenderingTests: XCTestCase {
    func testEndingAndResultInLightDarkAndAccessibilityText() async throws {
        for appearance in [UIUserInterfaceStyle.light, .dark] {
            for size in [DynamicTypeSize.large, .accessibility3] {
                try await render("ending", phase: .ending, appearance: appearance, size: size)
                try await render("ended-no-recorder", phase: .result, appearance: appearance, size: size)
            }
        }
    }

    func testRecorderStatesRemainDistinct() async throws {
        try await render("finalizing", recording: .finalizing, recorderEnabled: true)
        try await render("save-failed", recording: .failed("저장 공간이 부족합니다."), recorderEnabled: true)
        try await render("missing-file", recording: .saved(URL(fileURLWithPath: "/missing-recording.mp4")), recorderEnabled: true)
        // The UI must not claim saving before a writer is connected (step 4).
        try await render("unconnected-writer", recording: .finalizing, recorderEnabled: false)
    }

    func testLongBroadcastAndHighContrastRemainScrollable() async throws {
        try await render("long-broadcast", size: .accessibility5, strongContrast: true,
                         duration: 3 * 3600 + 15 * 60 + 9)
    }

    private func render(
        _ name: String,
        phase: BroadcastLifecyclePhase = .result,
        recording: BroadcastRecordingState = .idle,
        recorderEnabled: Bool = false,
        appearance: UIUserInterfaceStyle = .dark,
        size: DynamicTypeSize = .large,
        strongContrast: Bool = false,
        duration: TimeInterval = 76
    ) async throws {
        // Hosted tests can start before UIKit finishes activating the application scene.
        for _ in 0..<50 {
            if UIApplication.shared.connectedScenes.contains(where: { $0.activationState == .foregroundActive }) { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousKeyWindow = scene.windows.first { $0.isKeyWindow }
        let started = Date(timeIntervalSince1970: 1_000)
        var actionCount = 0
        let root = ZStack {
            LinearGradient(colors: [.blue, .orange], startPoint: .topLeading, endPoint: .bottomTrailing)
                .ignoresSafeArea()
            BroadcastEndOverlay(
                phase: phase,
                result: .init(startedAt: started, endedAt: started.addingTimeInterval(duration)),
                recording: recording, isRecordingEnabled: recorderEnabled,
                onClose: { actionCount += 1 },
                onPlay: { _ in actionCount += 1 },
                onSave: { _ in actionCount += 1 }
            )
        }
        .dynamicTypeSize(size)
        .environment(\.colorScheme, appearance == .dark ? .dark : .light)
        let controller = UIHostingController(rootView: root)
        controller.traitOverrides.accessibilityContrast = strongContrast ? UIAccessibilityContrast.high : .normal
        let window = UIWindow(windowScene: scene)
        window.frame = scene.coordinateSpace.bounds
        window.overrideUserInterfaceStyle = appearance
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKeyAndVisible()
        }
        try await Task.sleep(for: .milliseconds(450))
        controller.view.layoutIfNeeded()
        window.layoutIfNeeded()
        let scroll = try XCTUnwrap(descendants(window).compactMap { $0 as? UIScrollView }.first)
        XCTAssertGreaterThan(scroll.bounds.width, 200)
        XCTAssertGreaterThanOrEqual(scroll.contentSize.height, scroll.bounds.height - scroll.adjustedContentInset.top - scroll.adjustedContentInset.bottom - 1)
        XCTAssertLessThanOrEqual(scroll.contentSize.width, scroll.bounds.width - scroll.adjustedContentInset.left - scroll.adjustedContentInset.right + 1)
        XCTAssertEqual(actionCount, 0, "Rendering must not close, play or export automatically")
        let format = UIGraphicsImageRendererFormat()
        format.scale = scene.screen.scale
        let screenshot = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        XCTAssertGreaterThan(screenshot.pngData()?.count ?? 0, 10_000)
        let attachment = XCTAttachment(image: screenshot)
        attachment.name = "end-\(name)-\(appearance == .dark ? "dark" : "light")-\(size)-iOS\(UIDevice.current.systemVersion)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func descendants(_ view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap(descendants)
    }
}
