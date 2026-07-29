//
//  CameraPreview.swift
//  InnoLive
//
//  Created by chaeyn on 7/29/26.
//

import AVFoundation
import SwiftUI
import UIKit

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let previewView = PreviewView()
        previewView.previewLayer.session = session
        previewView.previewLayer.videoGravity = .resizeAspectFill
        return previewView
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        // UI가 다시 렌더링 될 때도 같은 session을 유지
        uiView.previewLayer.session = session
    }
}

final class PreviewView: UIView {
    override class var layerClass: AnyClass {
        AVCaptureVideoPreviewLayer.self
    }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }
}
