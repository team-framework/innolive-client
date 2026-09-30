import Combine
import Foundation

// 동일 편집기를 방송 준비와 추가 플랫폼 설정 화면에서 재사용한다.
@MainActor
final class BroadcastSettingsEditor: ObservableObject {
    @Published var provider: BroadcastSettingsProvider = .youtube
    @Published private(set) var youtube = YouTubeBroadcastSettings.defaultValue
    @Published private(set) var chzzk = CHZZKBroadcastSettings()
    @Published private(set) var usesDefaults: [BroadcastSettingsProvider: Bool] = [.youtube: true, .chzzk: true]
    @Published private(set) var defaultsMessages: [BroadcastSettingsProvider: String] = [:]
    @Published private(set) var loadingDefaults: Set<BroadcastSettingsProvider> = []
    @Published private(set) var youtubeCategories: [YouTubeBroadcastCategory] = []
    @Published private(set) var chzzkCategories: [CHZZKBroadcastCategory] = []
    @Published private(set) var categoryMessage: String?
    @Published private(set) var isSearching = false
    @Published private(set) var fieldErrors: [String: String] = [:]
    private let api: YouTubeAPI
    private let preferences: YouTubePreferencesStore
    private(set) var contextGeneration = UUID()
    private var context: String?
    private var keys: [BroadcastSettingsProvider: String] = [:]
    private var revisions: [BroadcastSettingsProvider: Int] = [:]
    private var requests: [BroadcastSettingsProvider: UUID] = [:]
    private var searchID = UUID()
    private var baselineYouTube = YouTubeBroadcastSettings.defaultValue
    private var baselineCHZZK = CHZZKBroadcastSettings()

    init(api: YouTubeAPI, preferences: YouTubePreferencesStore) {
        self.api = api
        self.preferences = preferences
    }

    func configure(scope: BroadcastSessionScope?, channels: [String: String]) {
        let next = scope.map { $0.storageKey + ":" + (channels["youtube"] ?? "") + ":" + (channels["chzzk"] ?? "") }
        guard next != context else { return }
        reset()
        context = next
        guard let scope else { return }
        for provider in BroadcastSettingsProvider.allCases {
            let channel = channels[provider.rawValue] ?? "unconnected"
            keys[provider] = scope.storageKey + ":" + provider.rawValue + ":" + Data(channel.utf8).base64EncodedString()
        }
        // 기존 전역 값에는 소유자가 없으므로 새 계정에 이관하지 않는다.
        baselineYouTube = preferences.loadScopedYouTubeSettings(key: keys[.youtube]!)
        baselineCHZZK = preferences.loadScopedCHZZKSettings(key: keys[.chzzk]!)
        youtube = baselineYouTube
        chzzk = baselineCHZZK
    }

    func reset() {
        contextGeneration = UUID()
        context = nil
        keys = [:]
        revisions = [:]
        requests = [:]
        searchID = UUID()
        provider = .youtube
        baselineYouTube = .defaultValue
        baselineCHZZK = CHZZKBroadcastSettings()
        youtube = baselineYouTube
        chzzk = baselineCHZZK
        usesDefaults = [.youtube: true, .chzzk: true]
        defaultsMessages = [:]
        loadingDefaults = []
        youtubeCategories = []
        chzzkCategories = []
        categoryMessage = nil
        isSearching = false
        fieldErrors = [:]
    }

    func editYouTube(_ edit: (inout YouTubeBroadcastSettings) -> Void) {
        edit(&youtube)
        revisions[.youtube, default: 0] += 1
        fieldErrors = [:]
    }
    func editCHZZK(_ edit: (inout CHZZKBroadcastSettings) -> Void) {
        edit(&chzzk)
        revisions[.chzzk, default: 0] += 1
        fieldErrors = [:]
    }
    func select(_ provider: BroadcastSettingsProvider) {
        self.provider = provider
        fieldErrors = [:]
        searchID = UUID()
        isSearching = false
        categoryMessage = nil
        chzzkCategories = []
    }
    func setUsesDefaults(_ enabled: Bool, provider: BroadcastSettingsProvider) {
        usesDefaults[provider] = enabled
        requests[provider] = UUID()
        loadingDefaults.remove(provider)
        defaultsMessages[provider] = nil
        // 직전 값을 적용한 뒤 해제하면 초기에 읽은 로컬 값으로 돌아간다.
        // 직접 편집한 값은 해제로도 지우지 않는다.
        if !enabled, revisions[provider, default: 0] == 0 {
            if provider == .youtube { youtube = baselineYouTube } else { chzzk = baselineCHZZK }
        }
    }

