//
//  CameraManager.swift
//  InnoLive
//
//  Created by chaeyn on 7/29/26.
//

import AVFoundation
import Observation

@Observable
final class CameraManager {
    let session = AVCaptureSession()

    // private(set): 읽기는 어디서나 가능, 갑 변경은 이 class 내부에서만 가능
    private(set) var authorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)

    // 현재 session에 연결된 영상 카메라 입력을 기억함
    private var videoInput: AVCaptureDeviceInput?

    // 아직 권한을 요청하지 않았다면 iOS 시스템 권한 팝업을 띄움
    func requestCameraAccess() {
        switch authorizationStatus {
        case .authorized:
            return

        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    self?.authorizationStatus = granted ? .authorized : .denied
                }
            }

        case .denied, .restricted:
            return

        @unknown default:
            return
        }
    }

    // 설정 화면에서 선택한 id의 카메라를 찾음
    func cameraDevice(for cameraID: String) -> AVCaptureDevice? {
        AVCaptureDevice(uniqueID: cameraID)
    }

    func addCameraInput(for cameraID: String) {
        guard videoInput == nil else {
            return
        }

        guard let device = cameraDevice(for: cameraID) else {
            return
        }

        do {
            // AVCaptureDeviceInput: 카메라와 session을 연결하는 객체
            let input = try AVCaptureDeviceInput(device: device)

            guard session.canAddInput(input) else {
                return
            }

            session.addInput(input)
            videoInput = input
        } catch {
            print("카메라를 연결하지 못했습니다: \(error.localizedDescription)")
        }
    }

    // 연결된 카메라로 실시간 영상 시작
    func startSession() {
        guard !session.isRunning else {
            return
        }

        session.startRunning()
    }

    // 앱을 처음 열었을 때 전면 카메라를 연결하고 시작
    func startDefaultCamera() {
        guard let frontCamera = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .front
        ) else {
            return
        }

        addCameraInput(for: frontCamera.uniqueID)
        startSession()
    }
}
