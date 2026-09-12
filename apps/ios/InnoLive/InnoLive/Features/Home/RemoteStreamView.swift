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

    var body: some View {
        ZStack {
            Color.black

            if uplink.isCapturingMedia,
               !uplink.isReleasingCamera,
               previewTransition == .none {
                WebRTCRemoteVideoView(uplink: uplink)
            }
        }
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
        previewTransition: .none
    )
}
