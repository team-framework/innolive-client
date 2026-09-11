//
//  RemoteStreamView.swift
//  InnoLive
//

@preconcurrency import LiveKitWebRTC
import SwiftUI
import UIKit

enum BroadcastVideoLayout {
    static let aspectRatio: CGFloat = 9.0 / 16.0
    static let previewWidth: CGFloat = 120
    static let previewHeight: CGFloat = previewWidth / aspectRatio
}

enum BroadcastPreviewTransition: Equatable {
    case none
    case starting
    case stopping
}

struct RemoteStreamView: View {
    @ObservedObject var uplink: WebRTCVideoUplink
    let previewTransition: BroadcastPreviewTransition
    let isPreparingSession: Bool
    let isConnectingVideo: Bool

    private var connectionPhase: BroadcastConnectionPhase? {
        if isPreparingSession {
            return .preparingSession
        }
        if previewTransition == .starting || isConnectingVideo || uplink.isConnecting {
            return .connectingCamera
        }
        if previewTransition == .stopping || uplink.isReleasingCamera {
            return .restoringPreview
        }
        if uplink.isCapturingMedia && !uplink.hasRemoteVideo {
            return .waitingForProcessedVideo
        }
        return nil
    }

    var body: some View {
        ZStack {
            Color.black

            if uplink.isCapturingMedia,
               !uplink.isReleasingCamera,
               previewTransition == .none {
                WebRTCRemoteVideoView(uplink: uplink)
            }

            if let connectionPhase {
                BroadcastConnectionOverlay(phase: connectionPhase)
            } else if !uplink.hasRemoteVideo {
                ContentUnavailableView(
                    "방송 송출 화면을 기다리는 중",
                    systemImage: "dot.radiowaves.left.and.right",
                    description: Text("서버 처리 영상이 준비되면 이 영역에 표시됩니다.")
                )
                .foregroundStyle(.white.opacity(0.8))
            }
        }
    }
}

private enum BroadcastConnectionPhase {
    case preparingSession
    case connectingCamera
    case waitingForProcessedVideo
    case restoringPreview

    var title: String {
        switch self {
        case .preparingSession: return "방송을 준비하는 중"
        case .connectingCamera: return "서버에 카메라 영상을 연결하는 중"
        case .waitingForProcessedVideo: return "비식별화 영상을 준비하는 중"
        case .restoringPreview: return "카메라 미리보기를 복구하는 중"
        }
    }

    var description: String {
        switch self {
        case .preparingSession: return "방송 세션을 만들고 있습니다."
        case .connectingCamera: return "카메라와 마이크를 서버에 연결하고 있습니다."
        case .waitingForProcessedVideo: return "서버 처리 영상이 곧 표시됩니다."
        case .restoringPreview: return "카메라를 다시 준비하고 있습니다."
        }
    }
}

private struct BroadcastConnectionOverlay: View {
    let phase: BroadcastConnectionPhase

    var body: some View {
        VStack(spacing: 12) {
            ProgressView()
                .controlSize(.large)
                .tint(.white)
            Text(phase.title)
                .font(.headline)
            Text(phase.description)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.72))
                .multilineTextAlignment(.center)
        }
        .foregroundStyle(.white)
        .padding(24)
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(phase.title). \(phase.description)")
    }
}

struct WebRTCLocalPreviewView: UIViewRepresentable {
    @ObservedObject var uplink: WebRTCVideoUplink

    func makeCoordinator() -> Coordinator {
        Coordinator(uplink: uplink)
    }

    func makeUIView(context: Context) -> LKRTCMTLVideoView {
        let view = LKRTCMTLVideoView(frame: .zero)
        view.videoContentMode = .scaleAspectFit
        view.isUserInteractionEnabled = false
        context.coordinator.uplink = uplink
        uplink.attachLocalRenderer(view)
        applyMirroring(to: view)
        return view
    }

    func updateUIView(_ uiView: LKRTCMTLVideoView, context: Context) {
        context.coordinator.uplink = uplink
        uplink.attachLocalRenderer(uiView)
        applyMirroring(to: uiView)
    }

    static func dismantleUIView(_ uiView: LKRTCMTLVideoView, coordinator: Coordinator) {
        coordinator.uplink.detachLocalRenderer(uiView)
    }

    private func applyMirroring(to view: LKRTCMTLVideoView) {
        view.transform = uplink.isUsingFrontCamera
            ? CGAffineTransform(scaleX: -1, y: 1)
            : .identity
    }

    final class Coordinator {
        var uplink: WebRTCVideoUplink

        init(uplink: WebRTCVideoUplink) {
            self.uplink = uplink
        }
    }
}

private struct WebRTCRemoteVideoView: UIViewRepresentable {
    @ObservedObject var uplink: WebRTCVideoUplink

    func makeUIView(context: Context) -> NativeWebRTCVideoView {
        let container = NativeWebRTCVideoView()
        container.uplink = uplink
        uplink.attachRemoteRenderer(container.remoteVideoView)
        container.update(hasRemoteVideo: uplink.hasRemoteVideo)
        return container
    }

    func updateUIView(_ uiView: NativeWebRTCVideoView, context: Context) {
        uiView.uplink = uplink
        uplink.attachRemoteRenderer(uiView.remoteVideoView)
        uiView.update(hasRemoteVideo: uplink.hasRemoteVideo)
    }

    static func dismantleUIView(_ uiView: NativeWebRTCVideoView, coordinator: ()) {
        uiView.uplink?.detachRemoteRenderer(uiView.remoteVideoView)
    }
}

private final class NativeWebRTCVideoView: UIView {
    let remoteVideoView = LKRTCMTLVideoView(frame: .zero)
    weak var uplink: WebRTCVideoUplink?

    private var hasRemoteVideo = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        clipsToBounds = true
        isUserInteractionEnabled = false
        remoteVideoView.isUserInteractionEnabled = false
        // 세로 9:16 처리 영상을 iPhone 화면에 맞춰 채운다.
        // 화면 비율 차이로 좌우 가장자리는 일부 잘릴 수 있다.
        remoteVideoView.videoContentMode = .scaleAspectFill
        addSubview(remoteVideoView)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func update(hasRemoteVideo: Bool) {
        self.hasRemoteVideo = hasRemoteVideo
        remoteVideoView.isHidden = !hasRemoteVideo
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        remoteVideoView.frame = bounds
    }
}

#Preview {
    RemoteStreamView(
        uplink: WebRTCVideoUplink(),
        previewTransition: .none,
        isPreparingSession: false,
        isConnectingVideo: false
    )
}
