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
    private var videoInput: AVCaptureDeviceInput?
    private(set) var currentCameraID: String?

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
            currentCameraID = cameraID
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

    func switchCamera(to cameraID: String) {
        // 이미 같은 카메라를 쓰고 있으면 다시 연결하지 않음
        guard videoInput?.device.uniqueID != cameraID,
              let device = cameraDevice(for: cameraID)
        else {
            return
        }

        do {
            let newInput = try AVCaptureDeviceInput(device: device)
            let previousInput = videoInput

            // 입력을 교체하는 동안 session의 중간 상태가 화면에 보이지 않게 함
            session.beginConfiguration()
            defer { session.commitConfiguration() }

            if let previousInput {
                session.removeInput(previousInput)
            }

            guard session.canAddInput(newInput) else {
                // 새 카메라를 추가할 수 없으면 이전 카메라를 다시 연결함
                if let previousInput, session.canAddInput(previousInput) {
                    session.addInput(previousInput)
                }
                return
            }

            session.addInput(newInput)
            videoInput = newInput
            currentCameraID = cameraID
        } catch {
            print("카메라를 변경하지 못했습니다: \(error.localizedDescription)")
        }
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
