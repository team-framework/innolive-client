import AVFoundation
import CoreGraphics

enum CameraZoom {
    static let defaultFactor: CGFloat = 1
    static let halfTimesFactor: CGFloat = 0.5
    static let accessibilityStep: CGFloat = 0.5
    static let fallbackMaxFactor: CGFloat = 16

    struct DeviceLimits: Equatable {
        var id: String
        var min: CGFloat
        var max: CGFloat
    }

    struct DevicePlan: Equatable {
        var deviceID: String
        var factor: CGFloat
    }

    static func clamped(_ factor: CGFloat, min: CGFloat, max: CGFloat) -> CGFloat {
        let lower = Swift.min(min, max)
        let upper = Swift.max(min, max)
        return Swift.min(Swift.max(factor, lower), upper)
    }

    static func factorAfterSwitchReset(min: CGFloat, max: CGFloat) -> CGFloat {
        clamped(defaultFactor, min: min, max: max)
    }

    static func factor(
        fromPinchStart start: CGFloat,
        magnification: CGFloat,
        min: CGFloat,
        max: CGFloat
    ) -> CGFloat {
        clamped(start * magnification, min: min, max: max)
    }

    static func requestedPinchFactor(fromPinchStart start: CGFloat, magnification: CGFloat) -> CGFloat {
        start * magnification
    }

    static func displayRange(
        hasHalfTimes: Bool,
        activeMin: CGFloat,
        activeMax: CGFloat
    ) -> ClosedRange<CGFloat> {
        let lower = hasHalfTimes ? min(halfTimesFactor, activeMin) : activeMin
        let upper = activeMax > lower ? activeMax : max(lower, fallbackMaxFactor)
        return lower...upper
    }

    static func steppedUp(from current: CGFloat, min: CGFloat, max: CGFloat) -> CGFloat {
        clamped(current + accessibilityStep, min: min, max: max)
    }

    static func steppedDown(from current: CGFloat, min: CGFloat, max: CGFloat) -> CGFloat {
        clamped(current - accessibilityStep, min: min, max: max)
    }

    static func plan(
        requestedFactor: CGFloat,
        currentDeviceID: String,
        wide: DeviceLimits?,
        virtual: DeviceLimits?
    ) -> DevicePlan {
        if requestedFactor < defaultFactor, let virtual {
            let virtualMax = virtual.max > virtual.min ? virtual.max : fallbackMaxFactor
            return DevicePlan(
                deviceID: virtual.id,
                factor: clamped(requestedFactor, min: virtual.min, max: virtualMax)
            )
        }
        if let wide {
            return DevicePlan(
                deviceID: wide.id,
                factor: clamped(requestedFactor, min: wide.min, max: wide.max)
            )
        }
        if let virtual {
            return DevicePlan(
                deviceID: virtual.id,
                factor: clamped(
                    max(requestedFactor, defaultFactor),
                    min: virtual.min,
                    max: virtual.max
                )
            )
        }
        return DevicePlan(deviceID: currentDeviceID, factor: requestedFactor)
    }
}

enum CameraDeviceZoom {
    struct Applied: Equatable {
        let factor: CGFloat
        let min: CGFloat
        let max: CGFloat
    }

    static func apply(_ factor: CGFloat, to device: AVCaptureDevice) -> Applied? {
        let minFactor = device.minAvailableVideoZoomFactor
        let maxFactor = device.maxAvailableVideoZoomFactor
        let clamped = CameraZoom.clamped(factor, min: minFactor, max: maxFactor)
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            if device.isRampingVideoZoom {
                device.cancelVideoZoomRamp()
            }
            device.videoZoomFactor = clamped
            return Applied(factor: clamped, min: minFactor, max: maxFactor)
        } catch {
            return nil
        }
    }
}
