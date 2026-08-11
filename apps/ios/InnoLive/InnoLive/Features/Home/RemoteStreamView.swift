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
        if uplink.isCapturingCamera && !uplink.hasRemoteVideo {
            return .waitingForProcessedVideo
        }
        return nil
    }

    var body: some View {
        ZStack {
            Color.black

            if uplink.isCapturingCamera,
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
        .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(phase.title). \(phase.description)")
    }
}

private struct WebRTCRemoteVideoView: UIViewRepresentable {
    @ObservedObject var uplink: WebRTCVideoUplink

    func makeUIView(context: Context) -> NativeWebRTCVideoView {
        let container = NativeWebRTCVideoView()
        container.uplink = uplink
        uplink.attachRenderers(local: container.localVideoView, remote: container.remoteVideoView)
        container.update(
            hasRemoteVideo: uplink.hasRemoteVideo,
            mirrorsLocalVideo: uplink.isUsingFrontCamera
        )
        return container
    }

    func updateUIView(_ uiView: NativeWebRTCVideoView, context: Context) {
        uiView.uplink = uplink
        uplink.attachRenderers(local: uiView.localVideoView, remote: uiView.remoteVideoView)
        uiView.update(
            hasRemoteVideo: uplink.hasRemoteVideo,
            mirrorsLocalVideo: uplink.isUsingFrontCamera
        )
    }

    static func dismantleUIView(_ uiView: NativeWebRTCVideoView, coordinator: ()) {
        uiView.uplink?.detachRenderers(local: uiView.localVideoView, remote: uiView.remoteVideoView)
    }
}

private final class NativeWebRTCVideoView: UIView {
    let localVideoView = LKRTCMTLVideoView(frame: .zero)
    let remoteVideoView = LKRTCMTLVideoView(frame: .zero)
    weak var uplink: WebRTCVideoUplink?

    private var hasRemoteVideo = false
    private var mirrorsLocalVideo = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        clipsToBounds = true
        // 세로 9:16 처리 영상을 iPhone 화면에 맞춰 채운다.
        // 화면 비율 차이로 좌우 가장자리는 일부 잘릴 수 있다.
        remoteVideoView.videoContentMode = .scaleAspectFill
        localVideoView.videoContentMode = .scaleAspectFit
        addSubview(remoteVideoView)
        addSubview(localVideoView)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func update(hasRemoteVideo: Bool, mirrorsLocalVideo: Bool) {
        self.hasRemoteVideo = hasRemoteVideo
        self.mirrorsLocalVideo = mirrorsLocalVideo
        remoteVideoView.isHidden = !hasRemoteVideo
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        remoteVideoView.frame = bounds

        // 수신 영상이 오기 전에도 로컬 카메라는 작은 프리뷰로만 표시한다.
        // 처리되지 않은 로컬 영상을 수신 화면 전체에 대신 표시하지 않는다.
        localVideoView.frame = CGRect(
            x: 24,
            y: 32,
            width: BroadcastVideoLayout.previewWidth,
            height: BroadcastVideoLayout.previewHeight
        )
        localVideoView.layer.cornerRadius = 18
        localVideoView.layer.borderWidth = 1
        localVideoView.layer.borderColor = UIColor.white.withAlphaComponent(0.65).cgColor
        localVideoView.clipsToBounds = true

        localVideoView.transform = mirrorsLocalVideo
            ? CGAffineTransform(scaleX: -1, y: 1)
            : .identity
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
