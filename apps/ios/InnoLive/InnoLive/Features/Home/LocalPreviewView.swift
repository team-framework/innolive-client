//
//  LocalPreviewView.swift
//  InnoLive
//

import AVFoundation
import SwiftUI

struct LocalPreviewView: View {
    let session: AVCaptureSession
    let cameraID: String?
    var contentScale: CGFloat = 1

    var body: some View {
        OriginalPreviewFrame(contentScale: contentScale) {
            CameraPreview(session: session, cameraID: cameraID)
        }
    }
}

struct OriginalPreviewFrame<Content: View>: View {
    let content: Content
    let contentScale: CGFloat

    init(contentScale: CGFloat = 1, @ViewBuilder content: () -> Content) {
        self.contentScale = contentScale
        self.content = content()
    }

    var body: some View {
        content
            .scaleEffect(contentScale)
            // 영상도 테두리와 같은 둥근 모양으로 잘라냄
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(.gray.opacity(0.6), lineWidth: 1)
            }
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(String(localized: "원본 미리보기"))
            .accessibilityHint(String(localized: "끌어다 놓으면 가까운 모서리에 붙습니다"))
            .accessibilityAddTraits(.allowsDirectInteraction)
    }
}

struct WebRTCColorPreviewFrame: View {
    @ObservedObject var uplink: WebRTCVideoUplink
    var contentScale: CGFloat = 1

    var body: some View {
        OriginalPreviewFrame(contentScale: contentScale) {
            WebRTCLocalPreviewView(uplink: uplink)
        }
    }
}

#Preview {
    LocalPreviewView(session: AVCaptureSession(), cameraID: nil)
        .frame(
            width: BroadcastVideoLayout.previewWidth,
            height: BroadcastVideoLayout.previewHeight
        )
}
