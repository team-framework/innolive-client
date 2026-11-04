import Combine
import Foundation
import SwiftUI

nonisolated enum ModelReadiness: Equatable, Sendable {
    case missing
    case compiling(progress: Double)
    case ready
    case failed(String)
}

/// 앱이 아직 온라인에 연결할 수 있는 동안 온디바이스 모델을 한 번 불러와 Core ML이 컴파일하고 캐시하게 한다.
@MainActor
final class ModelReadinessStore: ObservableObject {
    nonisolated deinit {}

    nonisolated struct Loader: Sendable {
        let name: String
        let load: @Sendable () throws -> Void
    }

    /// 얼굴 임베딩 모델이 YuNet도 함께 불러오므로, 두 번만 불러와도 번들 모델 세 개가 모두 워밍업된다.
    nonisolated static let bundledLoaders: [Loader] = [
        Loader(name: "PrivacyDetector") { _ = try PrivacyModel() },
        Loader(name: "PrivacyFaceRecognizer+PrivacyYuNet") { _ = try PrivacyFaceEmbeddingModel() }
    ]
    private static let warmedBuildKey = "com.framework.innolive.model-readiness.warmed-build"

    @Published private(set) var state: ModelReadiness
    @Published private(set) var lastWarmSeconds: Double?
    private let loaders: [Loader]
    private let defaults: UserDefaults
    private var task: Task<Void, Never>?

    init(available: Bool = AIProcessingMode.localModelsAvailable,
         loaders: [Loader] = ModelReadinessStore.bundledLoaders,
         defaults: UserDefaults = .standard) {
        self.loaders = loaders
        self.defaults = defaults
        state = available ? .compiling(progress: 0) : .missing
    }

    var isReady: Bool { state == .ready }

    /// 이 OS에서 이 앱 빌드가 워밍업을 마친 적이 있는지 나타낸다. Core ML 컴파일 캐시 여부를 대신 판단하는 값이다.
    var wasWarmedForThisBuild: Bool { defaults.string(forKey: Self.warmedBuildKey) == Self.buildKey }

    private static var buildKey: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(version)|\(ProcessInfo.processInfo.operatingSystemVersionString)"
    }

    /// 여러 번 호출해도 안전하다. 워밍업은 하나만 실행되고, ready나 missing 상태가 되면 더 바뀌지 않는다.
    func warm() async {
        if let task { return await task.value }
        switch state {
        case .ready, .missing: return
        case .compiling, .failed: break
        }
        let task = Task { await run() }
        self.task = task
        await task.value
        self.task = nil
    }

    private func run() async {
        let started = ContinuousClock.now
        state = .compiling(progress: 0)
        for (index, loader) in loaders.enumerated() {
            do {
                try await Task.detached(priority: .utility) { try loader.load() }.value
            } catch {
                state = .failed(error.localizedDescription)
                return
            }
            state = .compiling(progress: Double(index + 1) / Double(max(loaders.count, 1)))
        }
        let elapsed = ContinuousClock.now - started
        lastWarmSeconds = Double(elapsed.components.seconds) + Double(elapsed.components.attoseconds) / 1e18
        defaults.set(Self.buildKey, forKey: Self.warmedBuildKey)
        state = .ready
    }
}
