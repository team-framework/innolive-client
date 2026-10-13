import AVFoundation
import Combine
import HaishinKit
import VideoToolbox

/// 온디바이스 세션에서 앱이 플랫폼으로 직접 보내는 송출을 관리한다(server #438).
@MainActor
protocol DirectBroadcastPublishing: AnyObject {
    var states: [String: DirectPublishState] { get }
    var mediaSink: (any DirectMediaSink)? { get }
    var onStateChange: (() -> Void)? { get set }
    /// 재연결까지 실패해 송출이 끊긴 대상이다. 서버의 플랫폼 방송도 정리해야 한다.
    var onPermanentFailure: ((String) -> Void)? { get set }
    /// 마이크를 열지 못해 무음으로 송출 중이다.
    var isMicrophoneUnavailable: Bool { get }
    func start(provider: String, endpoint: DirectIngestEndpoint, quality: CameraQualityPreset) async throws
    func stop(provider: String) async
    func stopAll() async
    func setPaused(_ paused: Bool, provider: String)
    /// 하트비트에 싣는 대상별 연결 상태와 누적 지표다.
    func heartbeatStatus() async -> DirectPublishStatus
}

struct DirectPublishTargetStatus: Equatable {
    let provider: String
    let connected: Bool
    /// 송신 대기열에 넘긴 누적 바이트. 연결이 없으면 nil이다.
    let sentBytes: Int?
    let reconnects: Int
}

struct DirectPublishStatus: Equatable {
    var targets: [DirectPublishTargetStatus] = []
    var metrics = DirectPublishMetrics.Snapshot()
}

enum DirectPublishError: LocalizedError, Equatable {
    case connectionFailed(provider: String)
    /// 직접 송출 세션인데 서버 응답에 쓸 수 있는 ingest 주소가 없다.
    case missingIngest(provider: String)

    var errorDescription: String? {
        let title: (String) -> String = { BroadcastSettingsProvider(rawValue: $0)?.title ?? $0 }
        switch self {
        case let .connectionFailed(provider):
            return String(format: String(localized: "%@ 송출 서버에 연결하지 못했습니다. 네트워크를 확인한 뒤 다시 시도해 주세요.", table: "DirectPublish"), title(provider))
        case let .missingIngest(provider):
            return String(format: String(localized: "서버에서 %@ 송출 주소를 받지 못해 방송을 시작하지 않았습니다.", table: "DirectPublish"), title(provider))
        }
    }
}

@MainActor
final class DirectBroadcastPublisher: ObservableObject, DirectBroadcastPublishing {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    static let maximumReconnectAttempts = 3

    @Published private(set) var states: [String: DirectPublishState] = [:]
    @Published private(set) var isMicrophoneUnavailable = false
    var onStateChange: (() -> Void)?
    var onPermanentFailure: ((String) -> Void)?

    private let fanout = DirectMediaFanout()
    private let microphone: any DirectAudioCapturing
    /// 마이크 녹음을 시작했고 아직 멈추지 않았다. 시작에 실패해도 녹음기는 다시 열기를 시도하므로 멈춰야 한다.
    private var isMicrophoneActive = false
    private var outputs: [String: RTMPDirectOutput] = [:]
    private var configurations: [String: (endpoint: DirectIngestEndpoint, quality: CameraQualityPreset)] = [:]
    private var generations: [String: UInt] = [:]
    private var reconnectTasks: [String: Task<Void, Never>] = [:]
    private var reconnectCounts: [String: Int] = [:]

    var mediaSink: (any DirectMediaSink)? { fanout }

    init(microphone: any DirectAudioCapturing = DirectMicrophoneCapture()) {
        self.microphone = microphone
    }

    func start(provider: String, endpoint: DirectIngestEndpoint, quality: CameraQualityPreset) async throws {
        await stop(provider: provider)
        if states.isEmpty && outputs.isEmpty {
            // 하트비트 누적값은 세션의 첫 직접 송출부터 센다.
            fanout.metrics.reset()
            reconnectCounts = [:]
        }
        startMicrophoneIfNeeded()
        let generation = nextGeneration(provider)
        configurations[provider] = (endpoint, quality)
        setState(.connecting, provider: provider)
        do {
            try await connect(provider: provider, endpoint: endpoint, quality: quality, generation: generation)
        } catch {
            guard generations[provider] == generation else { throw CancellationError() }
            configurations[provider] = nil
            stopMicrophoneIfIdle()
            setState(nil, provider: provider)
            throw DirectPublishError.connectionFailed(provider: provider)
        }
    }

    func stop(provider: String) async {
        _ = nextGeneration(provider)
        reconnectTasks.removeValue(forKey: provider)?.cancel()
        configurations[provider] = nil
        fanout.detach(provider: provider)
        let output = outputs.removeValue(forKey: provider)
        stopMicrophoneIfIdle()
        setState(nil, provider: provider)
        await output?.close()
    }

    func stopAll() async {
        let providers = Set(states.keys).union(outputs.keys).union(configurations.keys)
        for provider in providers { await stop(provider: provider) }
    }

    func setPaused(_ paused: Bool, provider: String) {
        guard let state = states[provider] else { return }
        fanout.setPaused(paused, provider: provider)
        if state == .publishing || state == .paused {
            setState(paused ? .paused : .publishing, provider: provider)
        }
    }

