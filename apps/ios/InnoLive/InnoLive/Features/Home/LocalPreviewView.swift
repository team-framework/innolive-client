//
//  LocalPreviewView.swift
//  InnoLive
//

import AVFoundation
import SwiftUI

struct LocalPreviewView: View {
    let session: AVCaptureSession
    let cameraID: String?

    var body: some View {
        OriginalPreviewFrame {
            CameraPreview(session: session, cameraID: cameraID)
        }
    }
}

struct OriginalPreviewFrame<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
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

#Preview {
    LocalPreviewView(session: AVCaptureSession(), cameraID: nil)
        .frame(
            width: BroadcastVideoLayout.previewWidth,
            height: BroadcastVideoLayout.previewHeight
        )
}
