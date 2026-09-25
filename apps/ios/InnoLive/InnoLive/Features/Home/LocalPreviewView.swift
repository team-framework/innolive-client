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
    let caption: String?
    let content: Content

    init(caption: String? = nil, @ViewBuilder content: () -> Content) {
        self.caption = caption
        self.content = content()
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            content
            if let caption {
                Text(caption)
                    .font(.system(size: 10, weight: .semibold))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 4)
                    .background(.black.opacity(0.72), in: .capsule)
                    .padding(5)
                    .allowsHitTesting(false)
            }
        }
            // 영상도 테두리와 같은 둥근 모양으로 잘라냄
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(.gray.opacity(0.6), lineWidth: 1)
            }
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(caption ?? String(localized: "원본 미리보기"))
            .accessibilityHint(String(localized: "끌어다 놓으면 가까운 모서리에 붙습니다"))
            .accessibilityAddTraits(.allowsDirectInteraction)
    }
}

struct WebRTCColorPreviewFrame: View {
    @ObservedObject var uplink: WebRTCVideoUplink
    let isOnDeviceProcessing: Bool

    private var caption: String? {
        let settings = uplink.videoQualitySettings
        guard settings.warmth != 0 || settings.saturation != 1 else { return nil }
        return isOnDeviceProcessing
            ? String(localized: "색감 미리보기 · 비식별화 전")
            : String(localized: "색감 미리보기")
    }

    var body: some View {
        OriginalPreviewFrame(caption: caption) {
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
