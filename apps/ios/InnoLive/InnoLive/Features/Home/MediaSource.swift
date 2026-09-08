import AVFoundation
import Foundation
@preconcurrency import LiveKitWebRTC

nonisolated enum MediaSourceKind: String, Equatable, Sendable {
    case camera
    case file
}

nonisolated enum VideoFrameRotation: Int, Equatable, Sendable {
    case rotation0 = 0
    case rotation90 = 90
    case rotation180 = 180
    case rotation270 = 270

    var rtcRotation: LKRTCVideoRotation {
        LKRTCVideoRotation(rawValue: rawValue) ?? LKRTCVideoRotation(rawValue: 0)!
    }

    nonisolated static func from(_ rotation: LKRTCVideoRotation) -> Self {
        switch rotation.rawValue {
        case 90: return .rotation90
        case 180: return .rotation180
        case 270: return .rotation270
        default: return .rotation0
        }
    }

    nonisolated static func from(preferredTransform transform: CGAffineTransform) -> Self {
        let angle = Int((atan2(transform.b, transform.a) * 180 / .pi).rounded())
        switch (angle % 360 + 360) % 360 {
        case 90: return .rotation90
        case 180: return .rotation180
        case 270: return .rotation270
        default: return .rotation0
        }
    }

    nonisolated var exifOrientation: Int32 {
        switch self {
        case .rotation90: return 6
        case .rotation180: return 3
        case .rotation270: return 8
        case .rotation0: return 1
        }
    }
}

/// A frame with the original WebRTC timestamp and orientation retained.
/// `pixelBuffer` is available when the source supplies a CVPixelBuffer. The
/// consumer can still forward frames whose WebRTC buffer is not CV-backed.
nonisolated struct VideoFrame: @unchecked Sendable {
    let rtcFrame: LKRTCVideoFrame
    let pixelBuffer: CVPixelBuffer?
    let timestampNs: Int64
    let rotation: VideoFrameRotation
    let source: MediaSourceKind
}

nonisolated struct MediaSourceMetrics: Equatable, Sendable {
    var inputFPS: Double = 0
    var width: Int = 0
    var height: Int = 0
    var deliveredFrames: Int = 0
    var droppedFrames: Int = 0
}

enum MediaSourceError: LocalizedError, Equatable {
    case cameraPermissionRequired
    case noCamera
    case unsupportedCameraFormat
    case cameraRecoveryFailed
    case invalidFile
    case missingVideoTrack
    case readerCreationFailed
    case readerFailed(String)
    case noPixelBuffer

    var errorDescription: String? {
        switch self {
        case .cameraPermissionRequired:
            return "카메라 권한이 필요합니다."
        case .noCamera:
            return "사용할 수 있는 카메라를 찾지 못했습니다."
        case .unsupportedCameraFormat:
            return "선택한 카메라의 영상 형식을 준비하지 못했습니다."
        case .cameraRecoveryFailed:
            return "카메라 연결을 복구하지 못했습니다."
        case .invalidFile:
            return "재생할 수 없는 영상 파일입니다."
        case .missingVideoTrack:
            return "영상 트랙을 찾지 못했습니다."
        case .readerCreationFailed:
            return "영상 디코더를 준비하지 못했습니다."
        case let .readerFailed(message):
            return "영상 파일을 읽지 못했습니다: \(message)"
        case .noPixelBuffer:
            return "영상 프레임을 디코드하지 못했습니다."
        }
    }
}

protocol MediaSource: AnyObject {
    var kind: MediaSourceKind { get }
    var metrics: MediaSourceMetrics { get }
    func start() async throws
    func stop() async
}

