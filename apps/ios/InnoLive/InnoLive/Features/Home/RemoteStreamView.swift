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

struct RemoteStreamView: View {
    @ObservedObject var uplink: WebRTCVideoUplink

    var body: some View {
        ZStack {
            if uplink.isCapturingCamera {
                WebRTCRemoteVideoView(uplink: uplink)
                    .ignoresSafeArea()
            } else {
                Color.black

                ContentUnavailableView(
                    "방송 송출 화면을 기다리는 중",
                    systemImage: "dot.radiowaves.left.and.right",
                    description: Text("방송 시작 후 서버 처리 영상이 이 영역에 표시됩니다.")
                )
                .foregroundStyle(.white.opacity(0.8))
            }
        }
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
        remoteVideoView.videoContentMode = .scaleAspectFit
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

        if hasRemoteVideo {
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
        } else {
            localVideoView.frame = bounds
            localVideoView.layer.cornerRadius = 0
            localVideoView.layer.borderWidth = 0
            localVideoView.clipsToBounds = true
        }

        localVideoView.transform = mirrorsLocalVideo
            ? CGAffineTransform(scaleX: -1, y: 1)
            : .identity
    }
}

#Preview {
    RemoteStreamView(uplink: WebRTCVideoUplink())
}
