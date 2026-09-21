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
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let onError: @Sendable (String) -> Void

    init(onError: @escaping @Sendable (String) -> Void) { self.onError = onError }
    func setEnabled(_ enabled: Bool) { gate.invalidate(enabled: enabled) }
    func reset() { gate.invalidate() }
    func stop() { gate.invalidate(stop: true) }

    func submit(_ frame: LKRTCVideoFrame, deliver: @escaping @Sendable (LKRTCVideoFrame) -> Void) {
        guard let ticket = gate.begin() else { return }
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
                        let orientation = Self.orientation(rotation: payload.frame.rotation.rawValue)
                        let original = CIImage(cvPixelBuffer: pixels.pixelBuffer).oriented(orientation.forward)
                        let upright = original.transformed(by: CGAffineTransform(translationX: -original.extent.minX, y: -original.extent.minY))
                        let rendered = try model!.process(upright).0
                        // Restore sensor dimensions; WebRTC still carries the original rotation metadata.
                        let restored = CIImage(cgImage: rendered).oriented(orientation.reverse)
                        let width = CVPixelBufferGetWidth(pixels.pixelBuffer), height = CVPixelBufferGetHeight(pixels.pixelBuffer)
                        var output: CVPixelBuffer?
                        guard CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA,
                                                  [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &output) == kCVReturnSuccess,
                              let output else { throw PrivacyModelError.imageBuffer }
                        let normalized = restored.transformed(by: CGAffineTransform(translationX: -restored.extent.minX, y: -restored.extent.minY))
                        context.render(normalized, to: output, bounds: CGRect(x: 0, y: 0, width: width, height: height), colorSpace: CGColorSpaceCreateDeviceRGB())
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
