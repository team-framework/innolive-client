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
    func start(provider: String, endpoint: DirectIngestEndpoint, quality: CameraQualityPreset) async throws
    func stop(provider: String) async
    func stopAll() async
    func setPaused(_ paused: Bool, provider: String)
}

enum DirectPublishError: LocalizedError, Equatable {
    case connectionFailed(provider: String)

    var errorDescription: String? {
        switch self {
        case let .connectionFailed(provider):
            let title = BroadcastSettingsProvider(rawValue: provider)?.title ?? provider
            return String(format: String(localized: "%@ 송출 서버에 연결하지 못했습니다. 네트워크를 확인한 뒤 다시 시도해 주세요.", table: "DirectPublish"), title)
        }
    }
}

@MainActor
final class DirectBroadcastPublisher: ObservableObject, DirectBroadcastPublishing {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    static let maximumReconnectAttempts = 3

    @Published private(set) var states: [String: DirectPublishState] = [:]
    var onStateChange: (() -> Void)?
    var onPermanentFailure: ((String) -> Void)?

    private let fanout = DirectMediaFanout()
    private var outputs: [String: RTMPDirectOutput] = [:]
    private var configurations: [String: (endpoint: DirectIngestEndpoint, quality: CameraQualityPreset)] = [:]
    private var generations: [String: UInt] = [:]
    private var reconnectTasks: [String: Task<Void, Never>] = [:]

    var mediaSink: (any DirectMediaSink)? { fanout }

    func start(provider: String, endpoint: DirectIngestEndpoint, quality: CameraQualityPreset) async throws {
        await stop(provider: provider)
        let generation = nextGeneration(provider)
        configurations[provider] = (endpoint, quality)
        setState(.connecting, provider: provider)
        do {
            try await connect(provider: provider, endpoint: endpoint, quality: quality, generation: generation)
        } catch {
            guard generations[provider] == generation else { throw CancellationError() }
            configurations[provider] = nil
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

    static func videoSettings(quality: CameraQualityPreset, dimensions: CMVideoDimensions?) -> VideoCodecSettings {
        let size = dimensions.map { CGSize(width: Int($0.width), height: Int($0.height)) }
            ?? CGSize(width: Int(quality.width), height: Int(quality.height))
        // YouTube·치지직 권장 범위 안에서 셀룰러 업로드를 고려한 고정 비트레이트다.
        let bitRate = quality.height >= 1080 ? 6_000_000 : 3_500_000
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

    static let audioSettings = AudioCodecSettings(bitRate: 128_000)

    private func connect(provider: String, endpoint: DirectIngestEndpoint,
                         quality: CameraQualityPreset, generation: UInt) async throws {
        let output = RTMPDirectOutput { [weak self] in
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
