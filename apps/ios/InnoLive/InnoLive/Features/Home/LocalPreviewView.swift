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
        ZStack {
            CameraPreview(session: session, cameraID: cameraID)
        }
        // 영상도 테두리와 같은 둥근 모양으로 잘라냄
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(.gray.opacity(0.6), lineWidth: 1)
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
