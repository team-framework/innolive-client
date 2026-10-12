import AVFoundation
@preconcurrency import LiveKitWebRTC

/// WebRTC가 처리한 마이크 음성을 앱 직접 송출로도 넘긴다.
/// 마이크는 WebRTC 오디오 장치가 쥐고 있으므로 따로 녹음하지 않고 송신 직전 버퍼를 복사한다.
nonisolated final class DirectAudioCaptureTap: NSObject, LKRTCAudioCustomProcessingDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private weak var sink: (any DirectMediaSink)?
    private var format: AVAudioFormat?

    func setSink(_ sink: (any DirectMediaSink)?) {
        lock.lock()
        self.sink = sink
        lock.unlock()
    }

    func audioProcessingInitialize(sampleRate sampleRateHz: Int, channels: Int) {
        lock.lock()
        format = nil
        lock.unlock()
    }

    func audioProcessingProcess(audioBuffer: LKRTCAudioBuffer) {
        lock.lock()
        let sink = sink
        lock.unlock()
        guard let sink, sink.acceptsMedia,
              let buffer = makeBuffer(from: audioBuffer) else { return }
        sink.appendAudio(buffer, when: AVAudioTime(hostTime: mach_absolute_time()))
    }

    func audioProcessingRelease() {}

    private func makeBuffer(from audioBuffer: LKRTCAudioBuffer) -> AVAudioPCMBuffer? {
        let frames = audioBuffer.frames
        let channels = audioBuffer.channels
        guard frames > 0, channels > 0 else { return nil }
        // WebRTC는 10ms 단위로 처리하므로 프레임 수 × 100이 샘플레이트다.
        let sampleRate = Double(frames * 100)
        lock.lock()
        var format = self.format
        if format?.sampleRate != sampleRate || format?.channelCount != AVAudioChannelCount(channels) {
            format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate,
                                   channels: AVAudioChannelCount(channels), interleaved: false)
            self.format = format
        }
        lock.unlock()
        guard let format, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)),
              let destination = buffer.floatChannelData else { return nil }
        buffer.frameLength = AVAudioFrameCount(frames)
        // WebRTC 내부 float 버퍼는 Int16 범위(-32768...32767)를 쓴다.
        let scale: Float = 1.0 / 32_768
        for channel in 0..<channels {
            let source = audioBuffer.rawBuffer(forChannel: channel)
            let target = destination[channel]
            for frame in 0..<frames {
                target[frame] = min(max(source[frame] * scale, -1), 1)
            }
        }
        return buffer
    }
}
