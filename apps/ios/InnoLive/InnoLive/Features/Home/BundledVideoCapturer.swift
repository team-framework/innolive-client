#if DEBUG
@preconcurrency import AVFoundation
@preconcurrency import LiveKitWebRTC

/// Debug 번들 영상을 파일의 프레임 속도로 반복 전달한다. 프레임 기한은 디코더·delegate 처리 시간만큼 밀리지 않는다.
/// SDK 파일 capturer는 reader가 completed 외 상태로 멈추면 오류 없이 프레임 전달을 멈춘다.
nonisolated final class BundledVideoCapturer: LKRTCFileVideoCapturer, @unchecked Sendable {
    private let frameQueue = DispatchQueue(label: "com.innolive.bundled-video", qos: .userInitiated)
    private var timer: DispatchSourceTimer?
    private var reader: AVAssetReader?
    private var output: AVAssetReaderTrackOutput?
    private var asset: AVURLAsset?
    private var track: AVAssetTrack?
    private var onFailure: (@Sendable (Error) -> Void)?

    func start(url: URL, onFailure: @escaping @Sendable (Error) -> Void) async throws {
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
                self.asset = asset
                self.track = track
                self.onFailure = onFailure
                do {
                    try resetReader()
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

    private func resetReader() throws {
        guard let asset, let track else { throw CocoaError(.fileReadCorruptFile) }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        ])
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else { throw CocoaError(.fileReadCorruptFile) }
        reader.add(output)
        guard reader.startReading() else { throw reader.error ?? CocoaError(.fileReadCorruptFile) }
        self.reader = reader
        self.output = output
    }

    private func deliverNextFrame() {
        do {
            var sample = output?.copyNextSampleBuffer()
            if sample == nil {
                guard reader?.status == .completed else { throw reader?.error ?? CocoaError(.fileReadCorruptFile) }
                try resetReader()
                sample = output?.copyNextSampleBuffer()
            }
            guard let sample, let pixels = CMSampleBufferGetImageBuffer(sample) else {
                throw CocoaError(.fileReadCorruptFile)
            }
            let frame = LKRTCVideoFrame(buffer: LKRTCCVPixelBuffer(pixelBuffer: pixels), rotation: ._0,
                                       timeStampNs: Int64(DispatchTime.now().uptimeNanoseconds))
            delegate?.capturer(self, didCapture: frame)
        } catch {
            let failure = onFailure
            stopOnQueue()
            failure?(error)
        }
    }

    private func stopOnQueue() {
        timer?.cancel(); timer = nil
        reader?.cancelReading(); reader = nil
        output = nil; asset = nil; track = nil; onFailure = nil
    }

    deinit { timer?.cancel(); reader?.cancelReading() }
}
#endif