    func loadDefaults(session: YouTubeBroadcastSession?, accessToken: String, provider: BroadcastSettingsProvider) async {
        guard usesDefaults[provider] == true else { return }
        guard let session, context != nil else {
            defaultsMessages[provider] = String(localized: "서버에 연결하면 직전 방송 값을 불러옵니다. 지금 직접 입력할 수 있습니다.")
            return
        }
        let request = UUID()
        requests[provider] = request
        let originalContext = context
        let revision = revisions[provider, default: 0]
        loadingDefaults.insert(provider)
        defer { if requests[provider] == request { loadingDefaults.remove(provider) } }
        do {
            let values = try await api.broadcastDefaults(session: session, accessToken: accessToken, provider: provider)
            guard context == originalContext, requests[provider] == request, usesDefaults[provider] == true,
                  revisions[provider, default: 0] == revision, revision == 0, !Task.isCancelled else { return }
            if provider == .youtube {
                if let title = values.title, !title.isEmpty { youtube.title = title }
                if let description = values.description { youtube.description = description }
                if let privacy = values.privacy.flatMap(YouTubeBroadcastPrivacy.init(rawValue:)) { youtube.privacy = privacy }
                if let kids = values.madeForKids { youtube.audience = kids ? .madeForKids : .notMadeForKids }
                if let category = values.categoryID { youtube.categoryID = category }
            } else {
                if let title = values.title, !title.isEmpty { chzzk.title = title }
                // YouTube 공통 폴백의 category_id를 치지직에 적용하지 않는다.
                chzzk.categoryType = values.categoryType ?? ""
                chzzk.categoryID = chzzk.categoryType.isEmpty ? "" : (values.categoryID ?? "")
                chzzk.tags = values.tags ?? []
            }
            defaultsMessages[provider] = nil
        } catch {
            guard context == originalContext, requests[provider] == request, !Task.isCancelled else { return }
            defaultsMessages[provider] = String(localized: "직전 방송 값을 불러오지 못했습니다. 직접 입력하거나 다시 시도해 주세요.")
        }
    }

    func invalidateCategorySearch() {
        searchID = UUID()
        isSearching = false
        chzzkCategories = []
        categoryMessage = nil
    }

    func searchCategories(query: String, accessToken: String) async {
        let request = UUID()
        searchID = request
        let selected = provider
        let originalContext = context
        categoryMessage = nil
        isSearching = false
        if selected == .chzzk { chzzkCategories = [] }
        guard selected == .youtube || !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            categoryMessage = String(localized: "검색어를 입력해 주세요.")
            return
        }
        isSearching = true
        defer { if searchID == request { isSearching = false } }
        do {
            if selected == .youtube {
                let values = try await api.youtubeCategories(accessToken: accessToken)
                guard searchID == request, context == originalContext, !Task.isCancelled else { return }
                youtubeCategories = values
                if values.isEmpty { categoryMessage = String(localized: "카테고리가 없습니다.") }
            } else {
                let values = try await api.chzzkCategories(query: query, accessToken: accessToken)
                guard searchID == request, context == originalContext, !Task.isCancelled else { return }
                chzzkCategories = values
                if values.isEmpty { categoryMessage = String(localized: "검색 결과가 없습니다.") }
            }
        } catch {
            guard searchID == request, context == originalContext, !Task.isCancelled else { return }
            categoryMessage = String(localized: "카테고리를 불러오지 못했습니다. 다시 시도해 주세요.")
        }
    }

    func cancelDefaults(provider: BroadcastSettingsProvider) {
        requests[provider] = UUID()
        loadingDefaults.remove(provider)
    }

    var validation: [String: String] { provider == .youtube ? youtube.validation : chzzk.validation }
    func showValidation() { fieldErrors = validation }
    func showFieldError(_ error: BroadcastSettingsFieldError) {
        let field = error.field.hasPrefix("tags") ? "tags" : error.field
        fieldErrors[field] = error.message
    }
    func persist(provider: BroadcastSettingsProvider) {
        guard let key = keys[provider] else { return }
        if provider == .youtube { preferences.saveScopedYouTubeSettings(youtube, key: key) }
        else { preferences.saveScopedCHZZKSettings(chzzk, key: key) }
    }
}
