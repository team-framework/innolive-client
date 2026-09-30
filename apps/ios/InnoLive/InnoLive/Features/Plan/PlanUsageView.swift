import SwiftUI

struct PlanUsageView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    var body: some View {
        ScrollView {
            PlanUsageSection(authentication: authentication, youtube: youtube)
                .padding(24)
        }
        .navigationTitle(String(localized: "요금제 및 사용량"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct PlanUsageSection: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @ObservedObject private var store: PlanStore

    init(authentication: AuthSession, youtube: YouTubeIntegration) {
        self.authentication = authentication
        self.youtube = youtube
        self.store = youtube.planStore
    }

    var body: some View {
        PlanUsageContent(snapshot: store.snapshot, isLoading: store.isLoading,
                         errorMessage: store.errorMessage, lastUpdatedAt: store.lastUpdatedAt,
                         currentMode: youtube.currentPlanMode) {
            Task { await youtube.refreshPlan(accessToken: authentication.currentAccessToken()) }
        }
        .task { await youtube.refreshPlan(accessToken: authentication.currentAccessToken()) }
    }
}

struct PlanUsageContent: View {
    let snapshot: PlanSnapshot?
    let isLoading: Bool
    let errorMessage: String?
    let lastUpdatedAt: Date?
    let currentMode: BroadcastPlanMode
    let onRefresh: () -> Void
    @State private var selectedMode: BroadcastPlanMode?
    @State private var showsPlanGuide = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(String(localized: "요금제 및 사용량")).font(.headline)
                Spacer()
                if isLoading { ProgressView().controlSize(.small) }
                Button(String(localized: "새로고침"), action: onRefresh)
                .disabled(isLoading)
            }
            if let snapshot {
                Text(snapshot.plan.plan.capitalized).font(.title3.bold())
                LabeledContent(String(localized: "월 방송 한도"), value: PlanTimeText.duration(
                    snapshot.plan.monthlyBroadcastSeconds == 0 ? nil : snapshot.plan.monthlyBroadcastSeconds))
                LabeledContent(String(localized: "1회 방송 한도"), value: PlanTimeText.duration(
                    snapshot.plan.maxPerBroadcastSeconds == 0 ? nil : snapshot.plan.maxPerBroadcastSeconds))
                LabeledContent(String(localized: "이번 달 차감 시간"), value: usedTime(snapshot.usage.usedSeconds))
                LabeledContent(String(localized: "이번 달 남은 시간"), value: PlanTimeText.duration(snapshot.usage.remainingSeconds))
                Text(String(localized: "현재 방송 방식: \(currentMode.title)"))
                    .font(.subheadline.weight(.semibold))
                ForEach(BroadcastPlanMode.allCases) { mode in
                    modeRow(mode, snapshot: snapshot)
                }
                if let selectedMode, let value = snapshot.availability(for: selectedMode), snapshot.isAllowed(selectedMode) {
                    Text(String(localized: "\(selectedMode.title) · 남은 시간 \(PlanTimeText.duration(value.seconds)) · \(value.multiplier)배 차감"))
                        .font(.footnote)
                }
                Text(String(localized: "방식별 남은 시간은 월 잔여 기준입니다. 1회 방송 한도는 별도로 적용됩니다."))
                    .font(.caption).foregroundStyle(.secondary)
            } else if !isLoading && errorMessage == nil {
                Text(String(localized: "요금제 조회 대기"))
                    .foregroundStyle(.secondary)
            }
            if let errorMessage {
                Label(errorMessage, systemImage: "exclamationmark.triangle")
                    .font(.footnote).foregroundStyle(.orange)
                if snapshot != nil {
                    Text(String(localized: "마지막 조회값을 표시하고 있습니다."))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Button(String(localized: "다시 시도"), action: onRefresh).disabled(isLoading)
            }
            if let date = lastUpdatedAt {
                Text(String(localized: "마지막 조회: \(date.formatted(date: .omitted, time: .standard))"))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .innoLiveGlassBackground(cornerRadius: 16)
        .sheet(isPresented: $showsPlanGuide) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 16) {
                    Text(String(localized: "현재 요금제에서 허용하지 않는 방송 방식입니다."))
                    Text(String(localized: "허용 방식과 실제 차감 배수는 서버에서 조회한 요금제를 따릅니다."))
                    ForEach(BroadcastPlanMode.allCases) { mode in
                        Text(String(localized: "\(mode.title): 기본 \(mode.referenceMultiplier)배 차감"))
                    }
                    Spacer()
                }.padding(24)
                .navigationTitle(String(localized: "플랜 안내"))
                .toolbar { ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "닫기")) { showsPlanGuide = false }
                } }
            }
        }
    }

    private func usedTime(_ seconds: Int) -> String {
        seconds == 0 ? "00:00:00" : PlanTimeText.duration(seconds)
    }

    private func modeRow(_ mode: BroadcastPlanMode, snapshot: PlanSnapshot) -> some View {
        Button {
            if snapshot.isAllowed(mode) { selectedMode = mode }
            else { showsPlanGuide = true }
        } label: {
            HStack {
                Image(systemName: snapshot.isAllowed(mode) ? "video" : "lock.fill")
                Text(mode.title)
                Spacer()
                if let availability = snapshot.availability(for: mode) {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(snapshot.isAllowed(mode) ? PlanTimeText.duration(availability.seconds) : String(localized: "잠김"))
                        Text(String(localized: "\(availability.multiplier)배 차감")).font(.caption)
                    }
                } else {
                    Text(String(localized: "조회 대기"))
                }
            }.padding(.vertical, 6)
        }.buttonStyle(.plain)
    }
}
