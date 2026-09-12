import AVFoundation

enum CameraDeviceCatalog {
    struct Source: Equatable {
        var id: String
        var position: Position
        var isBuiltInWideAngle: Bool

        enum Position: Equatable {
            case front
            case back
            case unspecified

            init(_ position: AVCaptureDevice.Position) {
                switch position {
                case .front: self = .front
                case .back: self = .back
                default: self = .unspecified
                }
            }

            var opposite: Position? {
                switch self {
                case .front: .back
                case .back: .front
                case .unspecified: nil
                }
            }
        }

        init(id: String, position: Position, isBuiltInWideAngle: Bool) {
            self.id = id
            self.position = position
            self.isBuiltInWideAngle = isBuiltInWideAngle
        }

        init(device: AVCaptureDevice) {
            self.init(
                id: device.uniqueID,
                position: Position(device.position),
                isBuiltInWideAngle: device.deviceType == .builtInWideAngleCamera
            )
        }
    }

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
        let hasHalfTimes = virtualZoomDevice(position: device.position) != nil
            || device.minAvailableVideoZoomFactor < CameraZoom.defaultFactor
        return CameraZoom.displayRange(
            hasHalfTimes: hasHalfTimes,
            activeMin: device.minAvailableVideoZoomFactor,
            activeMax: device.maxAvailableVideoZoomFactor
        )
    }

    static func nextCamera(after currentCameraID: String?) -> AVCaptureDevice? {
        let availableDevices = devices
        let resolvedID = currentCameraID.flatMap { resolvedDevice(for: $0)?.uniqueID }
            ?? currentCameraID
        guard let nextID = nextCameraID(
            after: resolvedID,
            in: availableDevices.map { Source(device: $0) }
        ) else {
            return nil
        }
        return availableDevices.first { $0.uniqueID == nextID }
    }

    static func nextCameraID(after currentID: String?, in sources: [Source]) -> String? {
        guard sources.count >= 2 else { return nil }

        guard let currentID,
              let current = sources.first(where: { $0.id == currentID }) else {
            if let currentID {
                return sources.first { $0.id != currentID }?.id
            }
            return nil
        }

        if let opposite = current.position.opposite {
            if let wide = sources.first(where: {
                $0.position == opposite && $0.isBuiltInWideAngle
            }) {
                return wide.id
            }
            if let oppositeCamera = sources.first(where: {
                $0.position == opposite && $0.id != current.id
            }) {
                return oppositeCamera.id
            }
        }

        guard let currentIndex = sources.firstIndex(where: { $0.id == current.id }) else {
            return nil
        }
        let nextIndex = sources.index(after: currentIndex)
        let candidate = nextIndex < sources.endIndex
            ? sources[nextIndex]
            : sources[sources.startIndex]
        return candidate.id != current.id ? candidate.id : nil
    }
}
