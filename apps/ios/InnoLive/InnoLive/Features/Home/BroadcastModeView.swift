import SwiftUI

struct BroadcastModeView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @ObservedObject private var planStore: PlanStore
    @State private var resolution: String
    @State private var targets: Set<BroadcastSettingsProvider>
    @State private var savedTargets: Set<BroadcastSettingsProvider> = []
    @State private var isConfirmingResolution = false
    @State private var awaitingCompletion = false

    init(authentication: AuthSession, youtube: YouTubeIntegration) {
        self.authentication = authentication
        self.youtube = youtube
        self.planStore = youtube.planStore
        _resolution = State(initialValue: youtube.broadcastResolution ?? "720p")
        _targets = State(initialValue: Set(youtube.liveEditingTargets))
    }

    private var currentTargets: Set<BroadcastSettingsProvider> { Set(youtube.liveEditingTargets) }
    private var addedTargets: Set<BroadcastSettingsProvider> { targets.subtracting(currentTargets) }
    private var changesResolution: Bool { resolution != (youtube.broadcastResolution ?? "720p") }
    private var selectedMode: BroadcastPlanMode { .current(resolution: resolution, targetCount: targets.count) }
    private var isLocked: Bool { !youtube.canChangeBroadcastMode || youtube.isSavingLiveSettings }
    private var canSubmit: Bool {
        !isLocked && !targets.isEmpty
            && (changesResolution || targets != currentTargets)
            && targets.allSatisfy(youtube.isSettingsAccountConnected)
            && addedTargets.isSubset(of: savedTargets)
            && planStore.snapshot.map { $0.canPrepare(selectedMode) } != false
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                card {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(String(localized: "방송 화질", table: "BroadcastMode")).font(.headline)
                        Picker(String(localized: "방송 화질", table: "BroadcastMode"), selection: $resolution) {
                            Text("720p").tag("720p")
                            Text("FHD").tag("fhd")
                        }
                        .pickerStyle(.segmented)
                        .disabled(isLocked)
                    }
                }
                card {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(String(localized: "송출할 플랫폼", table: "Simulcast")).font(.headline)
                        ForEach(BroadcastSettingsProvider.allCases) { provider in
                            Toggle(provider.title, isOn: Binding(
                                get: { targets.contains(provider) },
                                set: { selected in
                                    if selected { targets.insert(provider) }
                                    else if targets.count > 1 {
                                        targets.remove(provider)
                                        savedTargets.remove(provider)
                                    }
                                }
                            ))
                            .disabled(isLocked || (!targets.contains(provider) && !youtube.isSettingsAccountConnected(provider)))
                            .accessibilityIdentifier("mode-target-" + provider.rawValue)
                            if !youtube.isSettingsAccountConnected(provider) {
                                Text(provider.title + " · " + String(localized: "계정 연결 필요", table: "Simulcast"))
                                    .font(.caption).foregroundStyle(.orange)
                            }
                            if addedTargets.contains(provider) {
                                NavigationLink {
                                    BroadcastSettingsView(authentication: authentication, youtube: youtube,
                                                          modeProvider: provider,
                                                          onModeSettingsSaved: { savedTargets.insert(provider) })
                                        .onAppear { savedTargets.remove(provider) }
                                } label: {
                                    Label(provider.title + " · " + String(localized: "방송 설정", table: "BroadcastMode"),
                                          systemImage: savedTargets.contains(provider) ? "checkmark.circle" : "pencil")
                                }
                                .disabled(isLocked)
                                .accessibilityIdentifier("mode-settings-" + provider.rawValue)
                            }
                        }
                        if !addedTargets.isEmpty {
                            Text(String(localized: "추가할 플랫폼의 방송 설정을 확인하고 저장해 주세요.", table: "BroadcastMode"))
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                if changesResolution {
                    card {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(String(localized: "화질 변경 안내", table: "BroadcastMode")).font(.headline)
                            Text(String(localized: "화질을 바꾸면 방송이 잠시 끊길 수 있습니다.", table: "BroadcastMode"))
                            if targets.contains(.youtube) {
                                Text(String(localized: "YouTube는 새 방송 링크를 만듭니다. 시청자에게 새 링크를 안내해 주세요.", table: "BroadcastMode"))
                            }
                            if targets.contains(.chzzk) {
                                Text(String(localized: "치지직은 같은 링크를 유지하며 약 15초 동안 방송이 끊길 수 있습니다.", table: "BroadcastMode"))
                            }
                        }.font(.footnote)
                    }
                }
                if let snapshot = planStore.snapshot, !snapshot.canPrepare(selectedMode) {
                    Text(snapshot.isAllowed(selectedMode)
                         ? String(localized: "선택한 방식으로 방송할 수 있는 시간이 부족합니다.", table: "BroadcastMode")
                         : String(localized: "현재 요금제에서 사용할 수 없는 방송 방식입니다.", table: "BroadcastMode"))
                        .font(.footnote).foregroundStyle(.orange)
                }
                if youtube.isChangingBroadcastMode {
                    ProgressView(String(localized: "방송 방식 변경 중", table: "BroadcastMode"))
                } else if youtube.isSavingLiveSettings {
                    Text(String(localized: "방송 정보 저장이 끝난 뒤 변경해 주세요.", table: "BroadcastMode"))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if let error = youtube.broadcastModeError {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
                ForEach(youtube.broadcastModeFailures, id: \.provider) { failure in
                    let title = BroadcastSettingsProvider(rawValue: failure.provider)?.title ?? failure.provider
                    Text(title + " · " + String(localized: "방송 방식 변경 실패", table: "BroadcastMode"))
                        .font(.footnote).foregroundStyle(.orange)
                }
                if let error = planStore.errorMessage {
                    Text(error).font(.footnote).foregroundStyle(.orange)
                    Button(String(localized: "다시 시도")) {
                        Task { await youtube.refreshPlan(accessToken: authentication.currentAccessToken()) }
                    }.disabled(planStore.isLoading || isLocked)
                }
                Button {
                    guard canSubmit else { return }
                    if changesResolution { isConfirmingResolution = true }
                    else { submit() }
                } label: {
                    Text(String(localized: "방송 방식 변경", table: "BroadcastMode"))
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 46)
                }
                .innoLiveGlassButtonStyle(prominent: true)
                .tint(.blue)
                .disabled(!canSubmit)
                .accessibilityIdentifier("broadcast-mode-submit")
            }.padding(24)
        }
        .navigationTitle(String(localized: "방송 방식 변경", table: "BroadcastMode"))
        .navigationBarTitleDisplayMode(.inline)
        .task { await youtube.refreshPlan(accessToken: authentication.currentAccessToken()) }
        .onChange(of: youtube.isChangingBroadcastMode) { previous, changing in
            if previous && !changing && awaitingCompletion { syncCompletedMode() }
        }
        .onChange(of: youtube.session?.sessionID) { _, _ in
            resolution = youtube.broadcastResolution ?? "720p"
            targets = currentTargets
            savedTargets = []
            awaitingCompletion = false
        }
        .alert(String(localized: "방송 화질을 변경할까요?", table: "BroadcastMode"), isPresented: $isConfirmingResolution) {
            Button(String(localized: "취소"), role: .cancel) {}
            Button(String(localized: "변경", table: "BroadcastMode")) { submit() }
                .disabled(!canSubmit)
        } message: {
            Text(confirmationMessage)
        }
    }

    private var confirmationMessage: String {
        var lines = [String(localized: "화질을 바꾸면 방송이 잠시 끊길 수 있습니다.", table: "BroadcastMode")]
        if targets.contains(.youtube) {
            lines.append(String(localized: "YouTube는 새 방송 링크를 만듭니다. 시청자에게 새 링크를 안내해 주세요.", table: "BroadcastMode"))
        }
        if targets.contains(.chzzk) {
            lines.append(String(localized: "치지직은 같은 링크를 유지하며 약 15초 동안 방송이 끊길 수 있습니다.", table: "BroadcastMode"))
        }
        return lines.joined(separator: "\n\n")
    }

    private func submit() {
        guard canSubmit else { return }
        let requestedResolution = resolution
        let requestedTargets = targets
        awaitingCompletion = true
        Task {
            let succeeded = await youtube.changeBroadcastMode(resolution: requestedResolution, targets: requestedTargets,
                                                              accessToken: authentication.currentAccessToken())
            if !succeeded && !youtube.isChangingBroadcastMode { awaitingCompletion = false }
            else if !youtube.isChangingBroadcastMode { syncCompletedMode() }
        }
    }

    private func syncCompletedMode() {
        resolution = youtube.broadcastResolution ?? resolution
        targets = currentTargets
        savedTargets = []
        awaitingCompletion = false
    }

    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content().padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .innoLiveGlassBackground(cornerRadius: 16)
    }
}
