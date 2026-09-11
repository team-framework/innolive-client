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

    static var devices: [AVCaptureDevice] {
        let discovered = AVCaptureDevice.DiscoverySession(
            deviceTypes: discoveryDeviceTypes,
            mediaType: .video,
            position: .unspecified
        ).devices
        return hidingConstituents(in: discovered)
    }

    static func hidingConstituents(in devices: [AVCaptureDevice]) -> [AVCaptureDevice] {
        let hiddenIDs = Set(devices.flatMap { $0.constituentDevices.map(\.uniqueID) })
        return devices.filter { !hiddenIDs.contains($0.uniqueID) }
    }

    static func visibleIDs(
        _ deviceIDs: [String],
        constituents: [String: [String]]
    ) -> [String] {
        let hidden = Set(constituents.values.flatMap { $0 })
        return deviceIDs.filter { !hidden.contains($0) }
    }

    static func resolvedCameraID(
        savedID: String,
        availableIDs: [String],
        constituents: [String: [String]]
    ) -> String {
        if availableIDs.contains(savedID) {
            return savedID
        }
        if let parentID = constituents.first(where: { $0.value.contains(savedID) })?.key,
           availableIDs.contains(parentID) {
            return parentID
        }
        return savedID
    }

    static func resolvedDevice(for cameraID: String) -> AVCaptureDevice? {
        let available = devices
        let constituents = Dictionary(uniqueKeysWithValues: available.map { device in
            (device.uniqueID, device.constituentDevices.map(\.uniqueID))
        })
        let resolvedID = resolvedCameraID(
            savedID: cameraID,
            availableIDs: available.map(\.uniqueID),
            constituents: constituents
        )
        return available.first(where: { $0.uniqueID == resolvedID })
            ?? AVCaptureDevice(uniqueID: cameraID)
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
