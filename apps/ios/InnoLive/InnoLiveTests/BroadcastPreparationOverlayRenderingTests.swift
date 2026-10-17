import SwiftUI
import UIKit
import XCTest

@testable import InnoLive

@MainActor
final class BroadcastPreparationOverlayRenderingTests: XCTestCase {
    func testPreparationStagesRenderOnDevice() async throws {
        let phases: [BroadcastPreparationPhase] = [
            .creatingSession, .connectingServer, .confirmingVideo,
            .savingSettings, .preparingStream, .cancelling,
        ]
        for (index, phase) in phases.enumerated() {
            try await render("stage-\(index)", status: .init(phase: phase), appearance: .dark)
        }
    }

    func testFailureAndRetryRenderWithLargeTextAndBothAppearances() async throws {
        for appearance in [UIUserInterfaceStyle.light, .dark] {
            for textSize in [DynamicTypeSize.large, .accessibility3] {
                try await render(
                    "failure", status: .init(
                        phase: .confirmingVideo,
                        failedPhase: .confirmingVideo,
                        message: "영상 입력을 확인하지 못했습니다. 연결을 확인하고 다시 시도해 주세요."
                    ),
                    appearance: appearance, textSize: textSize
                )
            }
        }
    }

    func testHighContrastRenders() async throws {
        try await render("accessible-contrast", status: .init(phase: .connectingServer),
                         appearance: .dark, textSize: .accessibility3, strongContrast: true)
    }

    private func render(
        _ name: String,
        status: BroadcastPreparationStatus,
        appearance: UIUserInterfaceStyle,
        textSize: DynamicTypeSize = .large,
        strongContrast: Bool = false
    ) async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousKeyWindow = scene.windows.first { $0.isKeyWindow }
        var retryCount = 0
        var cancelCount = 0
        let root = ZStack {
            LinearGradient(colors: [.blue, .orange], startPoint: .topLeading, endPoint: .bottomTrailing)
                .ignoresSafeArea()
            BroadcastPreparationOverlay(status: status,
                                        onRetry: { retryCount += 1 },
                                        onCancel: { cancelCount += 1 })
        }
        .dynamicTypeSize(textSize)
        .environment(\.colorScheme, appearance == .dark ? .dark : .light)
        let controller = UIHostingController(rootView: root)
        controller.traitOverrides.accessibilityContrast = strongContrast ? UIAccessibilityContrast.high : UIAccessibilityContrast.normal
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
        let viewportHeight = scroll.bounds.height - scroll.adjustedContentInset.top - scroll.adjustedContentInset.bottom
        XCTAssertGreaterThanOrEqual(scroll.contentSize.height, viewportHeight - 1)
        // Accessibility sizes must remain scrollable without horizontal clipping.
        XCTAssertLessThanOrEqual(scroll.contentSize.width, scroll.bounds.width + 1)
        XCTAssertEqual(retryCount, 0, "Rendering a failure must not trigger an automatic retry")
        XCTAssertEqual(cancelCount, 0)
        let format = UIGraphicsImageRendererFormat()
        format.scale = scene.screen.scale
        let screenshot = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { _ in
            XCTAssertTrue(window.drawHierarchy(in: window.bounds, afterScreenUpdates: true))
        }
        XCTAssertGreaterThan(screenshot.pngData()?.count ?? 0, 10_000)
        let attachment = XCTAttachment(image: screenshot)
        attachment.name = "preparation-\(name)-\(appearance == .dark ? "dark" : "light")-\(textSize)-iOS\(UIDevice.current.systemVersion)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func descendants(_ view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap(descendants)
    }
}
