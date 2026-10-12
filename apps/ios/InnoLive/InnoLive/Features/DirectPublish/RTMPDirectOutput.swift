import AVFoundation
import HaishinKit
import RTMPHaishinKit
import VideoToolbox

/// 플랫폼 RTMP(S) 연결 하나다. 미디어는 순서를 지키도록 단일 소비 작업에서 넘긴다.
nonisolated final class RTMPDirectOutput: DirectMediaOutput, @unchecked Sendable {
    typealias AudioSample = (buffer: AVAudioPCMBuffer, when: AVAudioTime)

    private static let closedCodes: Set<String> = [
        RTMPConnection.Code.connectClosed.rawValue,
        RTMPConnection.Code.connectFailed.rawValue,
        RTMPConnection.Code.connectIdleTimeOut.rawValue,
        RTMPConnection.Code.connectAppshutdown.rawValue,
        RTMPConnection.Code.connectRejected.rawValue
    ]

    private let connection: RTMPConnection
    private let stream: RTMPStream
    private let videoInput: AsyncStream<CMSampleBuffer>
    private let videoContinuation: AsyncStream<CMSampleBuffer>.Continuation
    private let audioInput: AsyncStream<AudioSample>
    private let audioContinuation: AsyncStream<AudioSample>.Continuation
    private let lock = NSLock()
    private var isOpen = false
    private var isClosing = false
    private var lastVideoTime = CMTime.negativeInfinity
    private var tasks: [Task<Void, Never>] = []
    private let onDisconnect: @Sendable () -> Void
    private let metrics: DirectPublishMetrics?

    init(metrics: DirectPublishMetrics? = nil, onDisconnect: @escaping @Sendable () -> Void) {
        self.metrics = metrics
        // 치지직 등 확장 RTMP를 모르는 서버와도 연결되도록 E-RTMP 연결 인자를 보내지 않는다.
        // H.264·AAC만 쓰므로 확장 코덱 협상은 필요 없다.
        connection = RTMPConnection(fourCcList: nil, videoFourCcInfoMap: nil, audioFourCcInfoMap: nil, capsEx: 0)
        stream = RTMPStream(connection: connection)
        (videoInput, videoContinuation) = AsyncStream.makeStream(of: CMSampleBuffer.self, bufferingPolicy: .bufferingNewest(4))
        (audioInput, audioContinuation) = AsyncStream.makeStream(of: AudioSample.self, bufferingPolicy: .bufferingNewest(64))
        self.onDisconnect = onDisconnect
    }

    func open(endpoint: DirectIngestEndpoint, video: VideoCodecSettings, audio: AudioCodecSettings) async throws {
        try await stream.setVideoSettings(video)
        try await stream.setAudioSettings(audio)
        let statuses = await connection.status
        _ = try await connection.connect(endpoint.connectURL)
        _ = try await stream.publish(endpoint.streamName)

        let stream = stream
        let videoInput = videoInput
        let audioInput = audioInput
        var currentVideo = video
        let videoTask = Task {
            for await sample in videoInput {
                // 세로·가로 고정 방향에 맞춰 인코더 크기를 실제 프레임 크기로 맞춘다.
                if let format = sample.formatDescription {
                    let dimensions = CMVideoFormatDescriptionGetDimensions(format)
                    let size = CGSize(width: Int(dimensions.width), height: Int(dimensions.height))
                    if size != currentVideo.videoSize {
                        currentVideo.videoSize = size
                        try? await stream.setVideoSettings(currentVideo)
                    }
                }
                await stream.append(sample)
            }
        }
        let audioTask = Task {
            for await sample in audioInput {
                await stream.append(sample.buffer, when: sample.when)
            }
        }
        let statusTask = Task { [weak self] in
            for await status in statuses where Self.closedCodes.contains(status.code) {
                self?.handleClosed()
                return
            }
        }
        lock.lock()
        isOpen = !isClosing
        tasks = [videoTask, audioTask, statusTask]
        let cancelled = isClosing
        lock.unlock()
        if cancelled { tasks.forEach { $0.cancel() } }
    }

    func appendVideo(_ sampleBuffer: CMSampleBuffer) {
        let time = sampleBuffer.presentationTimeStamp
        lock.lock()
        // RTMP 타임스탬프는 증가해야 한다. 늦게 도착한 프레임은 버린다.
        guard isOpen else {
            lock.unlock()
            return
        }
        guard time > lastVideoTime else {
            lock.unlock()
            metrics?.recordDroppedFrame()
            return
        }
        lastVideoTime = time
        lock.unlock()
        // 인코더가 밀리면 가장 오래된 프레임을 버린다. 버린 수는 하트비트 드롭에 더한다.
        if case .dropped = videoContinuation.yield(sampleBuffer) { metrics?.recordDroppedFrame() }
    }

    /// 송신 대기열에 넘긴 누적 바이트다. 하트비트 비트레이트 계산에 쓴다.
    func sentByteCount() async -> Int {
        await stream.info.byteCount
    }

    func appendAudio(_ buffer: AVAudioPCMBuffer, when: AVAudioTime) {
        lock.lock()
        let isOpen = isOpen
        lock.unlock()
        guard isOpen else { return }
        audioContinuation.yield((buffer, when))
    }

    func close() async {
        lock.lock()
        isClosing = true
        isOpen = false
        let tasks = tasks
        self.tasks = []
        lock.unlock()
        videoContinuation.finish()
        audioContinuation.finish()
        tasks.forEach { $0.cancel() }
        _ = try? await stream.close()
        try? await connection.close()
    }

    private func handleClosed() {
        lock.lock()
        let unexpected = !isClosing
        isOpen = false
        lock.unlock()
        if unexpected { onDisconnect() }
    }
}
