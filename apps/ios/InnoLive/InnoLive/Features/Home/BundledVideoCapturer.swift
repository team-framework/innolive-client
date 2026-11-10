#if DEBUG
@preconcurrency import AVFoundation
@preconcurrency import LiveKitWebRTC

/// Debug Simulator 번들 영상을 시작할 때 한 번만 디코딩하고 파일 프레임 속도로 반복 전달한다.
/// 재생 중 프레임 전달은 AVAssetReader 디코딩이나 SDK 파일 capturer의 background QoS 큐를 기다리지 않는다.
nonisolated final class BundledVideoCapturer: LKRTCFileVideoCapturer, @unchecked Sendable {
    private let frameQueue = DispatchQueue(label: "com.innolive.bundled-video", qos: .userInitiated)
    private var timer: DispatchSourceTimer?
    private var frames: [CVPixelBuffer] = []
    private var nextFrameIndex = 0

    func start(url: URL) async throws {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let frameRate = try await track.load(.nominalFrameRate)
        guard frameRate > 0 else { throw CocoaError(.fileReadCorruptFile) }
        let frameInterval = DispatchTimeInterval.nanoseconds(Int((1_000_000_000 / Double(frameRate)).rounded()))
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            frameQueue.async { [self] in
                stopOnQueue()
                do {
                    frames = try Self.decodeFrames(asset: asset, track: track)
                    let timer = DispatchSource.makeTimerSource(flags: .strict, queue: frameQueue)
                    timer.schedule(deadline: .now(), repeating: frameInterval, leeway: .microseconds(100))
                    timer.setEventHandler { [weak self] in self?.deliverNextFrame() }
                    self.timer = timer
                    timer.resume()
                    continuation.resume()
                } catch {
                    stopOnQueue()
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    override func stopCapture() {
        frameQueue.sync { stopOnQueue() }
    }

    private static func decodeFrames(asset: AVAsset, track: AVAssetTrack) throws -> [CVPixelBuffer] {
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        ])
        guard reader.canAdd(output) else { throw CocoaError(.fileReadCorruptFile) }
        reader.add(output)
        guard reader.startReading() else { throw reader.error ?? CocoaError(.fileReadCorruptFile) }
        var frames: [CVPixelBuffer] = []
        while let sample = output.copyNextSampleBuffer() {
            guard let pixels = CMSampleBufferGetImageBuffer(sample) else { throw CocoaError(.fileReadCorruptFile) }
            frames.append(pixels)
        }
        guard reader.status == .completed, !frames.isEmpty else {
            throw reader.error ?? CocoaError(.fileReadCorruptFile)
        }
        return frames
    }

    private func deliverNextFrame() {
        let pixels = frames[nextFrameIndex]
        nextFrameIndex = (nextFrameIndex + 1) % frames.count
        let frame = LKRTCVideoFrame(buffer: LKRTCCVPixelBuffer(pixelBuffer: pixels), rotation: ._0,
                                   timeStampNs: Int64(DispatchTime.now().uptimeNanoseconds))
        delegate?.capturer(self, didCapture: frame)
    }

    private func stopOnQueue() {
        timer?.cancel(); timer = nil
        frames = []; nextFrameIndex = 0
    }

    deinit { timer?.cancel() }
}
#endif
