import AVFoundation
import Foundation

nonisolated struct BroadcastVideoQualitySettings: Equatable {
    var stabilizationEnabled = true
    var exposureEV: Float = 0
    var warmth: Float = 0
    var saturation: Float = 1

    private enum Key {
        static let stabilization = "broadcastVideoQuality.stabilizationEnabled"
        static let exposure = "broadcastVideoQuality.exposureEV"
        static let warmth = "broadcastVideoQuality.warmth"
        static let saturation = "broadcastVideoQuality.saturation"
    }

    init(
        stabilizationEnabled: Bool = true,
        exposureEV: Float = 0,
        warmth: Float = 0,
        saturation: Float = 1
    ) {
        self.stabilizationEnabled = stabilizationEnabled
        self.exposureEV = Self.clamped(exposureEV, min: -2, max: 2, fallback: 0)
        self.warmth = Self.clamped(warmth, min: -1, max: 1, fallback: 0)
        self.saturation = Self.clamped(saturation, min: 0, max: 2, fallback: 1)
    }

    static func load(from defaults: UserDefaults = .standard) -> Self {
        Self(
            stabilizationEnabled: defaults.object(forKey: Key.stabilization) as? Bool ?? true,
            exposureEV: defaults.object(forKey: Key.exposure) as? Float ?? 0,
            warmth: defaults.object(forKey: Key.warmth) as? Float ?? 0,
            saturation: defaults.object(forKey: Key.saturation) as? Float ?? 1
        )
    }

    func save(to defaults: UserDefaults = .standard) {
        defaults.set(stabilizationEnabled, forKey: Key.stabilization)
        defaults.set(exposureEV, forKey: Key.exposure)
        defaults.set(warmth, forKey: Key.warmth)
        defaults.set(saturation, forKey: Key.saturation)
    }

    private static func clamped(_ value: Float, min lower: Float, max upper: Float, fallback: Float) -> Float {
        guard value.isFinite else { return fallback }
        return Swift.min(Swift.max(value, lower), upper)
    }
}

nonisolated enum VideoStabilizationStatus: Equatable {
    case inactive
    case active(AVCaptureVideoStabilizationMode)
    case unsupported
}

nonisolated enum VideoQualityCapturePolicy {
    static func stabilizationMode(
        enabled: Bool,
        supportsLowLatency: Bool,
        supportsStandard: Bool
    ) -> AVCaptureVideoStabilizationMode? {
        guard enabled else { return .off }
        if #available(iOS 26, *), supportsLowLatency { return .lowLatency }
        if supportsStandard { return .standard }
        return nil
    }

    static func stabilizationStatus(
        enabled: Bool,
        requestedMode: AVCaptureVideoStabilizationMode?,
        activeMode: AVCaptureVideoStabilizationMode
    ) -> VideoStabilizationStatus {
        guard enabled else { return .inactive }
        guard requestedMode != nil, activeMode != .off else { return .unsupported }
        return .active(activeMode)
    }

    static func clampedExposureEV(_ requested: Float, min deviceMin: Float, max deviceMax: Float) -> Float {
        guard requested.isFinite, deviceMin.isFinite, deviceMax.isFinite, deviceMin <= deviceMax else {
            return 0
        }
        let lower = Swift.max(-2, deviceMin)
        let upper = Swift.min(2, deviceMax)
        guard lower <= upper else { return 0 }
        return Swift.min(Swift.max(requested, lower), upper)
    }
}
