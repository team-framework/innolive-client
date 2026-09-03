@preconcurrency import LiveKitWebRTC
import SwiftUI
import UIKit

struct WebRTCFaceRegistrationPreview: UIViewRepresentable {
    @ObservedObject var videoUplink: WebRTCVideoUplink

    func makeUIView(context: Context) -> NativeWebRTCFaceRegistrationPreview {
        let preview = NativeWebRTCFaceRegistrationPreview()
        preview.attach(to: videoUplink)
        preview.update(mirrorsVideo: videoUplink.isUsingFrontCamera)
        return preview
    }

    func updateUIView(
        _ uiView: NativeWebRTCFaceRegistrationPreview,
        context: Context
    ) {
        uiView.attach(to: videoUplink)
        uiView.update(mirrorsVideo: videoUplink.isUsingFrontCamera)
    }

    static func dismantleUIView(
        _ uiView: NativeWebRTCFaceRegistrationPreview,
        coordinator: Void
    ) {
        uiView.detach()
    }
}

final class NativeWebRTCFaceRegistrationPreview: UIView {
    private let videoView = LKRTCMTLVideoView(frame: .zero)
    private weak var videoUplink: WebRTCVideoUplink?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        clipsToBounds = true
        videoView.videoContentMode = .scaleAspectFill
        addSubview(videoView)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func attach(to videoUplink: WebRTCVideoUplink) {
        guard self.videoUplink !== videoUplink else {
            videoUplink.attachFaceRegistrationRenderer(videoView)
            return
        }
        detach()
        self.videoUplink = videoUplink
        videoUplink.attachFaceRegistrationRenderer(videoView)
    }

    func update(mirrorsVideo: Bool) {
        videoView.transform = mirrorsVideo
            ? CGAffineTransform(scaleX: -1, y: 1)
            : .identity
    }

    func detach() {
        videoUplink?.detachFaceRegistrationRenderer(videoView)
        videoUplink = nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        videoView.frame = bounds
    }

    deinit {
        detach()
    }
}