/// The only consumer used by camera, file, and the debug harness. Keeping the
/// WebRTC conversion here means every source reaches the same RTC boundary.
nonisolated final class WebRTCVideoFrameConsumer: NSObject, LKRTCVideoCapturerDelegate, @unchecked Sendable {
    private let target: LKRTCVideoSource
    private var bridgeCapturer: LKRTCVideoCapturer!
    private let lock = NSLock()
    private let frameObserver: (@Sendable (VideoFrame) -> Void)?
    private var metrics = MediaSourceMetrics()
    private var firstTimestampNs: Int64?
    private var lastTimestampNs: Int64?
    private var deliveredAtUptime: TimeInterval?
    private var sourceKind: MediaSourceKind = .camera

    init(
        target: LKRTCVideoSource,
        frameObserver: (@Sendable (VideoFrame) -> Void)? = nil
    ) {
        self.target = target
        self.frameObserver = frameObserver
        super.init()
        bridgeCapturer = LKRTCVideoCapturer(delegate: self)
    }

    func capturer(_ capturer: LKRTCVideoCapturer, didCapture frame: LKRTCVideoFrame) {}

    @discardableResult
    func consume(_ frame: VideoFrame) -> Bool {
        target.capturer(bridgeCapturer, didCapture: frame.rtcFrame)

        lock.lock()
        sourceKind = frame.source
        metrics.width = Int(frame.rtcFrame.width)
        metrics.height = Int(frame.rtcFrame.height)
        metrics.deliveredFrames += 1
        let now = ProcessInfo.processInfo.systemUptime
        if firstTimestampNs == nil {
            firstTimestampNs = frame.timestampNs
            deliveredAtUptime = now
        }
        lastTimestampNs = frame.timestampNs
        if let deliveredAtUptime,
           now > deliveredAtUptime,
           metrics.deliveredFrames > 1 {
            metrics.inputFPS = Double(metrics.deliveredFrames - 1) / (now - deliveredAtUptime)
        }
        lock.unlock()
        frameObserver?(frame)
        return true
    }

    func recordDrop() {
        lock.lock()
        metrics.droppedFrames += 1
        lock.unlock()
    }

    func currentMetrics() -> MediaSourceMetrics {
        lock.lock()
        defer { lock.unlock() }
        return metrics
    }
}

nonisolated final class WebRTCCameraFrameRelay: NSObject, LKRTCVideoCapturerDelegate, @unchecked Sendable {
    struct SendablePixelBuffer: @unchecked Sendable {
        let value: CVPixelBuffer
    }

    typealias FaceFrameHandler = @Sendable (CVPixelBuffer, AVCaptureDevice.Position) -> Void

    private static let deliveryInterval: TimeInterval = 0.1

    private let target: WebRTCVideoFrameConsumer
    private let analysisQueue = DispatchQueue(label: "com.innolive.webrtc.face-detection")
    private let lock = NSLock()
    private var faceFrameHandler: FaceFrameHandler?
    private var cameraPosition: AVCaptureDevice.Position
    private var isAnalysisPending = false
    private var lastDeliveryTime: TimeInterval = 0

    init(target: WebRTCVideoFrameConsumer, cameraPosition: AVCaptureDevice.Position) {
        self.target = target
        self.cameraPosition = cameraPosition
        super.init()
    }

    func setFaceFrameHandler(
        _ handler: FaceFrameHandler?,
        cameraPosition: AVCaptureDevice.Position
    ) {
        lock.lock()
        faceFrameHandler = handler
        self.cameraPosition = cameraPosition
        lastDeliveryTime = 0
        lock.unlock()
    }

    func updateCameraPosition(_ cameraPosition: AVCaptureDevice.Position) {
        lock.lock()
        self.cameraPosition = cameraPosition
        lock.unlock()
    }

    func capturer(_ capturer: LKRTCVideoCapturer, didCapture frame: LKRTCVideoFrame) {
        let frameBuffer = (frame.buffer as? LKRTCCVPixelBuffer)?.pixelBuffer
        let videoFrame = VideoFrame(
            rtcFrame: frame,
            pixelBuffer: frameBuffer,
            timestampNs: frame.timeStampNs,
            rotation: VideoFrameRotation.from(frame.rotation),
            source: .camera
        )
        _ = target.consume(videoFrame)

        guard let frameBuffer else { return }
        lock.lock()
        let now = ProcessInfo.processInfo.systemUptime
        guard let handler = faceFrameHandler,
              !isAnalysisPending,
              now - lastDeliveryTime >= Self.deliveryInterval else {
            lock.unlock()
            return
        }
        isAnalysisPending = true
        lastDeliveryTime = now
        let currentCameraPosition = cameraPosition
        let sendableFrameBuffer = SendablePixelBuffer(value: frameBuffer)
        lock.unlock()

        analysisQueue.async { [weak self, sendableFrameBuffer] in
            handler(sendableFrameBuffer.value, currentCameraPosition)
            guard let self else { return }
            self.lock.lock()
            self.isAnalysisPending = false
            self.lock.unlock()
        }
    }
}

