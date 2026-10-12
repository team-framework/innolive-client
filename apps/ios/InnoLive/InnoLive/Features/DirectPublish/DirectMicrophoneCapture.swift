import AVFoundation

/// 앱 직접 송출에 넣는 음성 입력이다. 캡처 스레드에서 송출 형식의 버퍼를 넘긴다.
nonisolated protocol DirectAudioCapturing: AnyObject, Sendable {
    /// 녹음을 시작하지 못하면 false다. 송출은 무음으로 이어간다.
    func start(_ handler: @escaping @Sendable (AVAudioPCMBuffer, AVAudioTime) -> Void) -> Bool
    func stop()
}

/// 직접 송출 세션의 마이크 녹음이다.
/// 서버가 직접 송출 세션의 음성을 쓰지 않으므로 WebRTC에는 마이크를 넘기지 않고 앱이 녹음한다.
/// WebRTC 음성 처리 모듈의 후처리 콜백은 실기기에서 호출되지 않아 쓰지 않는다(2026-10-12 iPhone 15 측정).
nonisolated final class DirectMicrophoneCapture: DirectAudioCapturing, @unchecked Sendable {
    private static let restartDelay: DispatchTimeInterval = .milliseconds(500)
    /// 이 시간 동안 버퍼가 없으면 엔진을 다시 연다. 오디오 세션 재설정 뒤 알림 없이 멈추는 경우가 있다.
    private static let watchdogInterval: TimeInterval = 2

    private let queue = DispatchQueue(label: "com.innolive.direct-publish.microphone")
    private let lock = NSLock()
    private let resampler = DirectAudioResampler()
    private var engine: AVAudioEngine?
    private var handler: (@Sendable (AVAudioPCMBuffer, AVAudioTime) -> Void)?
    private var engineObserver: NSObjectProtocol?
    private var sessionObservers: [NSObjectProtocol] = []
    private var watchdog: DispatchSourceTimer?
    private var lastBufferAt = Date.distantPast

    func start(_ handler: @escaping @Sendable (AVAudioPCMBuffer, AVAudioTime) -> Void) -> Bool {
        queue.sync {
            lock.withLock {
                self.handler = handler
                lastBufferAt = Date()
            }
            if sessionObservers.isEmpty { observeSession() }
            startWatchdog()
            return startEngine()
        }
    }

    func stop() {
        queue.sync {
            lock.withLock { handler = nil }
            watchdog?.cancel()
            watchdog = nil
            sessionObservers.forEach(NotificationCenter.default.removeObserver)
            sessionObservers = []
            stopEngine()
        }
    }

    private func startWatchdog() {
        guard watchdog == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + Self.watchdogInterval, repeating: Self.watchdogInterval)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            let stalled = self.lock.withLock { self.handler != nil && Date().timeIntervalSince(self.lastBufferAt) > Self.watchdogInterval }
            if stalled {
                self.lock.withLock { self.lastBufferAt = Date() }
                _ = self.startEngine()
            }
        }
        watchdog = timer
        timer.resume()
    }

    private func startEngine() -> Bool {
        stopEngine()
        // 입력 경로가 바뀌면 이전 엔진의 입력 형식이 맞지 않으므로 엔진을 새로 만든다.
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return false }
        input.installTap(onBus: 0, bufferSize: 1_024, format: format) { [weak self] buffer, time in
            self?.deliver(buffer, time: time)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            return false
        }
        self.engine = engine
        engineObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil
        ) { [weak self] _ in
            self?.scheduleRestart()
        }
        return true
    }

    private func stopEngine() {
        if let engineObserver { NotificationCenter.default.removeObserver(engineObserver) }
        engineObserver = nil
        engine?.inputNode.removeTap(onBus: 0)
        engine?.stop()
        engine = nil
        resampler.reset()
    }

    /// 경로 변경·통화 중단 뒤에는 엔진이 멈춘다. 끊긴 동안은 송출 쪽이 무음을 채운다.
    private func scheduleRestart() {
        queue.asyncAfter(deadline: .now() + Self.restartDelay) { [weak self] in
            guard let self, self.lock.withLock({ self.handler != nil }) else { return }
            _ = self.startEngine()
        }
    }

    private func observeSession() {
        let center = NotificationCenter.default
        sessionObservers = [
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] notification in
                let type = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                    .flatMap(AVAudioSession.InterruptionType.init(rawValue:))
                if type == .ended { self?.scheduleRestart() }
            },
            center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: nil) { [weak self] _ in
                self?.scheduleRestart()
            }
        ]
    }

    private func deliver(_ buffer: AVAudioPCMBuffer, time: AVAudioTime) {
        let handler = lock.withLock {
            lastBufferAt = Date()
            return self.handler
        }
        guard let handler, let converted = resampler.convert(buffer) else { return }
        handler(converted, AVAudioTime(hostTime: time.isHostTimeValid ? time.hostTime : mach_absolute_time()))
    }
}

/// 마이크 입력을 송출 형식으로 바꾼다. 블루투스 등으로 샘플레이트가 바뀌어도 AAC 인코더 설정은 그대로다.
nonisolated final class DirectAudioResampler: @unchecked Sendable {
    /// 송출 음성 형식이다. YouTube 권장값에 맞춰 48kHz 스테레오로 보낸다.
    static let outputFormat = AVAudioFormat(
        commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false
    )!
    private static let monoFormat = AVAudioFormat(
        commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 1, interleaved: false
    )!

    private let lock = NSLock()
    private var converter: AVAudioConverter?
    private var inputFormat: AVAudioFormat?

    func reset() {
        lock.withLock {
            converter = nil
            inputFormat = nil
        }
    }

    func convert(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        guard buffer.frameLength > 0, buffer.format.sampleRate > 0 else { return nil }
        lock.lock()
        defer { lock.unlock() }
        if inputFormat != buffer.format {
            converter = AVAudioConverter(from: buffer.format, to: Self.monoFormat)
            converter?.downmix = true
            inputFormat = buffer.format
        }
        guard let converter else { return nil }
        let ratio = Self.monoFormat.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 64
        guard let mono = AVAudioPCMBuffer(pcmFormat: Self.monoFormat, frameCapacity: capacity) else { return nil }
        var supplied = false
        var error: NSError?
        let status = converter.convert(to: mono, error: &error) { _, inputStatus in
            if supplied {
                inputStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            inputStatus.pointee = .haveData
            return buffer
        }
        guard status != .error, mono.frameLength > 0,
              let stereo = AVAudioPCMBuffer(pcmFormat: Self.outputFormat, frameCapacity: mono.frameLength),
              let source = mono.floatChannelData, let destination = stereo.floatChannelData else { return nil }
        stereo.frameLength = mono.frameLength
        let count = Int(mono.frameLength)
        destination[0].update(from: source[0], count: count)
        destination[1].update(from: source[0], count: count)
        return stereo
    }
}
