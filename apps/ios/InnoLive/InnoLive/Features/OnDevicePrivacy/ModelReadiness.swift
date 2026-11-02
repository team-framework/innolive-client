import Combine
import Foundation
import SwiftUI

nonisolated enum ModelReadiness: Equatable, Sendable {
    case missing
    case compiling(progress: Double)
    case ready
    case failed(String)
}

/// Loads the on-device models once so Core ML compiles and caches them while the app can still go online.
@MainActor
final class ModelReadinessStore: ObservableObject {
    nonisolated deinit {}

    nonisolated struct Loader: Sendable {
        let name: String
        let load: @Sendable () throws -> Void
    }

    /// The face embedding model also loads YuNet, so two loads warm all three bundled models.
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

    /// Whether this app build on this OS finished a warm-up before, a proxy for Core ML's compiled cache.
    var wasWarmedForThisBuild: Bool { defaults.string(forKey: Self.warmedBuildKey) == Self.buildKey }

    private static var buildKey: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(version)|\(ProcessInfo.processInfo.operatingSystemVersionString)"
    }

    /// Safe to call repeatedly; only one warm-up runs, and a ready or missing state is final.
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
