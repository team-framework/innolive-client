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

/// 분석한 카메라 프레임 하나다. 이미지는 미리보기와 똑같이 바로 세우고 좌우 반전돼 있다.
nonisolated struct FaceSelectionFrame: @unchecked Sendable {
    let image: CIImage
    let faces: [FaceSelectionCandidate]

    var pixelSize: CGSize { image.extent.size }
}

nonisolated final class FaceDetectionService: @unchecked Sendable {
    private static let outputSize = 500
    private static let detectionInterval: TimeInterval = 0.35
    // 브래킷이 얼굴을 따라갈 만큼 빠르다. Vision은 메인 스레드 밖에서 돈다.
    private static let selectionInterval: TimeInterval = 0.1

    private let context = CIContext(options: [.cacheIntermediates: false])
    private let analysisLock = NSLock()
    private var lastDetectionTime: TimeInterval = 0
    private var previewAspectRatio = FaceSelectionGeometry.previewAspectRatio

    func reset() {
        analysisLock.lock()
        lastDetectionTime = 0
        analysisLock.unlock()
    }

    /// aspect-fill 미리보기의 너비 / 높이다. 사용자가 화면에서 볼 수 있는 얼굴만 고를 수 있게 한다.
    func setPreviewAspectRatio(_ ratio: CGFloat) {
        guard ratio.isFinite, ratio > 0 else { return }
        analysisLock.lock()
        previewAspectRatio = ratio
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

        return analyzePreparedCrop(crop)
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
        let request = Self.faceLandmarksRequest()
        do {
            try VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: orientation).perform([request])
        } catch {
            return nil
        }
        return selectionFrame(
            image: CIImage(cvPixelBuffer: pixelBuffer).oriented(orientation),
            observations: request.results ?? [],
            previewAspectRatio: previewAspectRatio
        )
    }

    func detectFaces(uprightImage image: CGImage) -> FaceSelectionFrame? {
        let request = Self.faceLandmarksRequest()
        do {
            try VNImageRequestHandler(cgImage: image, orientation: .up).perform([request])
        } catch {
            return nil
        }
        analysisLock.lock()
        let previewAspectRatio = previewAspectRatio
        analysisLock.unlock()
        return selectionFrame(
            image: CIImage(cgImage: image),
            observations: request.results ?? [],
            previewAspectRatio: previewAspectRatio
        )
    }

    /// 선택한 얼굴을 중심으로 자른 500 x 500 JPEG이며 다른 얼굴은 모두 검게 가린다.
    /// 서버 등록과 온디바이스 등록 모두 얼굴이 둘 이상인 이미지는 거부한다.
    func registrationJPEG(from frame: FaceSelectionFrame, faceIndex: Int) -> Data? {
        guard var image = registrationImage(from: frame, faceIndex: faceIndex),
              var faces = faceRects(in: image) else { return nil }
        let center = CGPoint(x: 0.5, y: 0.5)
        // 전체 프레임에서는 너무 작아 못 찾은 얼굴이 crop을 확대하면 잡힐 수 있다.
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
        // 프레임 밖 영역은 서버의 정사각형 얼굴 crop 여백처럼 검게 둔다.
        let output = masked.composited(over: CIImage(color: .black))
            .cropped(to: crop)
            .transformed(by: CGAffineTransform(translationX: -crop.minX, y: -crop.minY))
            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        return context.createCGImage(
            output,
            from: CGRect(x: 0, y: 0, width: Self.outputSize, height: Self.outputSize)
        )
    }

    /// `hiding` 얼굴은 검게 가리고 선택한 얼굴은 그대로 둔다.
    /// 사각형은 왼쪽 위 원점 기준으로 정규화돼 있고 `image`는 원점에서 시작한다.
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
        // 옆 사람을 가린 마스크가 선택한 얼굴 자체를 덮으면 안 된다.
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

    private func selectionFrame(
        image: CIImage,
        observations: [VNFaceObservation],
        previewAspectRatio: CGFloat
    ) -> FaceSelectionFrame {
        let candidates = FaceSelectionGeometry.candidates(
            for: observations.map(Self.topLeftRect),
            imageSize: image.extent.size,
            previewAspectRatio: previewAspectRatio
        )
        return FaceSelectionFrame(
            image: image,
            faces: zip(candidates, observations).map { candidate, observation in
                var candidate = candidate
                candidate.contour = Self.jawline(of: observation)
                return candidate
            }
        )
    }

    /// Vision은 랜드마크 점을 얼굴 박스 기준 상대 좌표로, 왼쪽 아래를 원점으로 준다.
    private static func jawline(of observation: VNFaceObservation) -> [CGPoint] {
        guard let points = observation.landmarks?.faceContour?.normalizedPoints else { return [] }
        let box = observation.boundingBox
        return points.map { point in
            CGPoint(
                x: box.minX + point.x * box.width,
                y: 1 - (box.minY + point.y * box.height)
            )
        }
    }

    // Vision은 왼쪽 아래를 원점으로 쓰고 미리보기 오버레이는 왼쪽 위를 원점으로 쓴다.
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

    private func makeCenteredCrop(
        pixelBuffer: CVPixelBuffer,
        orientation: CGImagePropertyOrientation
    ) -> CGImage? {
        makeCenteredCrop(image: CIImage(cvPixelBuffer: pixelBuffer).oriented(orientation))
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
        runningOnSimulatorCPU(VNDetectFaceRectanglesRequest())
    }

    private static func faceLandmarksRequest() -> VNDetectFaceLandmarksRequest {
        runningOnSimulatorCPU(VNDetectFaceLandmarksRequest())
    }

    private static func runningOnSimulatorCPU<Request: VNImageBasedRequest>(_ request: Request) -> Request {
        #if targetEnvironment(simulator)
        // 시뮬레이터에서는 Vision의 기본 추론 컨텍스트를 만들 수 없다.
        for (stage, devices) in (try? request.supportedComputeStageDevices) ?? [:] {
            if let cpu = devices.first(where: {
                if case .cpu = $0 { return true }
                return false
            }) {
                request.setComputeDevice(cpu, for: stage)
            }
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
