import AVFoundation
import CoreImage
import Foundation

nonisolated enum LocalBroadcastRecordingError: LocalizedError {
    case alreadyStarted, notStarted, noVideo, noAudio, audioOverflow, invalidAudio, conversion, nonMonotonicAudio
    case writer(String)
    var errorDescription: String? {
        switch self {
        case .alreadyStarted: String(localized: "이미 녹화를 시작했습니다.")
        case .notStarted: String(localized: "녹화를 시작하지 않았습니다.")
        case .noVideo: String(localized: "저장할 영상 프레임이 없습니다.")
        case .noAudio: String(localized: "음성을 받지 못해 녹화를 완료하지 못했습니다.")
        case .audioOverflow: String(localized: "저장 속도가 느려 음성을 기록하지 못했습니다.")
        case .invalidAudio: String(localized: "녹화 음성 형식이 올바르지 않습니다.")
        case .conversion: String(localized: "녹화 프레임을 변환하지 못했습니다.")
        case .nonMonotonicAudio: String(localized: "녹화 음성 시간 정보가 겹칩니다.")
        case let .writer(message): message
        }
    }
}

nonisolated struct LocalBroadcastRecordingResult: Sendable {
    let url: URL
    let videoFrames: Int
    let audioFrames: Int
    let droppedVideoFrames: Int
    let duration: TimeInterval
}

