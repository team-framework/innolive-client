import Combine
import Foundation

@MainActor
protocol PlanAPIClient {
    func userPlan(accessToken: String) async throws -> UserPlan
    func userUsage(accessToken: String) async throws -> UserUsage
}

@MainActor
final class PlanStore: ObservableObject {
    nonisolated deinit {}
    @Published private(set) var snapshot: PlanSnapshot?
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var lastUpdatedAt: Date?
    private var generation: UInt = 0
    private var serverSnapshot: PlanSnapshot?
    private var pendingLocalTakes: [LocalTakeUsage] = []
    private let cacheURL: URL?

    private struct CachedPlan: Codable {
        let accountID: String
        let plan: UserPlan
        let usage: UserUsage
        let fetchedAt: Date
    }

    init(cacheURL: URL? = PlanStore.defaultCacheURL) {
        self.cacheURL = cacheURL
    }

    nonisolated static var defaultCacheURL: URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent("PlanSnapshot.json")
    }

    /// Shows this account's last server answer until a refresh replaces it, so usage reads the same offline.
    func restore(accountID: String?) {
        guard serverSnapshot == nil, let accountID, let cacheURL,
              let data = try? Data(contentsOf: cacheURL),
              let cached = try? JSONDecoder().decode(CachedPlan.self, from: data),
              cached.accountID == accountID else { return }
        serverSnapshot = PlanSnapshot(plan: cached.plan, usage: cached.usage)
        lastUpdatedAt = cached.fetchedAt
        publish()
    }

    func eraseCache() {
        guard let cacheURL else { return }
        try? FileManager.default.removeItem(at: cacheURL)
    }

    private func saveCache(_ cached: CachedPlan) {
        guard let cacheURL, let data = try? JSONEncoder().encode(cached) else { return }
        try? FileManager.default.createDirectory(at: cacheURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: cacheURL, options: .atomic)
    }

    func setPendingLocalTakes(_ takes: [LocalTakeUsage]) {
        pendingLocalTakes = takes
        publish()
    }

    private func publish() {
        snapshot = serverSnapshot?.addingPendingLocalTakes(pendingLocalTakes)
    }

    func reset() {
        generation &+= 1
        serverSnapshot = nil
        snapshot = nil
        lastUpdatedAt = nil
        errorMessage = nil
        isLoading = false
    }

    func refresh(api: any PlanAPIClient, accessToken: String?) async {
        guard let accessToken, !accessToken.isEmpty, !isLoading else { return }
        let generation = self.generation
        isLoading = true
        defer { if self.generation == generation { isLoading = false } }
        do {
            let plan = try await api.userPlan(accessToken: accessToken)
            let usage = try await api.userUsage(accessToken: accessToken)
            guard self.generation == generation, !Task.isCancelled else { return }
            guard plan.plan == usage.plan else { throw YouTubeAPIError.response }
            serverSnapshot = PlanSnapshot(plan: plan, usage: usage)
            publish()
            let fetchedAt = Date.now
            lastUpdatedAt = fetchedAt
            errorMessage = nil
            if let accountID = OfflineGrant.accountID(fromAccessToken: accessToken) {
                saveCache(CachedPlan(accountID: accountID, plan: plan, usage: usage, fetchedAt: fetchedAt))
            }
        } catch {
            guard self.generation == generation, !Task.isCancelled else { return }
            if serverSnapshot != nil, error as? YouTubeAPIError == .transport { return }
            errorMessage = String(localized: "요금제와 사용량을 조회하지 못했습니다. 다시 시도해 주세요.")
        }
    }
}
