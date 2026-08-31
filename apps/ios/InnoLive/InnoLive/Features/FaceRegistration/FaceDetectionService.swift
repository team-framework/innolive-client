@preconcurrency import AVFoundation
import CoreImage
import Foundation
import ImageIO
import UIKit
import Vision

enum FaceDetectionOutcome: Sendable {
    case noFace
    case multipleFaces
    case moveCloser
    case centerFace
    case ready(Data)
    case failed
}

nonisolated final class FaceDetectionService: @unchecked Sendable {
    private static let outputSize = 500
    private static let detectionInterval: TimeInterval = 0.35

    private let context = CIContext(options: [.cacheIntermediates: false])
    private let analysisLock = NSLock()
    private var lastDetectionTime: TimeInterval = 0

    func reset() {
        analysisLock.lock()
        lastDetectionTime = 0
        analysisLock.unlock()
    }

    func analyze(
        sampleBuffer: CMSampleBuffer,
        cameraPosition: AVCaptureDevice.Position,
        deviceOrientation: UIDeviceOrientation
    ) -> FaceDetectionOutcome? {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return .failed
        }
        return analyze(
            pixelBuffer: pixelBuffer,
            cameraPosition: cameraPosition,
            deviceOrientation: deviceOrientation
        )
    }

    func analyze(
        pixelBuffer: CVPixelBuffer,
        cameraPosition: AVCaptureDevice.Position,
        deviceOrientation: UIDeviceOrientation
    ) -> FaceDetectionOutcome? {
        analysisLock.lock()
        defer { analysisLock.unlock() }
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastDetectionTime >= Self.detectionInterval else { return nil }
        lastDetectionTime = now

        guard let crop = makeCenteredCrop(
            pixelBuffer: pixelBuffer,
            orientation: imageOrientation(
                deviceOrientation: deviceOrientation,
                cameraPosition: cameraPosition
            )
        ) else {
            return .failed
        }

        let request = VNDetectFaceRectanglesRequest()
        do {
            try VNImageRequestHandler(cgImage: crop, orientation: .up).perform([request])
        } catch {
            return .failed
        }

        guard let faces = request.results else { return .failed }
        guard faces.count == 1, let face = faces.first else {
            return faces.isEmpty ? .noFace : .multipleFaces
        }

        let bounds = face.boundingBox
        guard bounds.width >= 0.24, bounds.height >= 0.24 else {
            return .moveCloser
        }

        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        guard abs(center.x - 0.5) <= 0.16,
              abs(center.y - 0.5) <= 0.18,
              bounds.minX >= 0.04,
              bounds.maxX <= 0.96,
              bounds.minY >= 0.04,
              bounds.maxY <= 0.96 else {
            return .centerFace
        }

        guard let jpegData = jpegData(from: crop) else { return .failed }
        return .ready(jpegData)
    }

    private func makeCenteredCrop(
        pixelBuffer: CVPixelBuffer,
        orientation: CGImagePropertyOrientation
    ) -> CGImage? {
        let orientedImage = CIImage(cvPixelBuffer: pixelBuffer).oriented(orientation)
        let extent = orientedImage.extent.integral
        let side = min(extent.width, extent.height)
        guard side >= CGFloat(Self.outputSize) else { return nil }

        let cropRect = CGRect(
            x: extent.midX - side / 2,
            y: extent.midY - side / 2,
            width: side,
            height: side
        )
        let normalized = orientedImage
            .cropped(to: cropRect)
            .transformed(by: CGAffineTransform(
                translationX: -cropRect.minX,
                y: -cropRect.minY
            ))
        let scale = CGFloat(Self.outputSize) / side
        let resized = normalized.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        return context.createCGImage(
            resized,
            from: CGRect(x: 0, y: 0, width: Self.outputSize, height: Self.outputSize)
        )
    }

    private func jpegData(from image: CGImage) -> Data? {
        UIImage(cgImage: image).jpegData(compressionQuality: 0.9)
    }

    private func imageOrientation(
        deviceOrientation: UIDeviceOrientation,
        cameraPosition: AVCaptureDevice.Position
    ) -> CGImagePropertyOrientation {
        switch deviceOrientation {
        case .portraitUpsideDown:
            return cameraPosition == .front ? .rightMirrored : .left
        case .landscapeLeft:
            return cameraPosition == .front ? .downMirrored : .up
        case .landscapeRight:
            return cameraPosition == .front ? .upMirrored : .down
        case .portrait, .faceUp, .faceDown, .unknown:
            return cameraPosition == .front ? .leftMirrored : .right
        @unknown default:
            return cameraPosition == .front ? .leftMirrored : .right
        }
    }
}
