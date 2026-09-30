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

    func reset() {
        generation &+= 1
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
            snapshot = PlanSnapshot(plan: plan, usage: usage)
            lastUpdatedAt = .now
            errorMessage = nil
        } catch {
            guard self.generation == generation, !Task.isCancelled else { return }
            errorMessage = String(localized: "요금제와 사용량을 조회하지 못했습니다. 다시 시도해 주세요.")
        }
    }
}
