import CoreImage
import ImageIO
@preconcurrency import LiveKitWebRTC

/// One in-flight frame. Mode/camera changes invalidate any earlier result.
nonisolated final class PrivacyFrameGate: @unchecked Sendable {
    struct Ticket { let id: UUID; let generation: UInt; let enabled: Bool }
    private let lock = NSLock()
    private var generation: UInt = 0
    private var activeTicket: UUID?
    private var stopped = false
    private var enabled = true

    func begin() -> Ticket? {
        lock.withLock {
            guard activeTicket == nil, !stopped else { return nil }
            let id = UUID()
            activeTicket = id
            return Ticket(id: id, generation: generation, enabled: enabled)
        }
    }
    func invalidate(enabled: Bool? = nil, stop: Bool = false) {
        lock.withLock {
            generation &+= 1
            if let enabled { self.enabled = enabled }
            stopped = stopped || stop
        }
    }
    func finish(_ ticket: Ticket, deliver: () -> Void) {
        lock.withLock {
            guard activeTicket == ticket.id else { return }
            defer { activeTicket = nil }
            guard !stopped, ticket.generation == generation else { return }
            deliver()
        }
    }
}

nonisolated final class PrivacyUplinkProcessor: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.innolive.webrtc.local-ai", qos: .userInitiated)
    private let gate = PrivacyFrameGate()
    private var model: PrivacyModel?
    private var appliedGeneration: UInt?
    private var geometry: String?
    private let outputPool = PrivacyOutputBufferPool()
    private let onError: @Sendable (String) -> Void
    private let metricsLock = NSLock()
    private var metricsHandler: (@Sendable (PrivacyFrameMetric) -> Void)?

    init(onError: @escaping @Sendable (String) -> Void) { self.onError = onError }
    #if DEBUG
    private var timingObserver: (@Sendable (PrivacyTimings) -> Void)?
    func setTimingObserver(_ observer: (@Sendable (PrivacyTimings) -> Void)?) {
        metricsLock.withLock { timingObserver = observer }
    }
    #endif
    /// 직접 송출 하트비트용 추론 시간·실패 기록을 받는다.
    func setMetricsHandler(_ handler: (@Sendable (PrivacyFrameMetric) -> Void)?) {
        metricsLock.withLock { metricsHandler = handler }
    }
    private func report(_ metric: PrivacyFrameMetric) {
        let handler = metricsLock.withLock { metricsHandler }
        handler?(metric)
    }
    func prepare() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async { [self] in
                do {
                    if model == nil { model = try PrivacyModel() }
                    continuation.resume()
                } catch { continuation.resume(throwing: error) }
            }
        }
    }
    func setEnabled(_ enabled: Bool) { gate.invalidate(enabled: enabled) }
    func reset() { gate.invalidate() }
    func stop() { gate.invalidate(stop: true) }

    func submit(_ frame: LKRTCVideoFrame, deliver: @escaping @Sendable (LKRTCVideoFrame) -> Void) {
        guard let ticket = gate.begin() else { PrivacyPipelineProbe.count("gate_rejected"); return }
        PrivacyPipelineProbe.count("gate_admitted")
        let payload = FramePayload(frame: frame)
        let libraryRevision = PrivacyFaceLibrary.revision
        queue.async { [self] in
            autoreleasepool {
                do {
                    let result: LKRTCVideoFrame
                    if ticket.enabled {
                        guard let pixels = payload.frame.buffer as? LKRTCCVPixelBuffer else { throw PrivacyModelError.imageBuffer }
                        if model == nil { model = try PrivacyModel() }
                        let nextGeometry = "\(pixels.width)x\(pixels.height):\(payload.frame.rotation.rawValue)"
                        if appliedGeneration != ticket.generation || geometry != nextGeometry {
                            model!.resetTemporalState()
                            appliedGeneration = ticket.generation
                            geometry = nextGeometry
                        }
                        let started = DispatchTime.now().uptimeNanoseconds
                        let orientation = Self.orientation(rotation: payload.frame.rotation.rawValue)
                        let original = CIImage(cvPixelBuffer: pixels.pixelBuffer).oriented(orientation.forward)
                        let upright = original.transformed(by: CGAffineTransform(translationX: -original.extent.minX, y: -original.extent.minY))
                        let output = try outputPool.acquire(width: CVPixelBufferGetWidth(pixels.pixelBuffer),
                                                            height: CVPixelBufferGetHeight(pixels.pixelBuffer))
                        // Restore sensor dimensions; WebRTC still carries the original rotation metadata.
                        let processing = try model!.process(upright, into: output, restoring: orientation.reverse)
                        #if DEBUG
                        let observer = metricsLock.withLock { timingObserver }
                        observer?(processing.1)
                        PrivacyPipelineProbe.record("prepare", milliseconds: processing.1.prepare)
                        PrivacyPipelineProbe.record("inference", milliseconds: processing.1.inference)
                        PrivacyPipelineProbe.record("mask", milliseconds: processing.1.mask)
                        PrivacyPipelineProbe.record("render", milliseconds: processing.1.render)
                        PrivacyPipelineProbe.count("faces_\(processing.0)")
                        #endif
                        report(.inference(milliseconds: Double(DispatchTime.now().uptimeNanoseconds - started) / 1_000_000))
                        result = LKRTCVideoFrame(buffer: LKRTCCVPixelBuffer(pixelBuffer: output), rotation: payload.frame.rotation, timeStampNs: payload.frame.timeStampNs)
                        result.timeStamp = payload.frame.timeStamp
                    } else {
                        // Only the user's explicit anonymization-off control permits raw output.
                        result = payload.frame
                    }
                    gate.finish(ticket) {
                        guard !ticket.enabled || libraryRevision == PrivacyFaceLibrary.revision else { return }
                        #if DEBUG
                        PrivacyUplinkValidationMetrics.recordProcessed(enabled: ticket.enabled)
                        #endif
                        deliver(result)
                    }
                } catch {
                    report(.failure)
                    gate.finish(ticket) { onError(error.localizedDescription) }
                    // No raw fallback when model loading, inference or rendering fails.
                }
            }
        }
    }

    static func orientation(rotation: Int) -> (forward: CGImagePropertyOrientation, reverse: CGImagePropertyOrientation) {
        switch rotation {
        case 90: (.right, .left)
        case 180: (.down, .down)
        case 270: (.left, .right)
        default: (.up, .up)
        }
    }
}

