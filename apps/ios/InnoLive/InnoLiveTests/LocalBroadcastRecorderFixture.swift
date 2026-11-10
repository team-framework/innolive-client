import AVFoundation
import XCTest
@testable import InnoLive

extension LocalBroadcastRecorder {
    // 녹화 테스트가 실제 MP4를 만들 때 넣는 합성 입력이다.
    // 10ms 간격으로 30번 반복하며 짝수 회차마다 64x48 BGRA 영상 1장, 매 회차 48kHz 스테레오 440Hz 오디오 480프레임을 넣는다.
    // 호출하는 테스트가 모두 @MainActor이므로 기본 격리 추론에 기대지 않고 MainActor로 명시한다.
    @MainActor
    func feedSyntheticMedia() async throws {
        let base = CMClockGetTime(CMClockGetHostTimeClock())
        let format = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2, interleaved: false))
        for index in 0..<30 {
            let time = CMTimeAdd(base, CMTime(value: Int64(index * 480), timescale: 48_000))
            if index % 2 == 0 {
                var pixel: CVPixelBuffer?
                CVPixelBufferCreate(kCFAllocatorDefault, 64, 48, kCVPixelFormatType_32BGRA,
                    [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &pixel)
                let buffer = try XCTUnwrap(pixel)
                CVPixelBufferLockBaseAddress(buffer, [])
                memset(CVPixelBufferGetBaseAddress(buffer), 100, CVPixelBufferGetDataSize(buffer))
                CVPixelBufferUnlockBaseAddress(buffer, [])
                appendVideo(pixelBuffer: buffer, rotation: 0,
                    timestampNs: CMTimeConvertScale(time, timescale: 1_000_000_000, method: .default).value)
            }
            let audio = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 480))
            audio.frameLength = 480
            for sample in 0..<480 {
                let value = Float(sin(Double(index * 480 + sample) * 2 * .pi * 440 / 48_000) * 0.25)
                audio.floatChannelData![0][sample] = value
                audio.floatChannelData![1][sample] = value
            }
            appendAudio(audio, when: AVAudioTime(hostTime: AVAudioTime.hostTime(forSeconds: CMTimeGetSeconds(time))))
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}
