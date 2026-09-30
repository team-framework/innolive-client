import SwiftUI

/// Shared presentation only within the iOS client. Requests remain in Integration.
struct BroadcastProblemPresenter: ViewModifier {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    var enabled = true
    var onRetry: () -> Void
    @State private var destination: BroadcastProblem.Action = .none
    @State private var planMessage = ""
    @Environment(\.openURL) private var openURL

    private var modal: BroadcastProblem? {
        guard enabled, let problem = youtube.problem,
              [.alert, .confirmation].contains(problem.presentation) else { return nil }
        return problem
    }

    func body(content: Content) -> some View {
        content
            .alert(String(localized: "방송 안내"), isPresented: Binding(
                get: { modal != nil },
                set: { shown in if !shown { youtube.dismissError() } }
            ), presenting: modal) { problem in
                if problem.presentation == .confirmation {
                    Button(String(localized: "계속")) {
                        Task { await youtube.confirmConcurrentPreparation(accessToken: authentication.currentAccessToken()) }
                    }
                    Button(String(localized: "취소"), role: .cancel) { youtube.cancelConcurrentPreparation() }
                } else {
                    switch problem.action {
                    case .accounts:
                        Button(String(localized: "계정 연결")) { destination = .accounts; youtube.dismissError() }
                    case .plan:
                        Button(String(localized: "플랜 안내")) { planMessage = problem.userMessage; destination = .plan; youtube.dismissError() }
                    case .help:
                        Button(String(localized: "활성화 안내")) {
                            if let url = problem.helpURL, ["https", "http"].contains(url.scheme ?? "") { openURL(url) }
                            youtube.dismissError()
                        }
                    case .retry:
                        Button(String(localized: "다시 시도")) { youtube.dismissError(); onRetry() }
                    case .none: EmptyView()
                    }
                    Button(String(localized: "확인"), role: .cancel) { youtube.dismissError() }
                }
            } message: { problem in Text(problem.userMessage) }
            .sheet(isPresented: Binding(get: { destination == .accounts || destination == .plan }, set: { if !$0 { destination = .none } })) {
                NavigationStack {
                    Group {
                        if destination == .accounts {
                            BroadcastPlatformSelectionView(authentication: authentication, youtube: youtube)
                        } else {
                            BroadcastPlanInformationView(authentication: authentication, youtube: youtube, restriction: planMessage)
                        }
                    }
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(String(localized: "닫기")) { destination = .none }
                        }
                    }
                }
            }
    }
}

struct BroadcastNoticeList: View {
    @ObservedObject var youtube: YouTubeIntegration
    var body: some View {
        ForEach(youtube.noticeBanners) { banner in
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: banner.isError ? "exclamationmark.triangle.fill" : "info.circle.fill")
                    .foregroundStyle(banner.isError ? Color.orange : Color.blue)
                Text(banner.message).font(.caption).frame(maxWidth: .infinity, alignment: .leading)
                Button { youtube.dismissNotice(banner.id) } label: { Image(systemName: "xmark") }
                    .accessibilityLabel(String(localized: "알림 닫기"))
            }
            .padding(10)
            .innoLiveGlassBackground(cornerRadius: 14)
        }
    }
}

struct BroadcastFieldError: View {
    let problem: BroadcastProblem?
    let field: String
    var body: some View {
        if let problem, problem.presentation == .inline, problem.field == field {
            Text(problem.userMessage).font(.caption).foregroundStyle(.red)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct BroadcastPlanInformation: Decodable {
    let plan: String
    let allowedModes: [String]
    let monthlyBroadcastSeconds: Int?
    let maxPerBroadcastSeconds: Int?
    enum CodingKeys: String, CodingKey {
        case plan
        case allowedModes = "allowed_modes"
        case monthlyBroadcastSeconds = "monthly_broadcast_seconds"
        case maxPerBroadcastSeconds = "max_per_broadcast_seconds"
    }
}

private struct BroadcastPlanInformationView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    let restriction: String
    @State private var plan: BroadcastPlanInformation?
    @State private var loadError: String?
    @State private var isLoading = false

    var body: some View {
        List {
            Text(restriction)
            if isLoading { ProgressView() }
            if let plan {
                LabeledContent(String(localized: "현재 플랜"), value: plan.plan.capitalized)
                LabeledContent(String(localized: "월 방송 한도"), value: duration(plan.monthlyBroadcastSeconds))
                LabeledContent(String(localized: "1회 방송 한도"), value: duration(plan.maxPerBroadcastSeconds))
                Section(String(localized: "사용 가능한 방식")) {
                    ForEach(plan.allowedModes, id: \.self) { mode in Text(modeTitle(mode)) }
                }
            }
            if let loadError {
                Text(loadError).foregroundStyle(.red)
                Button(String(localized: "다시 시도")) { Task { await load() } }
            }
        }
        .navigationTitle(String(localized: "플랜 안내"))
        .task { await load() }
    }

    private func load() async {
        guard !isLoading else { return }
        isLoading = true
        loadError = nil
        defer { isLoading = false }
        do { plan = try await youtube.broadcastPlanInformation(accessToken: authentication.currentAccessToken()) }
        catch { loadError = (error as? YouTubeAPIError)?.userMessage ?? String(localized: "플랜 정보를 불러오지 못했습니다.") }
    }
    private func duration(_ seconds: Int?) -> String {
        guard let seconds, seconds != 0 else { return String(localized: "무제한") }
        return String(format: String(localized: "%d분"), seconds / 60)
    }
    private func modeTitle(_ mode: String) -> String {
        switch mode {
        case "720p_single": return String(localized: "720p 단독 송출")
        case "fhd_single": return String(localized: "FHD 단독 송출")
        case "720p_multi": return String(localized: "720p 동시 송출")
        case "fhd_multi": return String(localized: "FHD 동시 송출")
        default: return mode
        }
    }
}
