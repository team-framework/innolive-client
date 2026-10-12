import AVFoundation
import CoreMedia
import VideoToolbox

/// 송출 프레임을 받는 쪽이다. 캡처 스레드에서 호출된다.
nonisolated protocol DirectMediaSink: AnyObject, Sendable {
    /// 연결된 송출 대상이 없으면 변환 비용을 들이지 않는다.
    var acceptsMedia: Bool { get }
    /// 송출 대상이 없어도 호출한다. 첫 연결 전에 인코더 크기를 알아야 하기 때문이다.
    func appendVideo(pixelBuffer: CVPixelBuffer, rotation: Int, timestampNs: Int64)
    func recordInference(milliseconds: Double)
    func recordAnonymizationFailure()
}

/// RTMP 연결 하나가 받는 미디어다.
nonisolated protocol DirectMediaOutput: AnyObject, Sendable {
    func appendVideo(_ sampleBuffer: CMSampleBuffer)
    func appendAudio(_ buffer: AVAudioPCMBuffer, when: AVAudioTime)
}

/// 가림 처리가 끝난 송출 프레임과 마이크 음성을 대상별 RTMP 연결에 나눠 준다.
/// 일시 중지한 대상에는 원본 대신 검은 화면과 무음을 보낸다. 백그라운드에서 카메라가
/// 멈춰도 연결이 끊기지 않도록 일시 중지 화면은 자체 타이머로 만든다.
/// YouTube는 음성 트랙이 없으면 스트림을 활성으로 보지 않아 라이브 전환을 거절한다.
/// 마이크 음성이 끊기면 그 구간을 무음으로 채운다.
nonisolated final class DirectMediaFanout: DirectMediaSink, @unchecked Sendable {
    static let slateFramesPerSecond = 15
    /// 송출 음성 형식이다. 마이크 음성과 무음이 같은 형식이어야 AAC 인코더가 다시 설정되지 않는다.
    static let audioFormat = DirectAudioResampler.outputFormat
    static let audioKeeperInterval: TimeInterval = 0.05
    /// 이 시간 동안 마이크 음성이 없으면 무음을 채운다.
    static let microphoneStaleInterval: TimeInterval = 0.3
    /// 무음 버퍼 하나의 길이다. HaishinKit 링 버퍼(16,384프레임)를 넘지 않게 나눠 보낸다.
    static let silenceChunkDuration: TimeInterval = 0.02

    let metrics = DirectPublishMetrics()
    private let lock = NSLock()
    private let conversionLock = NSLock()
    private let converter = DirectFrameConverter()
    private let slateQueue = DispatchQueue(label: "com.innolive.direct-publish.slate")
    private var outputs: [String: any DirectMediaOutput] = [:]
    private var pausedProviders: Set<String> = []
    private var slateTimer: DispatchSourceTimer?
    private var audioKeeperTimer: DispatchSourceTimer?
    private var videoDimensions: CMVideoDimensions?
    private var lastMicrophoneAudioAt: TimeInterval = 0
    private var audioCoveredUntil: TimeInterval = 0

    var acceptsMedia: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !outputs.isEmpty
    }

    /// 최근 1초 안에 마이크 음성이 들어왔다.
    var isReceivingMicrophone: Bool {
        lock.lock()
        defer { lock.unlock() }
        return Self.hostSeconds() - lastMicrophoneAudioAt < 1
    }

    /// 회전을 적용한 최근 송출 프레임 크기다. 인코더 크기를 처음 정할 때 쓴다.
    var latestVideoDimensions: CMVideoDimensions? {
        lock.lock()
        defer { lock.unlock() }
        return videoDimensions
    }

    func attach(_ output: any DirectMediaOutput, provider: String) {
        lock.lock()
        if outputs.isEmpty { audioCoveredUntil = Self.hostSeconds() }
        outputs[provider] = output
        lock.unlock()
        updateSlateTimer()
        updateAudioKeeperTimer()
    }

    func detach(provider: String) {
        lock.lock()
        outputs.removeValue(forKey: provider)
        pausedProviders.remove(provider)
        lock.unlock()
        updateSlateTimer()
        updateAudioKeeperTimer()
    }

    func setPaused(_ paused: Bool, provider: String) {
        lock.lock()
        if paused { pausedProviders.insert(provider) } else { pausedProviders.remove(provider) }
        lock.unlock()
        updateSlateTimer()
    }

    func isPaused(provider: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return pausedProviders.contains(provider)
    }

    func appendVideo(pixelBuffer: CVPixelBuffer, rotation: Int, timestampNs: Int64) {
        let dimensions = DirectFrameConverter.rotatedDimensions(
            width: CVPixelBufferGetWidth(pixelBuffer), height: CVPixelBufferGetHeight(pixelBuffer), rotation: rotation
        )
        lock.lock()
        videoDimensions = dimensions
        let targets = outputs.filter { !pausedProviders.contains($0.key) }.map(\.value)
        lock.unlock()
        guard !targets.isEmpty else { return }

        let presentationTime = CMTime(value: timestampNs, timescale: 1_000_000_000)
        conversionLock.lock()
        let sample = converter.sampleBuffer(from: pixelBuffer, rotation: rotation, presentationTime: presentationTime)
        conversionLock.unlock()
        guard let sample else {
            metrics.recordDroppedFrame()
            return
        }
        metrics.recordVideoFrame(width: Int(dimensions.width), height: Int(dimensions.height))
        targets.forEach { $0.appendVideo(sample) }
    }

    func recordInference(milliseconds: Double) {
        guard acceptsMedia else { return }
        metrics.recordInference(milliseconds: milliseconds)
    }

    func recordAnonymizationFailure() {
        guard acceptsMedia else { return }
        metrics.recordAnonymizationFailure()
    }

    /// 마이크 음성이다. `audioFormat` 형식이어야 한다.
    func appendAudio(_ buffer: AVAudioPCMBuffer, when: AVAudioTime) {
        lock.lock()
        let now = Self.hostSeconds()
        lastMicrophoneAudioAt = now
        audioCoveredUntil = now
        let targets = outputs.filter { !pausedProviders.contains($0.key) }.map(\.value)
        lock.unlock()
        targets.forEach { $0.appendAudio(buffer, when: when) }
    }

    private func updateAudioKeeperTimer() {
        lock.lock()
        let needsKeeper = !outputs.isEmpty
        let running = audioKeeperTimer != nil
        lock.unlock()
        guard needsKeeper != running else { return }
        if needsKeeper {
            let timer = DispatchSource.makeTimerSource(queue: slateQueue)
            timer.schedule(deadline: .now() + Self.audioKeeperInterval, repeating: Self.audioKeeperInterval,
                           leeway: .milliseconds(5))
            timer.setEventHandler { [weak self] in self?.fillMissingAudio() }
            lock.lock()
            audioKeeperTimer = timer
            lock.unlock()
            timer.resume()
        } else {
            lock.lock()
            let timer = audioKeeperTimer
            audioKeeperTimer = nil
            lock.unlock()
            timer?.cancel()
        }
    }

    private func fillMissingAudio() {
        lock.lock()
        let now = Self.hostSeconds()
        let targets = outputs.filter { !pausedProviders.contains($0.key) }.map(\.value)
        var chunks = 0
        if now - lastMicrophoneAudioAt > Self.microphoneStaleInterval, !targets.isEmpty {
            // 오래 멈췄던 타이머가 한꺼번에 큰 무음을 만들지 않도록 한 번에 0.5초까지만 채운다.
            audioCoveredUntil = max(audioCoveredUntil, now - 0.5)
            chunks = Int(((now - audioCoveredUntil) / Self.silenceChunkDuration).rounded(.down))
            // 남은 구간은 다음 주기로 넘긴다. 버리면 음성 시간축이 실제 시간보다 느려진다.
            audioCoveredUntil += Double(chunks) * Self.silenceChunkDuration
        }
        lock.unlock()
        guard chunks > 0, let silence = Self.silence(format: Self.audioFormat, duration: Self.silenceChunkDuration) else {
            return
        }
        for _ in 0..<chunks {
            let when = AVAudioTime(hostTime: mach_absolute_time())
            targets.forEach { $0.appendAudio(silence, when: when) }
        }
    }

    static func hostSeconds() -> TimeInterval {
        AVAudioTime.seconds(forHostTime: mach_absolute_time())
    }

    private func updateSlateTimer() {
        lock.lock()
        let needsSlate = !pausedProviders.intersection(outputs.keys).isEmpty
        let running = slateTimer != nil
        lock.unlock()
        guard needsSlate != running else { return }
        if needsSlate {
            let timer = DispatchSource.makeTimerSource(queue: slateQueue)
            let interval = 1.0 / Double(Self.slateFramesPerSecond)
            timer.schedule(deadline: .now(), repeating: interval, leeway: .milliseconds(5))
            timer.setEventHandler { [weak self] in self?.emitSlate() }
            lock.lock()
            slateTimer = timer
            lock.unlock()
            timer.resume()
        } else {
            lock.lock()
            let timer = slateTimer
            slateTimer = nil
            lock.unlock()
            timer?.cancel()
        }
    }

    private func emitSlate() {
        lock.lock()
        let targets = outputs.filter { pausedProviders.contains($0.key) }.map(\.value)
        // 송출 크기를 아직 모르면 검은 화면을 만들지 않는다. 다른 크기를 보내면 인코더가 다시 설정된다.
        let dimensions = videoDimensions
        lock.unlock()
        guard !targets.isEmpty else { return }

        let now = CMClockGetTime(CMClockGetHostTimeClock())
        conversionLock.lock()
        let video = dimensions.flatMap {
            converter.blackSampleBuffer(width: Int($0.width), height: Int($0.height), presentationTime: now)
        }
        conversionLock.unlock()
        let silence = Self.silence(format: Self.audioFormat, duration: 1.0 / Double(Self.slateFramesPerSecond))
        let when = AVAudioTime(hostTime: mach_absolute_time())
        for target in targets {
            if let video { target.appendVideo(video) }
            if let silence { target.appendAudio(silence, when: when) }
        }
    }

    static func silence(format: AVAudioFormat, duration: TimeInterval) -> AVAudioPCMBuffer? {
        let frames = AVAudioFrameCount((format.sampleRate * duration).rounded())
        guard frames > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { return nil }
        buffer.frameLength = frames
        if let channels = buffer.floatChannelData {
            for channel in 0..<Int(format.channelCount) {
                channels[channel].update(repeating: 0, count: Int(frames))
            }
        }
        return buffer
    }
}