@MainActor
final class CameraSource: MediaSource, @unchecked Sendable {
    let kind: MediaSourceKind = .camera
    let capturer: LKRTCCameraVideoCapturer
    let relay: WebRTCCameraFrameRelay

    private let consumer: WebRTCVideoFrameConsumer
    private var selectedDevice: AVCaptureDevice
    private var quality: CameraQualityPreset
    private var isRunning = false
    private var captureInFlight = false
    private var operationGeneration: UInt = 0

    init(
        preferredCameraID: String?,
        quality: CameraQualityPreset,
        consumer: WebRTCVideoFrameConsumer
    ) throws {
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
            throw MediaSourceError.cameraPermissionRequired
        }

        let availableDevices = LKRTCCameraVideoCapturer.captureDevices()
        guard let selectedDevice = preferredCameraID
            .flatMap({ cameraID in availableDevices.first { $0.uniqueID == cameraID } })
            ?? availableDevices.first(where: { $0.position == .front })
            ?? availableDevices.first else {
            throw MediaSourceError.noCamera
        }
        guard Self.captureSetting(for: selectedDevice, quality: quality) != nil else {
            throw MediaSourceError.unsupportedCameraFormat
        }

        self.selectedDevice = selectedDevice
        self.quality = quality
        self.consumer = consumer
        let relay = WebRTCCameraFrameRelay(
            target: consumer,
            cameraPosition: selectedDevice.position
        )
        self.relay = relay
        capturer = LKRTCCameraVideoCapturer(delegate: relay)
    }

    var metrics: MediaSourceMetrics {
        consumer.currentMetrics()
    }

    var currentCameraID: String { selectedDevice.uniqueID }
    var currentCameraPosition: AVCaptureDevice.Position { selectedDevice.position }
    var currentQuality: CameraQualityPreset { quality }

    func start() async throws {
        guard !isRunning else { return }
        operationGeneration &+= 1
        let generation = operationGeneration
        guard let setting = Self.captureSetting(for: selectedDevice, quality: quality) else {
            throw MediaSourceError.unsupportedCameraFormat
        }
        do {
            try await startCapture(with: selectedDevice, setting: setting)
        } catch {
            throw error
        }
        guard operationGeneration == generation else {
            await forceStopCapture()
            throw WebRTCVideoUplinkError.cancelled
        }
        isRunning = true
    }

    func stop() async {
        operationGeneration &+= 1
        await stopCapture()
    }

    private func stopCapture() async {
        guard isRunning || captureInFlight else { return }
        await forceStopCapture()
    }

    private func forceStopCapture() async {
        await withCheckedContinuation { continuation in
            capturer.stopCapture {
                continuation.resume()
            }
        }
        isRunning = false
        captureInFlight = false
    }

    func switchCamera(to cameraID: String) async throws {
        guard isRunning else {
            throw WebRTCVideoUplinkError.failed("비식별화를 시작한 뒤 카메라를 전환해 주세요.")
        }
        guard cameraID != selectedDevice.uniqueID else { return }
        guard let newDevice = AVCaptureDevice(uniqueID: cameraID),
              let newSetting = Self.captureSetting(for: newDevice, quality: quality),
              let previousSetting = Self.captureSetting(for: selectedDevice, quality: quality) else {
            throw WebRTCVideoUplinkError.failed("선택한 카메라를 사용할 수 없습니다.")
        }

        operationGeneration &+= 1
        let generation = operationGeneration
        await stopCapture()
        guard operationGeneration == generation else {
            throw WebRTCVideoUplinkError.cancelled
        }

        do {
            try await startCapture(with: newDevice, setting: newSetting)
            guard operationGeneration == generation else {
                await forceStopCapture()
                throw WebRTCVideoUplinkError.cancelled
            }
            selectedDevice = newDevice
            relay.updateCameraPosition(newDevice.position)
            isRunning = true
        } catch {
            await forceStopCapture()
            guard operationGeneration == generation else {
                throw WebRTCVideoUplinkError.cancelled
            }
            do {
                try await startCapture(with: selectedDevice, setting: previousSetting)
                guard operationGeneration == generation else {
                    await forceStopCapture()
                    throw WebRTCVideoUplinkError.cancelled
                }
                isRunning = true
            } catch {
                await forceStopCapture()
                guard operationGeneration == generation else {
                    throw WebRTCVideoUplinkError.cancelled
                }
                throw MediaSourceError.cameraRecoveryFailed
            }
            throw WebRTCVideoUplinkError.failed("카메라를 전환하지 못해 기존 카메라를 계속 사용합니다.")
        }
    }

    func switchQuality(to newQuality: CameraQualityPreset) async throws {
        guard isRunning else {
            throw WebRTCVideoUplinkError.failed("비식별화를 시작한 뒤 화질을 변경해 주세요.")
        }
        guard newQuality != quality else { return }
        guard let newSetting = Self.captureSetting(for: selectedDevice, quality: newQuality),
              let previousSetting = Self.captureSetting(for: selectedDevice, quality: quality) else {
            throw WebRTCVideoUplinkError.failed("현재 카메라가 선택한 화질을 지원하지 않습니다.")
        }

        operationGeneration &+= 1
        let generation = operationGeneration
        await stopCapture()
        guard operationGeneration == generation else {
            throw WebRTCVideoUplinkError.cancelled
        }
        do {
            try await startCapture(with: selectedDevice, setting: newSetting)
            guard operationGeneration == generation else {
                await forceStopCapture()
                throw WebRTCVideoUplinkError.cancelled
            }
            quality = newQuality
            isRunning = true
        } catch {
            await forceStopCapture()
            guard operationGeneration == generation else {
                throw WebRTCVideoUplinkError.cancelled
            }
            do {
                try await startCapture(with: selectedDevice, setting: previousSetting)
                guard operationGeneration == generation else {
                    await forceStopCapture()
                    throw WebRTCVideoUplinkError.cancelled
                }
                isRunning = true
            } catch {
                await forceStopCapture()
                guard operationGeneration == generation else {
                    throw WebRTCVideoUplinkError.cancelled
                }
                throw MediaSourceError.cameraRecoveryFailed
            }
            throw WebRTCVideoUplinkError.failed("화질을 변경하지 못해 기존 화질을 계속 사용합니다.")
        }
    }

    @discardableResult
    func startFaceFrameDelivery(
        handler: @escaping WebRTCCameraFrameRelay.FaceFrameHandler
    ) -> Bool {
        guard isRunning else { return false }
        relay.setFaceFrameHandler(handler, cameraPosition: selectedDevice.position)
        return true
    }

    func stopFaceFrameDelivery() {
        relay.setFaceFrameHandler(nil, cameraPosition: .unspecified)
    }

    private func startCapture(
        with device: AVCaptureDevice,
        setting: CameraCaptureSetting
    ) async throws {
        captureInFlight = true
        do {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                capturer.startCapture(with: device, format: setting.format, fps: setting.fps) { error in
                    if let error {
                        continuation.resume(
                            throwing: WebRTCVideoUplinkError.failed(
                                "선택한 카메라를 열지 못했습니다: \(error.localizedDescription)"
                            )
                        )
                    } else {
                        continuation.resume()
                    }
                }
            }
        } catch {
            captureInFlight = false
            throw error
        }
        captureInFlight = false
    }

    private static func captureSetting(
        for device: AVCaptureDevice,
        quality: CameraQualityPreset
    ) -> CameraCaptureSetting? {
        let candidates = LKRTCCameraVideoCapturer.supportedFormats(for: device).compactMap { format -> CameraCaptureSetting? in
            guard format.videoSupportedFrameRateRanges.contains(where: { range in
                range.minFrameRate <= Double(quality.framesPerSecond)
                    && range.maxFrameRate >= Double(quality.framesPerSecond)
            }) else { return nil }
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            return CameraCaptureSetting(
                format: format,
                width: dimensions.width,
                height: dimensions.height,
                fps: quality.framesPerSecond
            )
        }
        return candidates.min { lhs, rhs in
            let lhsScore = Int64(abs(lhs.width - quality.width)) + Int64(abs(lhs.height - quality.height))
            let rhsScore = Int64(abs(rhs.width - quality.width)) + Int64(abs(rhs.height - quality.height))
            return lhsScore < rhsScore
        }
    }
}

