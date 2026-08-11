import AVFoundation

enum CameraDeviceCatalog {
    static var devices: [AVCaptureDevice] {
        AVCaptureDevice.DiscoverySession(
            deviceTypes: [
                .builtInTelephotoCamera,
                .builtInWideAngleCamera,
                .external
            ],
            mediaType: .video,
            position: .unspecified
        ).devices
    }

    static func nextCamera(after currentCameraID: String?) -> AVCaptureDevice? {
        let availableDevices = devices
        guard !availableDevices.isEmpty else { return nil }
        guard let currentCameraID,
              let currentDevice = availableDevices.first(where: { $0.uniqueID == currentCameraID }) else {
            return availableDevices.first
        }

        let oppositePosition: AVCaptureDevice.Position? = switch currentDevice.position {
        case .front: .back
        case .back: .front
        case .unspecified: nil
        @unknown default: nil
        }

        if let oppositePosition,
           let oppositeCamera = availableDevices.first(where: { $0.position == oppositePosition }) {
            return oppositeCamera
        }

        guard let currentIndex = availableDevices.firstIndex(where: {
            $0.uniqueID == currentCameraID
        }) else {
            return availableDevices.first
        }
        let nextIndex = availableDevices.index(after: currentIndex)
        return nextIndex < availableDevices.endIndex
            ? availableDevices[nextIndex]
            : availableDevices.first
    }
}
