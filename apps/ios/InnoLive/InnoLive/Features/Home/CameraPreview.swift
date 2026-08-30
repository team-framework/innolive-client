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
    // 현재 프리뷰에 연결된 카메라를 찾아 회전값을 계산하는 데 사용함
    let cameraID: String?
    let videoGravity: AVLayerVideoGravity

    init(
        session: AVCaptureSession,
        cameraID: String?,
        videoGravity: AVLayerVideoGravity = .resizeAspect
    ) {
        self.session = session
        self.cameraID = cameraID
        self.videoGravity = videoGravity
    }

    func makeUIView(context: Context) -> PreviewView {
        let previewView = PreviewView()
        previewView.configure(session: session, cameraID: cameraID, videoGravity: videoGravity)
        return previewView
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {
        // UI가 다시 렌더링 될 때도 같은 session을 유지
        uiView.configure(session: session, cameraID: cameraID, videoGravity: videoGravity)
    }

    static func dismantleUIView(_ uiView: PreviewView, coordinator: Void) {
        uiView.detachSession()
    }
}

final class PreviewView: UIView {
    override class var layerClass: AnyClass {
        AVCaptureVideoPreviewLayer.self
    }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private var rotationObservation: NSKeyValueObservation?
    private var observedCameraID: String?

    func configure(
        session: AVCaptureSession,
        cameraID: String?,
        videoGravity: AVLayerVideoGravity
    ) {
        previewLayer.session = session
        previewLayer.videoGravity = videoGravity

        // 같은 카메라는 중복 등록을 막음
        guard observedCameraID != cameraID else {
            return
        }

        observedCameraID = cameraID
        // 카메라가 바뀌면 이전 카메라의 회전 관찰을 해제함
        rotationObservation?.invalidate()
        rotationObservation = nil
        rotationCoordinator = nil

        // 사용중인 실제 카메라를 찾지 못하면 회전을 설정하지 못하도록 막음
        guard let cameraID,
              let device = AVCaptureDevice(uniqueID: cameraID)
        else {
            return
        }

        let coordinator = AVCaptureDevice.RotationCoordinator(
            device: device,
            previewLayer: previewLayer
        )
        rotationCoordinator = coordinator
        rotationObservation = coordinator.observe(
            \.videoRotationAngleForHorizonLevelPreview,
            options: [.initial, .new]
        ) { [weak self] coordinator, _ in
            DispatchQueue.main.async {
                // 기기 방향에 맞는 각도를 적용
                let rotationAngle = coordinator.videoRotationAngleForHorizonLevelPreview
                guard let connection = self?.previewLayer.connection,
                      connection.isVideoRotationAngleSupported(rotationAngle)
                else {
                    return
                }

                connection.videoRotationAngle = rotationAngle
            }
        }
    }

    func detachSession() {
        rotationObservation?.invalidate()
        rotationObservation = nil
        rotationCoordinator = nil
        observedCameraID = nil
        previewLayer.session = nil
    }

    deinit {
        detachSession()
    }
}
