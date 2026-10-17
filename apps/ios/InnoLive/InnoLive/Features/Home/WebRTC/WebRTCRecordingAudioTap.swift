import AVFoundation
@preconcurrency import LiveKitWebRTC

/// Copies the post-processing samples used by the WebRTC sender; never retains RTCAudioBuffer.
nonisolated final class WebRTCRecordingAudioTap: NSObject, LKRTCAudioCustomProcessingDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var binding: Binding?
    private var sampleRate = 0
    private var isMuted = false

    func setRecordingSink(_ recorder: LocalBroadcastRecorder?) {
        lock.withLock {
            // New recorder => new timeline/converter. Queued callbacks retain their old binding.
            binding = recorder.map(Binding.init)
        }
    }

    func setMuted(_ muted: Bool) { lock.withLock { isMuted = muted } }

    func audioProcessingInitialize(sampleRate: Int, channels: Int) {
        lock.withLock { self.sampleRate = sampleRate }
    }

    func audioProcessingRelease() {
        lock.withLock { sampleRate = 0 }
    }

    func audioProcessingProcess(audioBuffer: LKRTCAudioBuffer) {
        let snapshot = lock.withLock { (binding, sampleRate, isMuted) }
        guard let binding = snapshot.0, snapshot.1 > 0 else { return }
        binding.append(audioBuffer, sampleRate: snapshot.1, muted: snapshot.2)
    }

    nonisolated private final class Binding: @unchecked Sendable {
        let recorder: LocalBroadcastRecorder
        private let lock = NSLock()
        private let resampler = DirectAudioResampler()
        private var lastRate = 0
        private var anchor: Double?
        private var outputFrames: Int64 = 0

        init(_ recorder: LocalBroadcastRecorder) { self.recorder = recorder }

        func append(_ source: LKRTCAudioBuffer, sampleRate: Int, muted: Bool) {
            guard source.frames > 0, source.channels > 0, source.channels <= 2,
                  let format = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                      sampleRate: Double(sampleRate), channels: AVAudioChannelCount(source.channels), interleaved: false),
                  let input = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(source.frames)),
                  let channels = input.floatChannelData else { return }
            input.frameLength = AVAudioFrameCount(source.frames)
            // WebRTC AudioBuffer::channels() uses FloatS16, not normalized [-1, 1] floats.
            for channel in 0..<source.channels {
                let raw = source.rawBuffer(forChannel: channel)
                for frame in 0..<source.frames {
                    let value = raw[frame] / 32768
                    channels[channel][frame] = muted ? 0 : (value.isFinite ? max(-1, min(1, value)) : 0)
                }
            }
            // Processing is serialized only for this binding; sink switching never waits on copying.
            lock.lock()
            defer { lock.unlock() }
            let previousEnd = anchor.map { $0 + Double(outputFrames) / 48_000 }
            if lastRate != sampleRate {
                resampler.reset()
                anchor = previousEnd.map { max($0, Self.now()) }
                outputFrames = 0
                lastRate = sampleRate
            }
            let output: AVAudioPCMBuffer?
            if sampleRate == 48_000 {
                // No rate converter, no collapse of stereo channels for the common WebRTC format.
                output = Self.stereoCopy(input)
            } else {
                output = resampler.convert(input)
            }
            guard let output else { return }
            let now = Self.now()
            if anchor == nil { anchor = now }
            var time = anchor! + Double(outputFrames) / 48_000
            // A capture interruption is a real timeline gap, not delayed audio to catch up later.
            if now - time > 0.25 {
                anchor = max(now, time)
                outputFrames = 0
                time = anchor!
            }
            outputFrames += Int64(output.frameLength)
            recorder.appendAudio(output, when: AVAudioTime(hostTime: AVAudioTime.hostTime(forSeconds: time)))
        }

        private static func now() -> Double { AVAudioTime.seconds(forHostTime: mach_absolute_time()) }
        private static func stereoCopy(_ input: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
            guard let output = AVAudioPCMBuffer(pcmFormat: DirectAudioResampler.outputFormat, frameCapacity: input.frameLength),
                  let source = input.floatChannelData, let destination = output.floatChannelData else { return nil }
            output.frameLength = input.frameLength
            destination[0].update(from: source[0], count: Int(input.frameLength))
            destination[1].update(from: source[input.format.channelCount > 1 ? 1 : 0], count: Int(input.frameLength))
            return output
        }
    }
}

/// A synchronized reference slot. Snapshot once at callback admission, before any asynchronous work.
nonisolated final class BroadcastRecordingSinkSlot: @unchecked Sendable {
    private let lock = NSLock()
    private var recorder: LocalBroadcastRecorder?
    func set(_ recorder: LocalBroadcastRecorder?) { lock.withLock { self.recorder = recorder } }
    func snapshot() -> LocalBroadcastRecorder? { lock.withLock { recorder } }
}
