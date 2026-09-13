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
    private var orientationObserver: NSObjectProtocol?
    private var isLockedToBroadcastOrientation = false

    func configure(
        session: AVCaptureSession,
        cameraID: String?,
        videoGravity: AVLayerVideoGravity
    ) {
        isUserInteractionEnabled = false
        previewLayer.session = session
        previewLayer.videoGravity = videoGravity
        startObservingBroadcastOrientationIfNeeded()

        if observedCameraID != cameraID {
            observedCameraID = cameraID
            tearDownCoordinator()
        }

        applyRotationPolicy()
    }

    func detachSession() {
        if let orientationObserver {
            NotificationCenter.default.removeObserver(orientationObserver)
            self.orientationObserver = nil
        }
        tearDownCoordinator()
        observedCameraID = nil
        isLockedToBroadcastOrientation = false
        previewLayer.session = nil
    }

    private func startObservingBroadcastOrientationIfNeeded() {
        guard orientationObserver == nil else { return }
        orientationObserver = NotificationCenter.default.addObserver(
            forName: .broadcastOrientationDidChange,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.applyRotationPolicy()
        }
    }

    private func applyRotationPolicy() {
        if let locked = BroadcastOrientationController.shared.lockedOrientation {
            tearDownCoordinator()
            isLockedToBroadcastOrientation = true
            applyPreviewAngle(BroadcastOrientationPolicy.previewRotationAngle(for: locked))
            return
        }

        let shouldRestartCoordinator = isLockedToBroadcastOrientation || rotationCoordinator == nil
        isLockedToBroadcastOrientation = false
        guard shouldRestartCoordinator else { return }
        startCoordinator()
    }

    private func startCoordinator() {
        tearDownCoordinator()
        guard let cameraID = observedCameraID,
              let device = AVCaptureDevice(uniqueID: cameraID) else {
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
                guard let self, !self.isLockedToBroadcastOrientation else { return }
                self.applyPreviewAngle(coordinator.videoRotationAngleForHorizonLevelPreview)
            }
        }
    }

    private func applyPreviewAngle(_ rotationAngle: CGFloat) {
        guard let connection = previewLayer.connection,
              connection.isVideoRotationAngleSupported(rotationAngle)
        else {
            return
        }
        connection.videoRotationAngle = rotationAngle
    }

    private func tearDownCoordinator() {
        rotationObservation?.invalidate()
        rotationObservation = nil
        rotationCoordinator = nil
    }

    deinit {
        detachSession()
    }
}
