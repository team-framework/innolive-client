import AVFoundation

enum CameraDeviceCatalog {
    static let discoveryDeviceTypes: [AVCaptureDevice.DeviceType] = [
        .builtInTripleCamera,
        .builtInDualWideCamera,
        .builtInDualCamera,
        .builtInWideAngleCamera,
        .builtInUltraWideCamera,
        .builtInTelephotoCamera,
        .builtInTrueDepthCamera,
        .external
    ]

    private static let hiddenVirtualTypes: Set<AVCaptureDevice.DeviceType> = [
        .builtInTripleCamera,
        .builtInDualWideCamera,
        .builtInDualCamera
    ]

    static var discoveredDevices: [AVCaptureDevice] {
        AVCaptureDevice.DiscoverySession(
            deviceTypes: discoveryDeviceTypes,
            mediaType: .video,
            position: .unspecified
        ).devices
    }

    static var devices: [AVCaptureDevice] {
        hidingVirtualCameras(in: discoveredDevices)
    }

    static func hidingVirtualCameras(in devices: [AVCaptureDevice]) -> [AVCaptureDevice] {
        devices.filter { !hiddenVirtualTypes.contains($0.deviceType) }
    }

    static func visibleIDs(_ deviceIDs: [String], hiding hiddenIDs: [String]) -> [String] {
        let hidden = Set(hiddenIDs)
        return deviceIDs.filter { !hidden.contains($0) }
    }

    static func resolvedCameraID(
        savedID: String,
        availableIDs: [String],
        virtualIDs: [String: String]
    ) -> String {
        if let wideID = virtualIDs[savedID], availableIDs.contains(wideID) {
            return wideID
        }
        if availableIDs.contains(savedID) {
            return savedID
        }
        return savedID
    }

    static func resolvedDevice(for cameraID: String) -> AVCaptureDevice? {
        let visible = devices
        let virtualToWide = Dictionary(
            uniqueKeysWithValues: discoveredDevices.compactMap { device -> (String, String)? in
                guard hiddenVirtualTypes.contains(device.deviceType),
                      let wide = wideAngleDevice(position: device.position) else {
                    return nil
                }
                return (device.uniqueID, wide.uniqueID)
            }
        )
        let resolvedID = resolvedCameraID(
            savedID: cameraID,
            availableIDs: visible.map(\.uniqueID),
            virtualIDs: virtualToWide
        )
        return visible.first(where: { $0.uniqueID == resolvedID })
            ?? AVCaptureDevice(uniqueID: cameraID)
    }

    static func wideAngleDevice(position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        discoveredDevices.first {
            $0.position == position && $0.deviceType == .builtInWideAngleCamera
        }
    }

    static func virtualZoomDevice(position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        let preferredTypes: [AVCaptureDevice.DeviceType] = [
            .builtInTripleCamera,
            .builtInDualWideCamera,
            .builtInDualCamera
        ]
        for type in preferredTypes {
            if let device = discoveredDevices.first(where: { $0.position == position && $0.deviceType == type }) {
                return device
            }
        }
        return discoveredDevices.first {
            $0.position == position && $0.deviceType == .builtInUltraWideCamera
        }
    }

    static func zoomLimits(for device: AVCaptureDevice) -> CameraZoom.DeviceLimits {
        CameraZoom.DeviceLimits(
            id: device.uniqueID,
            min: device.minAvailableVideoZoomFactor,
            max: device.maxAvailableVideoZoomFactor
        )
    }

    static func expandedZoomRange(for device: AVCaptureDevice) -> ClosedRange<CGFloat> {
        let virtual = virtualZoomDevice(position: device.position)
        let minimum = min(
            device.minAvailableVideoZoomFactor,
            virtual?.minAvailableVideoZoomFactor ?? device.minAvailableVideoZoomFactor
        )
        let maximum = max(
            device.maxAvailableVideoZoomFactor,
            virtual?.maxAvailableVideoZoomFactor ?? device.maxAvailableVideoZoomFactor
        )
        return minimum...maximum
    }

    static func nextCamera(after currentCameraID: String?) -> AVCaptureDevice? {
        let availableDevices = devices
        guard !availableDevices.isEmpty else { return nil }
        let resolvedID = currentCameraID.flatMap { resolvedDevice(for: $0)?.uniqueID }
            ?? currentCameraID
        guard let resolvedID,
              let currentDevice = availableDevices.first(where: { $0.uniqueID == resolvedID }) else {
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
            $0.uniqueID == resolvedID
        }) else {
            return availableDevices.first
        }
        let nextIndex = availableDevices.index(after: currentIndex)
        return nextIndex < availableDevices.endIndex
            ? availableDevices[nextIndex]
            : availableDevices.first
    }
}