private struct CameraCaptureSetting {
    let format: AVCaptureDevice.Format
    let width: Int32
    let height: Int32
    let fps: Int
}

final class FileSource: MediaSource, @unchecked Sendable {
    let kind: MediaSourceKind = .file
    let url: URL

    private let consumer: WebRTCVideoFrameConsumer
    private let decodeQueue = DispatchQueue(label: "com.innolive.media.file-source")
    private let stateLock = NSLock()
    private let bufferCapacity: Int
    private let loop: Bool
    private let errorHandler: (@Sendable (Error) -> Void)?
    private let completionHandler: (@Sendable () -> Void)?
    private var reader: AVAssetReader?
    private var output: AVAssetReaderTrackOutput?
    private var track: AVAssetTrack?
    private var pendingFrames: [DecodedFrame] = []
    private var scheduledDelivery: DispatchWorkItem?
    private var generation: UInt = 0
    private var running = false
    private var reachedEOF = false
    private var timestampOffsetNs: Int64 = 0
    private var sourcePassStartTimestampNs: Int64?
    private var lastOutputTimestampNs: Int64?
    private var lastFrameDurationNs: Int64?
    private var frameDurationNs: Int64 = 33_333_333
    private var hostStartUptimeNs: UInt64?
    private var rotation: VideoFrameRotation = .rotation0
    private var currentMetrics = MediaSourceMetrics()
    private var firstTimestampNs: Int64?
    private var firstDeliveryUptime: TimeInterval?
    private var didComplete = false