/// WebRTC 프레임의 회전 정보를 실제 픽셀에 적용한다. RTMP·H.264에는 회전 메타데이터가 없다.
nonisolated final class DirectFrameConverter {
    private struct PoolKey: Hashable {
        let format: OSType
        let width: Int
        let height: Int
    }

    private var rotationSession: VTPixelRotationSession?
    private var sessionRotation = -1
    private var pools: [PoolKey: CVPixelBufferPool] = [:]
    private var blackBuffers: [PoolKey: CVPixelBuffer] = [:]

    deinit {
        if let rotationSession { VTPixelRotationSessionInvalidate(rotationSession) }
    }

    static func rotatedDimensions(width: Int, height: Int, rotation: Int) -> CMVideoDimensions {
        let swaps = rotation == 90 || rotation == 270
        return CMVideoDimensions(width: Int32(swaps ? height : width), height: Int32(swaps ? width : height))
    }

    func sampleBuffer(from pixelBuffer: CVPixelBuffer, rotation: Int, presentationTime: CMTime) -> CMSampleBuffer? {
        guard let upright = rotated(pixelBuffer, rotation: rotation) else { return nil }
        return Self.makeSampleBuffer(upright, presentationTime: presentationTime)
    }

    func blackSampleBuffer(width: Int, height: Int, presentationTime: CMTime) -> CMSampleBuffer? {
        guard width > 0, height > 0 else { return nil }
        let key = PoolKey(format: kCVPixelFormatType_32BGRA, width: width, height: height)
        let buffer: CVPixelBuffer
        if let cached = blackBuffers[key] {
            buffer = cached
        } else {
            guard let created = makeBuffer(for: key) else { return nil }
            CVPixelBufferLockBaseAddress(created, [])
            if let base = CVPixelBufferGetBaseAddress(created) {
                memset(base, 0, CVPixelBufferGetDataSize(created))
            }
            CVPixelBufferUnlockBaseAddress(created, [])
            blackBuffers = [key: created]
            buffer = created
        }
        return Self.makeSampleBuffer(buffer, presentationTime: presentationTime)
    }

    private func rotated(_ source: CVPixelBuffer, rotation: Int) -> CVPixelBuffer? {
        let normalized = ((rotation % 360) + 360) % 360
        guard normalized != 0 else { return source }
        guard let session = session(for: normalized) else { return nil }
        let dimensions = Self.rotatedDimensions(
            width: CVPixelBufferGetWidth(source), height: CVPixelBufferGetHeight(source), rotation: normalized
        )
        let key = PoolKey(
            format: CVPixelBufferGetPixelFormatType(source),
            width: Int(dimensions.width), height: Int(dimensions.height)
        )
        guard let destination = makeBuffer(for: key),
              VTPixelRotationSessionRotateImage(session, source, destination) == noErr else { return nil }
        return destination
    }

    private func session(for rotation: Int) -> VTPixelRotationSession? {
        if let rotationSession, sessionRotation == rotation { return rotationSession }
        if rotationSession == nil {
            var created: VTPixelRotationSession?
            guard VTPixelRotationSessionCreate(kCFAllocatorDefault, &created) == noErr,
                  let created else { return nil }
            rotationSession = created
        }
        guard let rotationSession else { return nil }
        // WebRTC 회전 값은 화면에 바로 보이기 위해 시계 방향으로 돌려야 하는 각도다.
        let value: CFString
        switch rotation {
        case 90: value = kVTRotation_CW90
        case 180: value = kVTRotation_180
        case 270: value = kVTRotation_CCW90
        default: value = kVTRotation_0
        }
        guard VTSessionSetProperty(rotationSession, key: kVTPixelRotationPropertyKey_Rotation, value: value) == noErr else {
            return nil
        }
        sessionRotation = rotation
        return rotationSession
    }

    private func makeBuffer(for key: PoolKey) -> CVPixelBuffer? {
        let pool: CVPixelBufferPool
        if let existing = pools[key] {
            pool = existing
        } else {
            let attributes: [CFString: Any] = [
                kCVPixelBufferWidthKey: key.width,
                kCVPixelBufferHeightKey: key.height,
                kCVPixelBufferPixelFormatTypeKey: key.format,
                kCVPixelBufferIOSurfacePropertiesKey: [:]
            ]
            var created: CVPixelBufferPool?
            guard CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &created) == kCVReturnSuccess,
                  let created else { return nil }
            // 회전·검은 화면 크기는 방송 중에 거의 바뀌지 않는다. 이전 크기의 풀은 버린다.
            pools = pools.filter { $0.key.width == key.width && $0.key.height == key.height }
            pools[key] = created
            pool = created
        }
        var buffer: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &buffer) == kCVReturnSuccess else { return nil }
        return buffer
    }

    static func makeSampleBuffer(_ pixelBuffer: CVPixelBuffer, presentationTime: CMTime) -> CMSampleBuffer? {
        var format: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault, imageBuffer: pixelBuffer, formatDescriptionOut: &format
        ) == noErr, let format else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: presentationTime, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault, imageBuffer: pixelBuffer, formatDescription: format,
            sampleTiming: &timing, sampleBufferOut: &sample
        ) == noErr else { return nil }
        return sample
    }
}
