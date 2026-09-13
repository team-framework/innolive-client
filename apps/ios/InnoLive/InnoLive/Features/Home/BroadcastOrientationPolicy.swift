import AVFoundation
import CoreGraphics
import UIKit

enum BroadcastInterfaceOrientation: Equatable, Sendable {
    case portrait
    case portraitUpsideDown
    case landscapeLeft
    case landscapeRight

    var isLandscape: Bool {
        switch self {
        case .landscapeLeft, .landscapeRight: return true
        case .portrait, .portraitUpsideDown: return false
        }
    }

    init?(interfaceOrientation: UIInterfaceOrientation) {
        switch interfaceOrientation {
        case .portrait: self = .portrait
        case .portraitUpsideDown: self = .portraitUpsideDown
        case .landscapeLeft: self = .landscapeLeft
        case .landscapeRight: self = .landscapeRight
        case .unknown: return nil
        @unknown default: return nil
        }
    }

    var uiInterfaceOrientation: UIInterfaceOrientation {
        switch self {
        case .portrait: return .portrait
        case .portraitUpsideDown: return .portraitUpsideDown
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        }
    }

    var mask: UIInterfaceOrientationMask {
        switch self {
        case .portrait: return .portrait
        case .portraitUpsideDown: return .portraitUpsideDown
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        }
    }
}

enum BroadcastVideoRotation: Int, Equatable, Sendable {
    case rotation0 = 0
    case rotation90 = 90
    case rotation180 = 180
    case rotation270 = 270
}

enum BroadcastOrientationLockEvent: Equatable {
    case homeAppeared
    case cameraUplinkConnected
    case prepareRequested
    case preparedWaiting
    case goLiveRequested
    case goLiveRetryInOngoingOperation
    case goLiveFailedBackToPrepared
    case live
    case paused
    case reconnecting
    case stopping
    case stopFailedWhileBroadcasting
    case stopSucceeded
    case reset
    case finalFailure
}

enum BroadcastOrientationLockAction: Equatable {
    case none
    case lockToCurrentIfUnlocked
    case keep
    case release
}

enum BroadcastOrientationPolicy {
    static let previewShortSide: CGFloat = 120
    static let previewLongSide: CGFloat = previewShortSide / (9.0 / 16.0)

    static func previewSize(isLandscape: Bool) -> CGSize {
        isLandscape
            ? CGSize(width: previewLongSide, height: previewShortSide)
            : CGSize(width: previewShortSide, height: previewLongSide)
    }

    static func previewSize(
        containerSize: CGSize,
        lockedOrientation: BroadcastInterfaceOrientation?
    ) -> CGSize {
        if let lockedOrientation {
            return previewSize(isLandscape: lockedOrientation.isLandscape)
        }
        return previewSize(isLandscape: containerSize.width > containerSize.height)
    }

    static func defaultSupportedMask(idiom: UIUserInterfaceIdiom) -> UIInterfaceOrientationMask {
        if idiom == .pad {
            return [.portrait, .portraitUpsideDown, .landscapeLeft, .landscapeRight]
        }
        return [.portrait, .landscapeLeft, .landscapeRight]
    }

    static func supportedMask(
        lockedOrientation: BroadcastInterfaceOrientation?,
        idiom: UIUserInterfaceIdiom
    ) -> UIInterfaceOrientationMask {
        guard let lockedOrientation else {
            return defaultSupportedMask(idiom: idiom)
        }
        if lockedOrientation == .portraitUpsideDown, idiom != .pad {
            return .portrait
        }
        return lockedOrientation.mask
    }

    static func prefersInterfaceOrientationLocked(
        _ lockedOrientation: BroadcastInterfaceOrientation?
    ) -> Bool {
        lockedOrientation != nil
    }

    /// WebRTC `RTCCameraVideoCapturer` maps `UIDeviceOrientation`. Interface landscape
    /// left/right is the inverse of device landscape left/right.
    static func videoRotation(
        interfaceOrientation: BroadcastInterfaceOrientation,
        cameraPosition: AVCaptureDevice.Position
    ) -> BroadcastVideoRotation {
        let usingFrontCamera = cameraPosition == .front
        switch interfaceOrientation {
        case .portrait:
            return .rotation90
        case .portraitUpsideDown:
            return .rotation270
        case .landscapeLeft:
            return usingFrontCamera ? .rotation0 : .rotation180
        case .landscapeRight:
            return usingFrontCamera ? .rotation180 : .rotation0
        }
    }

    static func previewRotationAngle(
        for interfaceOrientation: BroadcastInterfaceOrientation
    ) -> CGFloat {
        switch interfaceOrientation {
        case .portrait: return 90
        case .portraitUpsideDown: return 270
        case .landscapeLeft: return 0
        case .landscapeRight: return 180
        }
    }

    static func action(for event: BroadcastOrientationLockEvent) -> BroadcastOrientationLockAction {
        switch event {
        case .homeAppeared, .cameraUplinkConnected, .prepareRequested, .preparedWaiting:
            return .none
        case .goLiveRequested:
            return .lockToCurrentIfUnlocked
        case .goLiveRetryInOngoingOperation, .live, .paused, .reconnecting, .stopping,
             .stopFailedWhileBroadcasting:
            return .keep
        case .goLiveFailedBackToPrepared, .stopSucceeded, .reset, .finalFailure:
            return .release
        }
    }
}
