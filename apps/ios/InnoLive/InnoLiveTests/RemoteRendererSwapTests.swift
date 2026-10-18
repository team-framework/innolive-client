@preconcurrency import LiveKitWebRTC
import Combine
import CoreVideo
import SwiftUI
import UIKit
import XCTest

@testable import InnoLive

/// Face selection swaps the broadcast output between the main area and the corner.
/// SwiftUI may update the outgoing view after creating the incoming one, so the
/// uplink must keep feeding every renderer still on screen.
@MainActor
final class RemoteRendererSwapTests: XCTestCase {
    func testOutgoingRendererUpdateDoesNotStopTheIncomingRenderer() throws {
        let uplink = WebRTCVideoUplink()
        let source = uplink.peerConnectionFactory.videoSource()
        uplink.setRemoteVideoTrack(uplink.peerConnectionFactory.videoTrack(with: source, trackId: "swap-remote"))
        let corner = CountingRenderer(frame: .zero)
        let main = CountingRenderer(frame: .zero)

        // Order recorded when leaving face selection: the main view is created,
        // the outgoing corner view is updated once more, then it is dismantled.
        uplink.attachRemoteRenderer(corner)
        uplink.attachRemoteRenderer(main)
        uplink.attachRemoteRenderer(corner)
        uplink.detachRemoteRenderer(corner)

        try deliverFrame(to: source, timestampNs: 1_000_000)

        XCTAssertGreaterThan(main.frameCount, 0, "The main broadcast view went black after the swap")
        XCTAssertEqual(corner.frameCount, 0)
    }

    func testDetachedRendererStopsAndNewTrackReachesRemainingRenderers() throws {
        let uplink = WebRTCVideoUplink()
        let factory = uplink.peerConnectionFactory
        let firstSource = factory.videoSource()
        uplink.setRemoteVideoTrack(factory.videoTrack(with: firstSource, trackId: "first-remote"))
        let kept = CountingRenderer(frame: .zero)
        let removed = CountingRenderer(frame: .zero)
        uplink.attachRemoteRenderer(kept)
        uplink.attachRemoteRenderer(removed)
        uplink.detachRemoteRenderer(removed)

        // Reconnecting replaces the remote track; the remaining renderer moves with it.
        let nextSource = factory.videoSource()
        uplink.setRemoteVideoTrack(factory.videoTrack(with: nextSource, trackId: "next-remote"))
        try deliverFrame(to: firstSource, timestampNs: 1_000_000)
        XCTAssertEqual(kept.frameCount, 0, "The old track must no longer render")
        try deliverFrame(to: nextSource, timestampNs: 2_000_000)

        XCTAssertGreaterThan(kept.frameCount, 0)
        XCTAssertEqual(removed.frameCount, 0)
    }

    func testFaceSelectionSwapKeepsTheBroadcastViewRendering() async throws {
        let uplink = WebRTCVideoUplink()
        let factory = uplink.peerConnectionFactory
        let source = factory.videoSource()
        // Any capturer makes the views show the remote renderer; nothing is captured here.
        uplink.fileVideoCapturer = LKRTCFileVideoCapturer(delegate: source)
        uplink.setRemoteVideoTrack(factory.videoTrack(with: source, trackId: "swap-hosted"))
        defer { uplink.fileVideoCapturer = nil }

        let state = SwapState()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive })
        let previousKeyWindow = scene.windows.first { $0.isKeyWindow }
        let window = UIWindow(windowScene: scene)
        window.frame = scene.coordinateSpace.bounds
        window.rootViewController = UIHostingController(rootView: SwapHome(state: state, uplink: uplink))
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKeyAndVisible()
        }

        try await settle()
        try assertEveryRemoteViewRenders(uplink, in: window, when: "before selecting")
        for round in 0..<3 {
            state.isSelecting = true
            try await settle()
            try assertEveryRemoteViewRenders(uplink, in: window, when: "selecting \(round)")
            state.isSelecting = false
            try await settle()
            try assertEveryRemoteViewRenders(uplink, in: window, when: "back \(round)")
        }
    }

    private func assertEveryRemoteViewRenders(
        _ uplink: WebRTCVideoUplink,
        in window: UIWindow,
        when moment: String
    ) throws {
        let onScreen = descendants(of: window).compactMap { $0 as? LKRTCMTLVideoView }
        XCTAssertEqual(onScreen.count, 1, "\(moment): exactly one broadcast view is shown")
        for renderer in onScreen {
            XCTAssertTrue(uplink.remoteRenderers.contains(renderer), "\(moment): the broadcast view is not rendering")
        }
    }

    private func descendants(of view: UIView) -> [UIView] {
        view.subviews + view.subviews.flatMap(descendants(of:))
    }

    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(300))
    }

    private func deliverFrame(to source: LKRTCVideoSource, timestampNs: Int64) throws {
        var pixelBuffer: CVPixelBuffer?
        CVPixelBufferCreate(kCFAllocatorDefault, 64, 64, kCVPixelFormatType_32BGRA, nil, &pixelBuffer)
        let buffer = LKRTCCVPixelBuffer(pixelBuffer: try XCTUnwrap(pixelBuffer))
        let frame = LKRTCVideoFrame(buffer: buffer, rotation: ._0, timeStampNs: timestampNs)
        source.capturer(LKRTCVideoCapturer(delegate: source), didCapture: frame)
    }
}

private final class CountingRenderer: LKRTCMTLVideoView {
    private let lock = NSLock()
    private var frames = 0

    var frameCount: Int { lock.withLock { frames } }

    override func renderFrame(_ frame: LKRTCVideoFrame?) {
        guard frame != nil else { return }
        lock.withLock { frames += 1 }
    }
}

@MainActor
private final class SwapState: ObservableObject {
    @Published var isSelecting = false
}

/// The same arrangement as HomeView: the main area and the corner preview trade the broadcast view.
private struct SwapHome: View {
    @ObservedObject var state: SwapState
    let uplink: WebRTCVideoUplink

    var body: some View {
        ZStack {
            if state.isSelecting {
                Color.gray
            } else {
                RemoteStreamView(
                    uplink: uplink,
                    previewTransition: .none,
                    isPreparingSession: false,
                    isConnectingVideo: false
                )
                .ignoresSafeArea()
            }

            GeometryReader { _ in
                Group {
                    if state.isSelecting {
                        OriginalPreviewFrame {
                            CompactRemoteStreamView(uplink: uplink)
                        }
                    } else {
                        Color.blue
                    }
                }
                .frame(width: 120, height: 200)
                .position(x: 80, y: 120)
            }
        }
    }
}
