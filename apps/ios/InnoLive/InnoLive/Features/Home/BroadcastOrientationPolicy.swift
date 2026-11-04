import AVFoundation
import CoreGraphics
import UIKit

nonisolated enum BroadcastInterfaceOrientation: Equatable, Sendable {
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

nonisolated enum BroadcastVideoRotation: Int, Equatable, Sendable {
    case rotation0 = 0
    case rotation90 = 90
    case rotation180 = 180
    case rotation270 = 270
}

nonisolated enum BroadcastOrientationPolicy {
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

    /// WebRTC `RTCCameraVideoCapturer`는 `UIDeviceOrientation`을 기준으로 회전을 정한다. 인터페이스의
    /// 가로 왼쪽·오른쪽은 기기의 가로 왼쪽·오른쪽과 반대다. 센서 장착 방향 때문에 가로에서는
    /// 전면·후면 카메라 값이 다르다.
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
        for interfaceOrientation: BroadcastInterfaceOrientation,
        cameraPosition: AVCaptureDevice.Position
    ) -> CGFloat {
        CGFloat(
            videoRotation(
                interfaceOrientation: interfaceOrientation,
                cameraPosition: cameraPosition
            ).rawValue
        )
    }

    /// 전면 카메라 미리보기에만 쓰는 화면 표시용 좌우 반전이다. 캡처 프레임은 반전하지 않는다.
    static func previewDisplayTransform(isUsingFrontCamera: Bool) -> CGAffineTransform {
        isUsingFrontCamera ? CGAffineTransform(scaleX: -1, y: 1) : .identity
    }
}