/// A recorder instance belongs to one broadcast. All writer state lives on its queue.
nonisolated final class LocalBroadcastRecorder: @unchecked Sendable {
    private enum Phase { case idle, recording, finishing, finished }
    private struct AudioPacket: @unchecked Sendable { let buffer: AVAudioPCMBuffer; let time: CMTime }
    private struct VideoPacket: @unchecked Sendable { let buffer: CVPixelBuffer; let rotation: Int; let time: CMTime }
    #if DEBUG
    var validationDroppedVideoFrames: Int { lock.withLock { dropped } }
    private var validationVideoAdmission: (@Sendable (CVPixelBuffer, Int, Int64) -> Void)?
    func setValidationVideoAdmission(_ observer: (@Sendable (CVPixelBuffer, Int, Int64) -> Void)?) {
        lock.withLock { validationVideoAdmission = observer }
    }
    #endif
    private let lock = NSLock()
    private let admittedCallbacks = DispatchGroup()
    private let queue = DispatchQueue(label: "com.innolive.recording.writer", qos: .utility)
    private var phase = Phase.idle
    private var pendingVideo = 0
    private var pendingAudio = 0
    private var pendingAudioFrames = 0
    private var admissionFailure: LocalBroadcastRecordingError?
    private var admissionFailureScheduled = false
    private var dropped = 0
    private let maxPendingVideo = 4
    private let maxPendingAudio = 200
    private let maxPendingAudioFrames = 96_000
    private var epoch = CMTime.zero
    private var outputURL: URL?
    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var failure: Error?
    private let onFailure: @Sendable (String) -> Void
    private var hasReportedFailure = false

    init(onFailure: @escaping @Sendable (String) -> Void = { _ in }) {
        self.onFailure = onFailure
    }
    private var audioPackets: [AudioPacket] = []
    private var audioPump: DispatchSourceTimer?
    private var stopContinuation: CheckedContinuation<LocalBroadcastRecordingResult, Error>?
    private var stopDeadline: DispatchTime?
    private var isFinishingWriter = false
    private let converter = DirectFrameConverter()
    private let context = CIContext(options: [.cacheIntermediates: false])
    private var dimensions: CMVideoDimensions?
    private var fittedBufferPool: CVPixelBufferPool?
    private var videoFrames = 0
    private var audioFrames = 0
    private var lastVideoTime = CMTime.invalid
    private var lastAudioEnd = CMTime.invalid
    private var lastMediaEnd = CMTime.zero

    /// A supplied epoch is useful for deterministic tests. Production uses the host clock.
    func start(url: URL, epoch: CMTime = CMClockGetTime(CMClockGetHostTimeClock())) throws {
        try lock.withLock {
            guard phase == .idle else { throw LocalBroadcastRecordingError.alreadyStarted }
            self.epoch = epoch
            outputURL = url
            phase = .recording
        }
    }

    func appendVideo(pixelBuffer: CVPixelBuffer, rotation: Int, timestampNs: Int64) {
        let accepted = lock.withLock { () -> Bool in
            guard phase == .recording, admissionFailure == nil else { return false }
            guard pendingVideo < maxPendingVideo else { dropped += 1; return false }
            admittedCallbacks.enter()
            pendingVideo += 1
            return true
        }
        guard accepted else { scheduleAdmissionFailureIfNeeded(); return }
        defer { admittedCallbacks.leave() }
        #if DEBUG
        let observer = lock.withLock { validationVideoAdmission }
        observer?(pixelBuffer, rotation, timestampNs)
        #endif
        let packet = VideoPacket(buffer: pixelBuffer, rotation: rotation,
            time: CMTime(value: timestampNs, timescale: 1_000_000_000))
        queue.async { [self] in
            defer { lock.withLock { pendingVideo -= 1 } }
            guard failure == nil else { return }
            do { try writeVideo(packet) } catch { failRecording(error) }
        }
    }

    /// Input must be normalized Float32, non-interleaved, 48 kHz stereo.
    /// Copy before returning because capture engines reuse their memory.
    func appendAudio(_ buffer: AVAudioPCMBuffer, when: AVAudioTime) {
        let accepted = lock.withLock { () -> Bool in
            guard phase == .recording, admissionFailure == nil else { return false }
            guard buffer.frameLength > 0, buffer.frameLength <= 48_000 else { admissionFailure = .invalidAudio; return false }
            guard pendingAudio < maxPendingAudio,
                  pendingAudioFrames + Int(buffer.frameLength) <= maxPendingAudioFrames else {
                admissionFailure = .audioOverflow; return false
            }
            admittedCallbacks.enter()
            pendingAudio += 1
            pendingAudioFrames += Int(buffer.frameLength)
            return true
        }
        guard accepted else { scheduleAdmissionFailureIfNeeded(); return }
        defer { admittedCallbacks.leave() }
        guard when.isHostTimeValid, buffer.format.commonFormat == .pcmFormatFloat32,
              !buffer.format.isInterleaved, buffer.format.channelCount == 2,
              buffer.format.sampleRate == 48_000, buffer.frameLength > 0,
              let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength),
              let source = buffer.floatChannelData, let destination = copy.floatChannelData else {
            lock.withLock { pendingAudio -= 1; pendingAudioFrames -= Int(buffer.frameLength); admissionFailure = .invalidAudio }
            scheduleAdmissionFailureIfNeeded()
            return
        }
        copy.frameLength = buffer.frameLength
        for channel in 0..<2 { destination[channel].update(from: source[channel], count: Int(buffer.frameLength)) }
        let packet = AudioPacket(buffer: copy,
            time: CMTime(seconds: AVAudioTime.seconds(forHostTime: when.hostTime), preferredTimescale: 1_000_000_000))
        queue.async { [self] in
            guard failure == nil, CMTimeGetSeconds(CMTimeSubtract(packet.time, epoch)) >= -1.0 / 48_000 else {
                releaseAudio(packet); return
            }
            audioPackets.append(packet)
            startAudioPumpIfNeeded()
            pumpAudio()
        }
    }

    func stop() async throws -> LocalBroadcastRecordingResult {
        try lock.withLock {
            guard phase == .recording else { throw LocalBroadcastRecordingError.notStarted }
            phase = .finishing
        }
        return try await withCheckedThrowingContinuation { continuation in
            admittedCallbacks.notify(queue: queue) { [self] in
                stopContinuation = continuation
                stopDeadline = .now() + .seconds(15)
                pumpAudio()
                completeStopIfReady()
            }
        }
    }

    private func releaseAudio(_ packet: AudioPacket) {
        lock.withLock { pendingAudio -= 1; pendingAudioFrames -= Int(packet.buffer.frameLength) }
    }

    private func startAudioPumpIfNeeded() {
        guard audioPump == nil, !audioPackets.isEmpty else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(10), repeating: .milliseconds(10), leeway: .milliseconds(2))
        timer.setEventHandler { [weak self] in
            self?.pumpAudio()
            self?.completeStopIfReady()
        }
        audioPump = timer
        timer.resume()
    }

    /// Drain a bounded batch. Not-ready is temporary backpressure, not a capture failure.
    private func pumpAudio() {
        if failure == nil, let admissionFailure = lock.withLock({ admissionFailure }) { failRecording(admissionFailure) }
        guard failure == nil else {
            audioPump?.cancel(); audioPump = nil
            return
        }
        guard let writer, let audioInput else { return }
        guard writer.status == .writing else {
            failRecording(writer.error ?? LocalBroadcastRecordingError.conversion)
            return
        }
        do {
            for _ in 0..<16 {
                guard let packet = audioPackets.first, audioInput.isReadyForMoreMediaData else { break }
                let rawTime = CMTimeMaximum(.zero, CMTimeSubtract(packet.time, epoch))
                let covered = lastAudioEnd.isValid ? lastAudioEnd : CMTime.zero
                let delta = CMTimeGetSeconds(CMTimeSubtract(rawTime, covered))
                // Suppress small host timestamp jitter without repeatedly inserting tiny silences.
                // Real route interruptions larger than 3 ms retain their wall-clock duration.
                if delta < -0.003 { throw LocalBroadcastRecordingError.nonMonotonicAudio }
                if delta > 0.003 {
                    let count = AVAudioFrameCount(min(4_800, max(1, Int64((delta * 48_000).rounded()))))
                    guard let silence = AVAudioPCMBuffer(pcmFormat: packet.buffer.format, frameCapacity: count),
                          let channels = silence.floatChannelData else { throw LocalBroadcastRecordingError.conversion }
                    silence.frameLength = count
                    for channel in 0..<2 { channels[channel].initialize(repeating: 0, count: Int(count)) }
                    try writeAudioBuffer(silence, start: covered, isSilence: true)
                } else {
                    try writeAudioBuffer(packet.buffer, start: covered, isSilence: false)
                    audioPackets.removeFirst()
                    releaseAudio(packet)
                }
            }
        } catch { failRecording(error) }
        if audioPackets.isEmpty {
            audioPump?.cancel(); audioPump = nil
        }
    }

    /// Admission may run on the real-time capture thread. Only enqueue one failure report.
    private func scheduleAdmissionFailureIfNeeded() {
        let error = lock.withLock { () -> LocalBroadcastRecordingError? in
            guard !admissionFailureScheduled, let admissionFailure else { return nil }
            admissionFailureScheduled = true
            return admissionFailure
        }
        if let error { queue.async { [self] in failRecording(error) } }
    }

    private func reportFailureOnce(_ error: Error) {
        guard !hasReportedFailure else { return }
        hasReportedFailure = true
        onFailure(error.localizedDescription)
    }

    private func failRecording(_ error: Error) {
        if failure == nil { failure = error }
        lock.withLock {
            if admissionFailure == nil { admissionFailure = .writer(error.localizedDescription) }
            admissionFailureScheduled = true
        }
        audioPump?.cancel(); audioPump = nil
        audioPackets.forEach(releaseAudio); audioPackets = []
        reportFailureOnce(failure ?? error)
    }

    private func completeStopIfReady() {
        guard let continuation = stopContinuation, !isFinishingWriter else { return }
        if failure == nil, videoFrames == 0 { failure = LocalBroadcastRecordingError.noVideo }
        if failure == nil, !audioPackets.isEmpty {
            if let stopDeadline, DispatchTime.now() > stopDeadline {
                failure = LocalBroadcastRecordingError.writer(String(localized: "음성 저장을 마무리하는 시간이 초과되었습니다."))
            } else { startAudioPumpIfNeeded(); return }
        }
        if failure == nil, audioFrames == 0 { failure = LocalBroadcastRecordingError.noAudio }
        audioPump?.cancel(); audioPump = nil
        if let failure {
            reportFailureOnce(failure)
            writer?.cancelWriting()
            audioPackets.forEach(releaseAudio); audioPackets = []
            lock.withLock { phase = .finished }
            stopContinuation = nil
            continuation.resume(throwing: failure)
            return
        }
        guard let writer, let outputURL else { return }
        isFinishingWriter = true
        videoInput?.markAsFinished(); audioInput?.markAsFinished()
        writer.finishWriting { [self] in
            queue.async { [self] in
                lock.withLock { phase = .finished }
                stopContinuation = nil
                guard self.writer?.status == .completed else {
                    let error = self.writer?.error ?? LocalBroadcastRecordingError.conversion
                    reportFailureOnce(error)
                    continuation.resume(throwing: error)
                    return
                }
                continuation.resume(returning: LocalBroadcastRecordingResult(url: outputURL,
                    videoFrames: videoFrames, audioFrames: audioFrames,
                    droppedVideoFrames: lock.withLock { dropped }, duration: CMTimeGetSeconds(lastMediaEnd)))
            }
        }
    }

    private func makeWriter(width: Int, height: Int) throws {
        guard let outputURL else { throw LocalBroadcastRecordingError.notStarted }
        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
        let video = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width, AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: min(12_000_000, max(2_000_000, width * height * 4))]
        ])
        let audio = AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 48_000,
            AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 128_000
        ])
        video.expectsMediaDataInRealTime = true
        audio.expectsMediaDataInRealTime = true
        guard writer.canAdd(video), writer.canAdd(audio) else { throw LocalBroadcastRecordingError.conversion }
        writer.add(video); writer.add(audio)
        guard writer.startWriting() else { throw writer.error ?? LocalBroadcastRecordingError.conversion }
        writer.startSession(atSourceTime: .zero)
        self.writer = writer; videoInput = video; audioInput = audio
        dimensions = CMVideoDimensions(width: Int32(width), height: Int32(height))
    }

    private func writeVideo(_ packet: VideoPacket) throws {
        let relativeTime = CMTimeSubtract(packet.time, epoch)
        guard CMTimeGetSeconds(relativeTime) >= -0.000_001 else { return }
        let time = CMTimeMaximum(.zero, relativeTime)
        guard !lastVideoTime.isValid || time > lastVideoTime else { return }
        guard let rotated = converter.sampleBuffer(from: packet.buffer, rotation: packet.rotation, presentationTime: time),
              let pixel = CMSampleBufferGetImageBuffer(rotated) else { throw LocalBroadcastRecordingError.conversion }
        if writer == nil {
            try makeWriter(width: CVPixelBufferGetWidth(pixel), height: CVPixelBufferGetHeight(pixel))

        }
        guard let writer, let videoInput else { throw LocalBroadcastRecordingError.conversion }
        guard writer.status == .writing else { throw writer.error ?? LocalBroadcastRecordingError.conversion }
        guard videoInput.isReadyForMoreMediaData else { lock.withLock { dropped += 1 }; return }
        let sample: CMSampleBuffer
        if let dimensions, (Int(dimensions.width) != CVPixelBufferGetWidth(pixel) || Int(dimensions.height) != CVPixelBufferGetHeight(pixel)) {
            let normalized = try fitted(pixel, width: Int(dimensions.width), height: Int(dimensions.height))
            guard let converted = DirectFrameConverter.makeSampleBuffer(normalized, presentationTime: time) else { throw LocalBroadcastRecordingError.conversion }
            sample = converted
        } else { sample = rotated }
        guard videoInput.append(sample) else { throw writer.error ?? LocalBroadcastRecordingError.conversion }
        videoFrames += 1; lastVideoTime = time
        lastMediaEnd = CMTimeMaximum(lastMediaEnd, time)
        pumpAudio()
    }

    private func writeAudioBuffer(_ buffer: AVAudioPCMBuffer, start: CMTime, isSilence: Bool) throws {
        guard let writer, let audioInput else { throw LocalBroadcastRecordingError.conversion }
        var description: CMAudioFormatDescription?
        guard CMAudioFormatDescriptionCreate(allocator: kCFAllocatorDefault, asbd: buffer.format.streamDescription,
            layoutSize: 0, layout: nil, magicCookieSize: 0, magicCookie: nil,
            extensions: nil, formatDescriptionOut: &description) == noErr, let description else { throw LocalBroadcastRecordingError.conversion }
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 48_000), presentationTimeStamp: start, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreate(allocator: kCFAllocatorDefault, dataBuffer: nil, dataReady: false,
            makeDataReadyCallback: nil, refcon: nil, formatDescription: description,
            sampleCount: Int(buffer.frameLength), sampleTimingEntryCount: 1, sampleTimingArray: &timing,
            sampleSizeEntryCount: 0, sampleSizeArray: nil, sampleBufferOut: &sample) == noErr,
            let sample,
            CMSampleBufferSetDataBufferFromAudioBufferList(sample, blockBufferAllocator: kCFAllocatorDefault,
                blockBufferMemoryAllocator: kCFAllocatorDefault, flags: 0, bufferList: buffer.audioBufferList) == noErr,
            CMSampleBufferSetDataReady(sample) == noErr else { throw LocalBroadcastRecordingError.conversion }
        guard audioInput.append(sample) else { throw writer.error ?? LocalBroadcastRecordingError.conversion }
        if !isSilence { audioFrames += Int(buffer.frameLength) }
        lastAudioEnd = CMTimeAdd(start, CMTime(value: Int64(buffer.frameLength), timescale: 48_000))
        lastMediaEnd = CMTimeMaximum(lastMediaEnd, lastAudioEnd)
    }

    private func fitted(_ buffer: CVPixelBuffer, width: Int, height: Int) throws -> CVPixelBuffer {
        // The writer's output dimensions stay fixed for this recorder's lifetime.
        // The pool reuses buffers only after their previous writer references are released.
        if fittedBufferPool == nil {
            let attributes: [CFString: Any] = [
                kCVPixelBufferWidthKey: width,
                kCVPixelBufferHeightKey: height,
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                kCVPixelBufferIOSurfacePropertiesKey: [:]
            ]
            guard CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary,
                                          &fittedBufferPool) == kCVReturnSuccess else {
                throw LocalBroadcastRecordingError.conversion
            }
        }
        var output: CVPixelBuffer?
        guard let fittedBufferPool,
              CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, fittedBufferPool, &output) == kCVReturnSuccess,
              let output else { throw LocalBroadcastRecordingError.conversion }
        let image = CIImage(cvPixelBuffer: buffer)
        let scale = min(CGFloat(width) / image.extent.width, CGFloat(height) / image.extent.height)
        let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let x = (CGFloat(width) - scaled.extent.width) / 2
        let y = (CGFloat(height) - scaled.extent.height) / 2
        let canvas = CGRect(x: 0, y: 0, width: width, height: height)
        let composed = scaled.transformed(by: CGAffineTransform(translationX: x, y: y))
            .composited(over: CIImage(color: .black).cropped(to: canvas))
        context.render(composed, to: output, bounds: canvas, colorSpace: CGColorSpaceCreateDeviceRGB())
        return output
    }
}