    func heartbeatStatus() async -> DirectPublishStatus {
        refreshMicrophoneAvailability()
        var targets: [DirectPublishTargetStatus] = []
        for provider in states.keys.sorted() {
            let connected = states[provider] == .publishing || states[provider] == .paused
            let bytes: Int?
            if connected, let output = outputs[provider] { bytes = await output.sentByteCount() } else { bytes = nil }
            targets.append(.init(provider: provider, connected: connected, sentBytes: bytes,
                                 reconnects: reconnectCounts[provider] ?? 0))
        }
        return DirectPublishStatus(targets: targets, metrics: fanout.metrics.drain())
    }

    static func videoSettings(quality: CameraQualityPreset, dimensions: CMVideoDimensions?) -> VideoCodecSettings {
        let size = dimensions.map { CGSize(width: Int($0.width), height: Int($0.height)) }
            ?? CGSize(width: Int(quality.width), height: Int(quality.height))
        // YouTube·치지직 권장 범위 안에서 셀룰러 업로드를 고려한 고정 비트레이트다.
        // 카메라 화질은 세션 해상도에 맞춰 바뀌므로 설정값이 아니라 실제 프레임의 짧은 변으로 고른다.
        let bitRate = min(size.width, size.height) >= 1080 ? 6_000_000 : 3_500_000
        return VideoCodecSettings(
            videoSize: size,
            bitRate: bitRate,
            profileLevel: kVTProfileLevel_H264_High_AutoLevel as String,
            scalingMode: .trim,
            bitRateMode: .average,
            maxKeyFrameIntervalDuration: 2,
            allowFrameReordering: false,
            expectedFrameRate: Double(quality.framesPerSecond)
        )
    }

    /// 마이크·무음 입력(48kHz 스테레오)과 같은 샘플레이트로 고정한다. 입력이 바뀌어도 AAC 설정은 그대로다.
    static let audioSettings = AudioCodecSettings(
        bitRate: 128_000, sampleRate: DirectMediaFanout.audioFormat.sampleRate
    )

    /// 직접 송출 대상이 하나라도 있는 동안 마이크를 연다. 열지 못하면 송출은 무음으로 이어간다.
    private func startMicrophoneIfNeeded() {
        guard !isMicrophoneActive else { return }
        isMicrophoneActive = true
        let fanout = fanout
        isMicrophoneUnavailable = !microphone.start { buffer, when in
            fanout.appendAudio(buffer, when: when)
        }
    }

    private func stopMicrophoneIfIdle() {
        guard configurations.isEmpty, outputs.isEmpty, isMicrophoneActive else { return }
        microphone.stop()
        isMicrophoneActive = false
        isMicrophoneUnavailable = false
    }

    /// 하트비트 주기마다 실제로 마이크 음성이 들어오는지로 경고를 고친다. 녹음이 나중에 살아나거나 끊긴 경우다.
    private func refreshMicrophoneAvailability() {
        guard isMicrophoneActive else { return }
        let unavailable = !fanout.isReceivingMicrophone
        guard unavailable != isMicrophoneUnavailable else { return }
        isMicrophoneUnavailable = unavailable
        onStateChange?()
    }

    private func connect(provider: String, endpoint: DirectIngestEndpoint,
                         quality: CameraQualityPreset, generation: UInt) async throws {
        let output = RTMPDirectOutput(metrics: fanout.metrics) { [weak self] in
            Task { @MainActor [weak self] in self?.handleDisconnect(provider: provider, generation: generation) }
        }
        do {
            try await output.open(
                endpoint: endpoint,
                video: Self.videoSettings(quality: quality, dimensions: fanout.latestVideoDimensions),
                audio: Self.audioSettings
            )
        } catch {
            await output.close()
            throw error
        }
        guard generations[provider] == generation else {
            await output.close()
            throw CancellationError()
        }
        outputs[provider] = output
        fanout.attach(output, provider: provider)
        setState(fanout.isPaused(provider: provider) ? .paused : .publishing, provider: provider)
    }

    private func handleDisconnect(provider: String, generation: UInt) {
        guard generations[provider] == generation, let configuration = configurations[provider] else { return }
        let paused = fanout.isPaused(provider: provider)
        fanout.detach(provider: provider)
        if paused { fanout.setPaused(true, provider: provider) }
        let previous = outputs.removeValue(forKey: provider)
        reconnectCounts[provider, default: 0] += 1
        setState(.reconnecting, provider: provider)
        let reconnectGeneration = nextGeneration(provider)
        reconnectTasks[provider] = Task { @MainActor [weak self] in
            await previous?.close()
            for _ in 1...Self.maximumReconnectAttempts {
                do { try await Task.sleep(for: .seconds(2)) } catch { return }
                guard let self, self.generations[provider] == reconnectGeneration else { return }
                do {
                    try await self.connect(provider: provider, endpoint: configuration.endpoint,
                                           quality: configuration.quality, generation: reconnectGeneration)
                    self.reconnectTasks[provider] = nil
                    return
                } catch {
                    guard self.generations[provider] == reconnectGeneration else { return }
                }
            }
            guard let self, self.generations[provider] == reconnectGeneration else { return }
            self.reconnectTasks[provider] = nil
            self.fanout.detach(provider: provider)
            self.configurations[provider] = nil
            self.stopMicrophoneIfIdle()
            self.setState(.failed, provider: provider)
            self.onPermanentFailure?(provider)
        }
    }

    private func nextGeneration(_ provider: String) -> UInt {
        let next = (generations[provider] ?? 0) &+ 1
        generations[provider] = next
        return next
    }

    private func setState(_ state: DirectPublishState?, provider: String) {
        guard states[provider] != state else { return }
        states[provider] = state
        onStateChange?()
    }
}
