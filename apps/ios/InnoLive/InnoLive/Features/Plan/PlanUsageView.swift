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
                         currentMode: youtube.currentPlanMode, currentProcessing: youtube.planProcessingMode) {
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
    var currentProcessing: AIProcessingMode = .server
    let onRefresh: () -> Void
    @State private var selectedProcessing: AIProcessingMode?
    @State private var selectedMode: BroadcastPlanMode?
    @State private var showsPlanGuide = false

    private var processing: AIProcessingMode { selectedProcessing ?? currentProcessing }

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
                Picker(String(localized: "사용량 구분"), selection: Binding(
                    get: { processing }, set: { selectedProcessing = $0; selectedMode = nil }
                )) {
                    Text(String(localized: "서버")).tag(AIProcessingMode.server)
                    Text(String(localized: "온디바이스")).tag(AIProcessingMode.onDevice)
                }.pickerStyle(.segmented)
                if processing == .server && (snapshot.plan.monthlyBroadcastSeconds == 0 || (snapshot.usage.hasLimitSeconds && snapshot.usage.limitSeconds == nil)) {
                    Text(String(localized: "이 요금제는 서버 송출을 지원하지 않습니다."))
                        .font(.subheadline).foregroundStyle(.secondary)
                } else {
                    timeRow(String(localized: "월 방송 한도"), value: processing == .server ? knownTime(snapshot.plan.monthlyBroadcastSeconds)
                        : nullableTime(snapshot.usage.deviceLimitSeconds, present: snapshot.usage.hasDeviceLimitSeconds))
                    timeRow(String(localized: "이번 달 차감 시간"), value: usedTime(processing == .server
                        ? snapshot.usage.usedSeconds : snapshot.usage.deviceUsedSeconds))
                    timeRow(String(localized: "이번 달 남은 시간"), value: processing == .server
                        ? PlanTimeText.duration(snapshot.usage.remainingSeconds) : nullableTime(snapshot.usage.deviceRemainingSeconds, present: snapshot.usage.hasDeviceRemainingSeconds))
                }
                timeRow(String(localized: "1회 방송 한도"), value: PlanTimeText.duration(
                    snapshot.perBroadcastLimit))
                Text(String(localized: "현재 방송 방식: \(currentMode.title)"))
                    .font(.subheadline.weight(.semibold))
                Text(String(localized: "이번 방송 최대", table: "PlanUsage")).font(.subheadline.bold())
                ForEach(BroadcastPlanMode.allCases) { mode in
                    modeRow(mode, snapshot: snapshot)
                }
                if let selectedMode, let value = snapshot.maximumBroadcastAvailability(for: selectedMode, processing: processing), snapshot.isAllowed(selectedMode, processing: processing) {
                    Text(String(localized: "\(selectedMode.title) · 남은 시간 \(PlanTimeText.duration(value.seconds)) · \(value.multiplier)배 차감"))
                        .font(.footnote)
                }
                Text(String(localized: "이번 방송 최대 시간은 월 잔여 시간과 1회 한도 중 작은 값입니다.", table: "PlanUsage"))
                    .font(.caption).foregroundStyle(.secondary)
                if !snapshot.usage.broadcasts.isEmpty {
                    Divider()
                    Text(String(localized: "방송별 사용 내역", table: "PlanUsage")).font(.headline)
                    if let month = snapshot.usage.month { Text(month).font(.caption).foregroundStyle(.secondary) }
                    ForEach(snapshot.usage.broadcasts) { broadcast in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(broadcast.processingTitle).font(.caption.bold()).padding(5).background(.quaternary, in: Capsule())
                                Text(broadcast.resolution.uppercased() + " · " + broadcast.providers.joined(separator: "+")).font(.caption)
                            }
                            if let date = broadcast.startDate {
                                Text(date.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                            }
                            timeRow(String(localized: "실제 방송 시간", table: "PlanUsage"), value: usedTime(broadcast.broadcastSeconds))
                            timeRow(String(localized: "차감 시간", table: "PlanUsage"), value: usedTime(broadcast.chargedSeconds))
                        }.padding(.vertical, 6)
                    }
                }
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

    private func timeRow(_ title: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Text(value)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.secondary)
                .fixedSize()
        }
    }

    private func nullableTime(_ seconds: Int?, present: Bool) -> String {
        present ? PlanTimeText.duration(seconds) : String(localized: "조회 대기")
    }

    private func knownTime(_ seconds: Int?) -> String {
        seconds.map { PlanTimeText.duration($0) } ?? String(localized: "조회 대기")
    }

    private func usedTime(_ seconds: Int?) -> String {
        guard let seconds else { return String(localized: "조회 대기") }
        return seconds == 0 ? "00:00:00" : PlanTimeText.duration(seconds)
    }

    private func modeRow(_ mode: BroadcastPlanMode, snapshot: PlanSnapshot) -> some View {
        Button {
            if snapshot.isAllowed(mode, processing: processing) { selectedMode = mode }
            else { showsPlanGuide = true }
        } label: {
            HStack {
                Image(systemName: snapshot.isAllowed(mode, processing: processing) ? "video" : "lock.fill")
                Text(mode.title)
                Spacer()
                if let availability = snapshot.maximumBroadcastAvailability(for: mode, processing: processing) {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(snapshot.isAllowed(mode, processing: processing) ? PlanTimeText.duration(availability.seconds) : String(localized: "잠김"))
                        Text(String(localized: "\(availability.multiplier)배 차감")).font(.caption)
                    }
                } else {
                    Text(processing == .server || snapshot.plan.deviceModes != nil && !snapshot.isAllowed(mode, processing: processing)
                         ? String(localized: "잠김") : String(localized: "조회 대기"))
                }
            }.padding(.vertical, 6)
        }.buttonStyle(.plain)
    }
}
