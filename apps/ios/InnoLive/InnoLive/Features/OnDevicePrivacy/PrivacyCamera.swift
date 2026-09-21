#if DEBUG
import AVFoundation
import OSLog

/// Capture, inference and compositing share one serial queue. Late frames are dropped by AVFoundation.
nonisolated final class PrivacyCamera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    typealias FrameHandler = @Sendable (CGImage, Int, Double, Double) -> Void
    typealias ErrorHandler = @Sendable (String) -> Void
    private let queue = DispatchQueue(label: "com.innolive.privacy-lab", qos: .userInitiated)
    private let session = AVCaptureSession()
    private let output = AVCaptureVideoDataOutput()
    private let logger = Logger(subsystem: "com.framework.innolive.privacy-lab", category: "pipeline")
    private var model: PrivacyModel?
    private var onFrame: FrameHandler?
    private var onError: ErrorHandler?
    private var position: AVCaptureDevice.Position = .front
    private var lastCompleted: TimeInterval = 0
    private var processed = 0

    func start(front: Bool, onFrame: @escaping FrameHandler, onError: @escaping ErrorHandler) {
        queue.async { [self] in
            self.onFrame = onFrame
            self.onError = onError
            position = front ? .front : .back
            do {
                if model == nil { model = try PrivacyModel() }
                try configure()
                lastCompleted = 0
                processed = 0
                session.startRunning()
                logger.info("local camera started; model=CoreML FP16 640; no network transport")
            } catch {
                session.stopRunning()
                onError(error.localizedDescription)
                logger.error("start failed: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    func stop() {
        queue.async { [self] in
            session.stopRunning()
            onFrame = nil
            onError = nil
        }
    }

    private func configure() throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        for input in session.inputs { session.removeInput(input) }
        session.sessionPreset = .hd1920x1080
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) else {
            throw NSError(domain: "PrivacyCamera", code: 1, userInfo: [NSLocalizedDescriptionKey: "카메라를 사용할 수 없습니다."])
        }
        let input = try AVCaptureDeviceInput(device: camera)
        guard session.canAddInput(input) else { throw PrivacyModelError.imageBuffer }
        session.addInput(input)
        if session.outputs.isEmpty {
            output.alwaysDiscardsLateVideoFrames = true
            output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            output.setSampleBufferDelegate(self, queue: queue)
            guard session.canAddOutput(output) else { throw PrivacyModelError.imageBuffer }
            session.addOutput(output)
        }
        if let connection = output.connection(with: .video) {
            if connection.isVideoRotationAngleSupported(90) { connection.videoRotationAngle = 90 }
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = position == .front
            }
        }
        try camera.lockForConfiguration()
        defer { camera.unlockForConfiguration() }
        if camera.activeFormat.videoSupportedFrameRateRanges.contains(where: { $0.minFrameRate <= 30 && $0.maxFrameRate >= 30 }) {
            camera.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
            camera.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 30)
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard session.isRunning, let model, let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        autoreleasepool {
            do {
                let start = ProcessInfo.processInfo.systemUptime
                let (image, count) = try model.process(buffer)
                let completed = ProcessInfo.processInfo.systemUptime
                let milliseconds = (completed - start) * 1000
                let fps = lastCompleted == 0 ? 0 : 1 / (completed - lastCompleted)
                lastCompleted = completed
                processed += 1
                onFrame?(image, count, milliseconds, fps)
                if processed == 1 || processed % 60 == 0 {
                    logger.info("processed=\(self.processed) objects=\(count) processing_ms=\(milliseconds) fps=\(fps)")

                }
            } catch {
                // Never show an unprocessed fallback frame after a runtime failure.
                session.stopRunning()
                onError?(error.localizedDescription)
                logger.error("processing failed: \(error.localizedDescription, privacy: .public)")
            }
        }
    }
}
#endif
