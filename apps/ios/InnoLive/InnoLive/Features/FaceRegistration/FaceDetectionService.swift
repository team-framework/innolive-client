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

/// One analyzed camera frame. The image is upright and mirrored like the preview.
nonisolated struct FaceSelectionFrame: @unchecked Sendable {
    let image: CIImage
    let faces: [FaceSelectionCandidate]

    var pixelSize: CGSize { image.extent.size }
}

nonisolated final class FaceDetectionService: @unchecked Sendable {
    private static let outputSize = 500
    // Fast enough for brackets to follow a face; Vision runs off the main thread.
    private static let selectionInterval: TimeInterval = 0.1

    private let context = CIContext(options: [.cacheIntermediates: false])
    private let analysisLock = NSLock()
    private var lastDetectionTime: TimeInterval = 0

    func reset() {
        analysisLock.lock()
        lastDetectionTime = 0
        analysisLock.unlock()
    }

    func detectFaces(
        sampleBuffer: CMSampleBuffer,
        cameraPosition: AVCaptureDevice.Position,
        deviceOrientation: UIDeviceOrientation
    ) -> FaceSelectionFrame? {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
        return detectFaces(
            pixelBuffer: pixelBuffer,
            cameraPosition: cameraPosition,
            deviceOrientation: deviceOrientation
        )
    }

    func detectFaces(
        pixelBuffer: CVPixelBuffer,
        cameraPosition: AVCaptureDevice.Position,
        deviceOrientation: UIDeviceOrientation
    ) -> FaceSelectionFrame? {
        analysisLock.lock()
        defer { analysisLock.unlock() }
        let now = ProcessInfo.processInfo.systemUptime
        guard now - lastDetectionTime >= Self.selectionInterval else { return nil }
        lastDetectionTime = now

        let orientation = imageOrientation(
            deviceOrientation: deviceOrientation,
            cameraPosition: cameraPosition
        )
        let request = Self.faceRectanglesRequest()
        do {
            try VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: orientation).perform([request])
        } catch {
            return nil
        }
        return selectionFrame(
            image: CIImage(cvPixelBuffer: pixelBuffer).oriented(orientation),
            observations: request.results ?? []
        )
    }

    func detectFaces(uprightImage image: CGImage) -> FaceSelectionFrame? {
        let request = Self.faceRectanglesRequest()
        do {
            try VNImageRequestHandler(cgImage: image, orientation: .up).perform([request])
        } catch {
            return nil
        }
        return selectionFrame(image: CIImage(cgImage: image), observations: request.results ?? [])
    }

    /// The frozen frame shown while the user names the selected face.
    func previewImage(from frame: FaceSelectionFrame) -> CGImage? {
        context.createCGImage(frame.image, from: frame.image.extent)
    }

    /// A 500 x 500 JPEG around the selected face with every other face blacked out.
    /// Server and on-device enrollment both reject images with more than one face.
    func registrationJPEG(from frame: FaceSelectionFrame, faceIndex: Int) -> Data? {
        guard var image = registrationImage(from: frame, faceIndex: faceIndex),
              var faces = faceRects(in: image) else { return nil }
        let center = CGPoint(x: 0.5, y: 0.5)
        // A face too small to find in the full frame can appear once the crop is enlarged.
        let others = faces.filter { !$0.contains(center) }
        if !others.isEmpty, let selected = faces.first(where: { $0.contains(center) }) {
            let size = CGSize(width: image.width, height: image.height)
            let remasked = masking(CIImage(cgImage: image), size: size, hiding: others, keeping: selected)
            guard let remaskedImage = context.createCGImage(remasked, from: CGRect(origin: .zero, size: size)),
                  let remaining = faceRects(in: remaskedImage) else { return nil }
            image = remaskedImage
            faces = remaining
        }
        guard faces.count == 1, faces[0].contains(center) else { return nil }
        return jpegData(from: image)
    }

    func registrationImage(from frame: FaceSelectionFrame, faceIndex: Int) -> CGImage? {
        let extent = frame.image.extent
        guard frame.faces.indices.contains(faceIndex),
              frame.faces[faceIndex].isSelectable,
              min(extent.width, extent.height) >= CGFloat(Self.outputSize) else { return nil }

        let size = extent.size
        let base = frame.image.transformed(by: CGAffineTransform(
            translationX: -extent.minX,
            y: -extent.minY
        ))
        let others = frame.faces.indices.filter { $0 != faceIndex }.map { frame.faces[$0].rect }
        let masked = masking(base, size: size, hiding: others, keeping: frame.faces[faceIndex].rect)

        let selected = FaceSelectionGeometry.pixelRect(for: frame.faces[faceIndex].rect, imageSize: size)
        let crop = FaceSelectionGeometry.registrationCropRect(around: selected)
        let scale = CGFloat(Self.outputSize) / crop.width
        // Areas outside the frame stay black, like the server's square face crop padding.
        let output = masked.composited(over: CIImage(color: .black))
            .cropped(to: crop)
            .transformed(by: CGAffineTransform(translationX: -crop.minX, y: -crop.minY))
            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        return context.createCGImage(
            output,
            from: CGRect(x: 0, y: 0, width: Self.outputSize, height: Self.outputSize)
        )
    }

    /// Blacks out the `hiding` faces and keeps the selected face intact.
    /// Rects are normalized with a top-left origin; `image` starts at the origin.
    private func masking(_ image: CIImage, size: CGSize, hiding: [CGRect], keeping: CGRect) -> CIImage {
        let black = CIImage(color: .black)
        var masked = image
        for face in hiding {
            let mask = FaceSelectionGeometry.pixelRect(
                for: face,
                imageSize: size,
                scale: FaceSelectionGeometry.maskedFaceScale
            )
            masked = black.cropped(to: mask).composited(over: masked)
        }
        // A neighbor's mask must not cover the selected face itself.
        let selected = FaceSelectionGeometry.pixelRect(for: keeping, imageSize: size)
        return image.cropped(to: selected).composited(over: masked)
    }

    private func faceRects(in image: CGImage) -> [CGRect]? {
        let request = Self.faceRectanglesRequest()
        do {
            try VNImageRequestHandler(cgImage: image, orientation: .up).perform([request])
        } catch {
            return nil
        }
        return (request.results ?? []).map(Self.topLeftRect)
    }

    private func selectionFrame(image: CIImage, observations: [VNFaceObservation]) -> FaceSelectionFrame {
        FaceSelectionFrame(
            image: image,
            faces: FaceSelectionGeometry.candidates(
                for: observations.map(Self.topLeftRect),
                imageSize: image.extent.size
            )
        )
    }

    // Vision uses a bottom-left origin; the preview overlay uses top-left.
    private static func topLeftRect(_ observation: VNFaceObservation) -> CGRect {
        let box = observation.boundingBox
        return CGRect(x: box.minX, y: 1 - box.maxY, width: box.width, height: box.height)
    }

    func analyzeUpright(image: CGImage) -> FaceDetectionOutcome {
        guard let crop = makeCenteredCrop(image: CIImage(cgImage: image)) else { return .failed }
        return analyzePreparedCrop(crop)
    }

    private func analyzePreparedCrop(_ crop: CGImage) -> FaceDetectionOutcome {
        let request = Self.faceRectanglesRequest()
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

    private func makeCenteredCrop(image orientedImage: CIImage) -> CGImage? {
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

    private static func faceRectanglesRequest() -> VNDetectFaceRectanglesRequest {
        let request = VNDetectFaceRectanglesRequest()
        #if targetEnvironment(simulator)
        // The Simulator cannot create Vision's default inference context.
        if let cpu = try? request.supportedComputeStageDevices[.main]?.first(where: {
            if case .cpu = $0 { return true }
            return false
        }) {
            request.setComputeDevice(cpu, for: .main)
        }
        #endif
        return request
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