    init(
        url: URL,
        consumer: WebRTCVideoFrameConsumer,
        loop: Bool = true,
        bufferCapacity: Int = 3,
        errorHandler: (@Sendable (Error) -> Void)? = nil,
        completionHandler: (@Sendable () -> Void)? = nil
    ) {
        self.url = url
        self.consumer = consumer
        self.loop = loop
        self.bufferCapacity = max(1, bufferCapacity)
        self.errorHandler = errorHandler
        self.completionHandler = completionHandler
    }

    var metrics: MediaSourceMetrics {
        stateLock.lock()
        defer { stateLock.unlock() }
        return currentMetrics
    }

    func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            decodeQueue.async { [weak self] in
                guard let self else {
                    continuation.resume(throwing: MediaSourceError.invalidFile)
                    return
                }
                do {
                    try self.startOnQueue()
                    continuation.resume()
                } catch {
                    self.stopOnQueue()
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    func stop() async {
        await withCheckedContinuation { continuation in
            decodeQueue.async { [weak self] in
                self?.stopOnQueue()
                continuation.resume()
            }
        }
    }

    private func startOnQueue() throws {
        stopOnQueue()
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw MediaSourceError.invalidFile
        }
        let asset = AVAsset(url: url)
        guard let track = asset.tracks(withMediaType: .video).first else {
            throw MediaSourceError.missingVideoTrack
        }
        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            throw MediaSourceError.readerFailed(error.localizedDescription)
        }
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
            ]
        )
        guard reader.canAdd(output) else {
            throw MediaSourceError.readerCreationFailed
        }
        reader.add(output)
        self.track = track
        self.reader = reader
        self.output = output
        rotation = VideoFrameRotation.from(preferredTransform: track.preferredTransform)
        frameDurationNs = estimatedFrameDurationNs(for: track)
        timestampOffsetNs = (lastOutputTimestampNs ?? 0)
            + (lastOutputTimestampNs == nil ? 0 : max(1, lastFrameDurationNs ?? frameDurationNs))
        hostStartUptimeNs = nil
        firstTimestampNs = nil
        firstDeliveryUptime = nil
        stateLock.lock()
        currentMetrics = MediaSourceMetrics()
        stateLock.unlock()
        pendingFrames.removeAll(keepingCapacity: true)
        reachedEOF = false
        sourcePassStartTimestampNs = nil
        running = true
        reader.startReading()
        guard reader.status == .reading || reader.status == .completed else {
            throw MediaSourceError.readerFailed(reader.error?.localizedDescription ?? "unknown error")
        }
        do {
            try fillBufferOnQueue()
        } catch {
            failOnQueue(error)
            throw error
        }
        guard !pendingFrames.isEmpty else {
            let error = MediaSourceError.readerFailed("영상 파일에서 프레임을 찾지 못했습니다.")
            failOnQueue(error)
            throw error
        }
        scheduleNextDeliveryOnQueue(generation: generation)
    }

    private func stopOnQueue() {
        generation &+= 1
        scheduledDelivery?.cancel()
        scheduledDelivery = nil
        reader?.cancelReading()
        reader = nil
        output = nil
        track = nil
        pendingFrames.removeAll(keepingCapacity: false)
        running = false
        reachedEOF = false
        didComplete = false
    }

    private func fillBufferOnQueue() throws {
        guard running else { return }
        while pendingFrames.count < bufferCapacity {
            guard let frame = try readNextFrameOnQueue() else { break }
            pendingFrames.append(frame)
        }
    }

    private func scheduleNextDeliveryOnQueue(generation scheduledGeneration: UInt) {
        guard running, scheduledGeneration == generation else { return }
        do {
            try fillBufferOnQueue()
        } catch {
            failOnQueue(error)
            return
        }
        guard let nextFrame = pendingFrames.first else {
            if !loop || reachedEOF {
                finishOnQueue()
            }
            return
        }

        let now = DispatchTime.now().uptimeNanoseconds
        let startTimestamp = firstTimestampNs ?? nextFrame.frame.timestampNs
        if firstTimestampNs == nil {
            firstTimestampNs = startTimestamp
            firstDeliveryUptime = ProcessInfo.processInfo.systemUptime
            hostStartUptimeNs = now
        }
        let base = hostStartUptimeNs ?? now
        let target = base + UInt64(max(0, nextFrame.frame.timestampNs - startTimestamp))
        let delay = target > now ? target - now : 0
        let workItem = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.deliverNextFrameOnQueue(generation: scheduledGeneration)
        }
        scheduledDelivery = workItem
        decodeQueue.asyncAfter(deadline: .now() + .nanoseconds(Int(min(delay, UInt64(Int.max)))), execute: workItem)
    }

    private func deliverNextFrameOnQueue(generation scheduledGeneration: UInt) {
        guard running, scheduledGeneration == generation else { return }
        scheduledDelivery = nil
        guard !pendingFrames.isEmpty else {
            scheduleNextDeliveryOnQueue(generation: scheduledGeneration)
            return
        }
        let decoded = pendingFrames.removeFirst()
        let now = DispatchTime.now().uptimeNanoseconds
        let startTimestamp = firstTimestampNs ?? decoded.frame.timestampNs
        let target = (hostStartUptimeNs ?? now)
            + UInt64(max(0, decoded.frame.timestampNs - startTimestamp))
        if target + 100_000_000 < now {
            consumer.recordDrop()
            stateLock.lock()
            currentMetrics.droppedFrames += 1
            stateLock.unlock()
            do {
                try fillBufferOnQueue()
            } catch {
                failOnQueue(error)
                return
            }
            scheduleNextDeliveryOnQueue(generation: scheduledGeneration)
            return
        }
        let accepted = consumer.consume(decoded.frame)
        if !accepted {
            consumer.recordDrop()
            stateLock.lock()
            currentMetrics.droppedFrames += 1
            stateLock.unlock()
        } else {
            updateMetrics(for: decoded.frame)
        }
        do {
            try fillBufferOnQueue()
        } catch {
            failOnQueue(error)
            return
        }
        scheduleNextDeliveryOnQueue(generation: scheduledGeneration)
    }

    private func readNextFrameOnQueue() throws -> DecodedFrame? {
        var eofResetCount = 0
        while true {
            guard running, let output else { return nil }
            guard let sampleBuffer = output.copyNextSampleBuffer() else {
                if let reader, reader.status == .failed {
                    throw MediaSourceError.readerFailed(reader.error?.localizedDescription ?? "unknown error")
                }
                guard loop else {
                    reachedEOF = true
                    return nil
                }
                eofResetCount += 1
                guard eofResetCount <= 1 else {
                    throw MediaSourceError.readerFailed("영상 파일에서 프레임을 찾지 못했습니다.")
                }
                try resetReaderOnQueue()
                continue
            }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            throw MediaSourceError.noPixelBuffer
        }
        let sourceTimestampNs = max(0, CMTimeConvertScale(
            CMSampleBufferGetPresentationTimeStamp(sampleBuffer),
            timescale: 1_000_000_000,
            method: .roundHalfAwayFromZero
        ).value)
        if sourcePassStartTimestampNs == nil {
            sourcePassStartTimestampNs = sourceTimestampNs
        }
        let normalizedSourceTimestampNs = max(
            0,
            sourceTimestampNs - (sourcePassStartTimestampNs ?? sourceTimestampNs)
        )
        var outputTimestampNs = normalizedSourceTimestampNs + timestampOffsetNs
        if let lastOutputTimestampNs, outputTimestampNs <= lastOutputTimestampNs {
            outputTimestampNs = lastOutputTimestampNs + max(1, frameDurationNs)
            timestampOffsetNs = outputTimestampNs - normalizedSourceTimestampNs
        }
        lastOutputTimestampNs = outputTimestampNs
        let sampleDurationNs = CMTimeConvertScale(
            CMSampleBufferGetDuration(sampleBuffer),
            timescale: 1_000_000_000,
            method: .roundHalfAwayFromZero
        ).value
        if sampleDurationNs > 0 {
            lastFrameDurationNs = sampleDurationNs
        }
        let rtcBuffer = LKRTCCVPixelBuffer(pixelBuffer: pixelBuffer)
        let rtcFrame = LKRTCVideoFrame(
            buffer: rtcBuffer,
            rotation: rotation.rtcRotation,
            timeStampNs: outputTimestampNs
        )
            return DecodedFrame(
                frame: VideoFrame(
                    rtcFrame: rtcFrame,
                    pixelBuffer: pixelBuffer,
                    timestampNs: outputTimestampNs,
                    rotation: rotation,
                    source: .file
                )
            )
        }
    }

    private func resetReaderOnQueue() throws {
        guard track != nil else { throw MediaSourceError.missingVideoTrack }
        if let reader, reader.status == .reading {
            reader.cancelReading()
        }
        let asset = AVAsset(url: url)
        let newReader: AVAssetReader
        do {
            newReader = try AVAssetReader(asset: asset)
        } catch {
            throw MediaSourceError.readerFailed(error.localizedDescription)
        }
        guard let newTrack = asset.tracks(withMediaType: .video).first else {
            throw MediaSourceError.missingVideoTrack
        }
        let newOutput = AVAssetReaderTrackOutput(
            track: newTrack,
            outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
            ]
        )
        guard newReader.canAdd(newOutput) else { throw MediaSourceError.readerCreationFailed }
        newReader.add(newOutput)
        newReader.startReading()
        reader = newReader
        output = newOutput
        track = newTrack
        rotation = VideoFrameRotation.from(preferredTransform: newTrack.preferredTransform)
        timestampOffsetNs = (lastOutputTimestampNs ?? 0)
            + max(1, lastFrameDurationNs ?? frameDurationNs)
        sourcePassStartTimestampNs = nil
        reachedEOF = false
    }

    private func failOnQueue(_ error: Error) {
        guard running else { return }
        running = false
        reachedEOF = true
        generation &+= 1
        scheduledDelivery?.cancel()
        scheduledDelivery = nil
        reader?.cancelReading()
        reader = nil
        output = nil
        track = nil
        pendingFrames.removeAll(keepingCapacity: false)
        errorHandler?(error)
    }

    private func finishOnQueue() {
        guard !didComplete else { return }
        didComplete = true
        running = false
        generation &+= 1
        scheduledDelivery?.cancel()
        scheduledDelivery = nil
        reader?.cancelReading()
        reader = nil
        output = nil
        track = nil
        pendingFrames.removeAll(keepingCapacity: false)
        completionHandler?()
    }

    private func estimatedFrameDurationNs(for track: AVAssetTrack) -> Int64 {
        let fps = track.nominalFrameRate
        guard fps.isFinite, fps > 0 else { return 33_333_333 }
        return max(1, Int64((1_000_000_000.0 / Double(fps)).rounded()))
    }

    private func updateMetrics(for frame: VideoFrame) {
        stateLock.lock()
        currentMetrics.width = Int(frame.rtcFrame.width)
        currentMetrics.height = Int(frame.rtcFrame.height)
        currentMetrics.deliveredFrames += 1
        if let firstDeliveryUptime,
           let firstTimestampNs,
           frame.timestampNs >= firstTimestampNs,
           ProcessInfo.processInfo.systemUptime > firstDeliveryUptime {
            currentMetrics.inputFPS = Double(currentMetrics.deliveredFrames - 1)
                / (ProcessInfo.processInfo.systemUptime - firstDeliveryUptime)
        }
        stateLock.unlock()
    }

    private struct DecodedFrame {
        let frame: VideoFrame
    }
}