nonisolated enum PrivacyFrameMetric: Sendable {
    case inference(milliseconds: Double)
    case failure
}

nonisolated private struct FramePayload: @unchecked Sendable { let frame: LKRTCVideoFrame }

#if DEBUG
nonisolated enum PrivacyUplinkValidationMetrics {
    private final class State: @unchecked Sendable {
        let lock = NSLock()
        var processed = 0
        var raw = 0
    }
    private static let state = State()
    static func recordProcessed(enabled: Bool) {
        state.lock.withLock {
            if enabled { state.processed += 1 } else { state.raw += 1 }
        }
    }
    static var counts: (processed: Int, raw: Int) {
        state.lock.withLock { (state.processed, state.raw) }
    }
}
#endif

/// Serializes the routing decision with delivery, including asynchronous AI results.
nonisolated final class PrivacyUplinkRoute: @unchecked Sendable {
    struct Ticket { let generation: UInt; let mode: AIProcessingMode }
    private let lock = NSLock()
    private var generation: UInt = 0
    private var mode: AIProcessingMode
    private var stopped = false
    init(mode: AIProcessingMode) { self.mode = mode }
    func ticket() -> Ticket? {
        lock.withLock { stopped ? nil : Ticket(generation: generation, mode: mode) }
    }
    func change(to mode: AIProcessingMode) {
        lock.withLock { generation &+= 1; self.mode = mode }
    }
    func stop() { lock.withLock { generation &+= 1; stopped = true } }
    func deliver(_ ticket: Ticket, _ body: () -> Void) {
        lock.withLock {
            guard !stopped, ticket.generation == generation else { return }
            body()
        }
    }
}

/// Used on the processing queue. Core Video only reuses buffers released by every consumer.
nonisolated final class PrivacyOutputBufferPool {
    private var pool: CVPixelBufferPool?
    private var dimensions: CGSize = .zero
    private let pixelFormat: OSType
    init(pixelFormat: OSType = kCVPixelFormatType_32BGRA) { self.pixelFormat = pixelFormat }
    func acquire(width: Int, height: Int) throws -> CVPixelBuffer {
        guard width > 0, height > 0 else { throw PrivacyModelError.imageBuffer }
        let size = CGSize(width: width, height: height)
        if pool == nil || dimensions != size {
            var next: CVPixelBufferPool?
            let attributes: [CFString: Any] = [kCVPixelBufferWidthKey: width, kCVPixelBufferHeightKey: height,
                kCVPixelBufferPixelFormatTypeKey: pixelFormat,
                kCVPixelBufferIOSurfacePropertiesKey: [:], kCVPixelBufferMetalCompatibilityKey: true]
            guard CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &next) == kCVReturnSuccess,
                  let next else { throw PrivacyModelError.imageBuffer }
            pool = next
            dimensions = size
        }
        var output: CVPixelBuffer?
        guard let pool,
              CVPixelBufferPoolCreatePixelBufferWithAuxAttributes(kCFAllocatorDefault, pool,
                [kCVPixelBufferPoolAllocationThresholdKey: 6] as CFDictionary, &output) == kCVReturnSuccess,
              let output else { throw PrivacyModelError.imageBuffer }
        return output
    }
}
