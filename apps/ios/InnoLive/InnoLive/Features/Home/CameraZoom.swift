import AVFoundation
import CoreGraphics

enum CameraZoom {
    static let defaultFactor: CGFloat = 1
    static let accessibilityStep: CGFloat = 0.5

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

    static func steppedUp(from current: CGFloat, min: CGFloat, max: CGFloat) -> CGFloat {
        clamped(current + accessibilityStep, min: min, max: max)
    }

    static func steppedDown(from current: CGFloat, min: CGFloat, max: CGFloat) -> CGFloat {
        clamped(current - accessibilityStep, min: min, max: max)
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
