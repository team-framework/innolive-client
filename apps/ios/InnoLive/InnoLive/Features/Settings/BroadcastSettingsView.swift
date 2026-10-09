import SwiftUI

struct BroadcastSettingsView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @ObservedObject private var planStore: PlanStore
    @ObservedObject private var editor: BroadcastSettingsEditor
    @State private var query = ""
    @State private var isSaved = false
    @State private var isShowingTransmissionNotice = false
    @State private var isShowingMediaConsent = false
    @State private var transmissionNotice = YouTubeTransmissionNotice()
    private let modeProvider: BroadcastSettingsProvider?
    private let upgradeSettings: Bool
    private let onModeSettingsSaved: (() -> Void)?
    private let liveProvider: BroadcastSettingsProvider?
    private let onPrepare: ((BroadcastSettingsProvider) -> Void)?
    private let onCancelPreparation: (() -> Void)?
    private let onContinuePreparation: (() -> Void)?

    init(authentication: AuthSession, youtube: YouTubeIntegration,
         liveProvider: BroadcastSettingsProvider? = nil,
         modeProvider: BroadcastSettingsProvider? = nil,
         upgradeSettings: Bool = false,
         onModeSettingsSaved: (() -> Void)? = nil,
         onPrepare: ((BroadcastSettingsProvider) -> Void)? = nil,
         onCancelPreparation: (() -> Void)? = nil,
         onContinuePreparation: (() -> Void)? = nil) {
        self.authentication = authentication
        self.youtube = youtube
        self.planStore = youtube.planStore
        self.modeProvider = modeProvider
        self.upgradeSettings = upgradeSettings
        self.onModeSettingsSaved = onModeSettingsSaved
        self.liveProvider = liveProvider
        self.onPrepare = onPrepare
        self.onCancelPreparation = onCancelPreparation
        self.onContinuePreparation = onContinuePreparation
        _editor = ObservedObject(wrappedValue: liveProvider == nil ? youtube.settingsEditor : youtube.liveSettingsEditor)
    }

    private var token: String { authentication.currentAccessToken() ?? "" }
    private var contextKey: String {
        youtube.settingsScopeKey(accessToken: token) + ":" + (liveProvider ?? modeProvider ?? editor.provider).rawValue + ":" + (youtube.session?.sessionID ?? "")
    }

    private var isLiveEditing: Bool { liveProvider != nil }
    private var isFormLocked: Bool {
        if let liveProvider { return youtube.isSavingLiveSettings || !youtube.canEditLiveBroadcast(liveProvider) }
        if let modeProvider {
            return !youtube.canChangeBroadcastMode || !youtube.isSettingsAccountConnected(modeProvider)
                || youtube.liveEditingTargets.contains(modeProvider)
                || (upgradeSettings && (youtube.upgradeOffer?.isExpired() != false || !youtube.upgradeSettingsProviders.contains(modeProvider)))
        }
        return youtube.isBroadcastSettingsLocked
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if upgradeSettings, let offer = youtube.upgradeOffer, let expiration = offer.expirationDate {
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        Text(String(localized: "설정 저장까지 남은 시간 \(max(0, Int(ceil(expiration.timeIntervalSince(context.date)))))초", table: "UpgradeOffer"))
                            .font(.footnote).monospacedDigit()
                    }
                    if let message = youtube.upgradeOfferError {
                        Text(message).font(.footnote).foregroundStyle(.red)
                    }
                }
                if !isLiveEditing && modeProvider == nil {
                    NavigationLink {
                        BroadcastPlatformSelectionView(authentication: authentication, youtube: youtube)
                    } label: {
                        Label(String(localized: "플랫폼 계정 연결"), systemImage: "person.crop.circle")
                    }
                    .broadcastTutorialAnchor(.connectAccount)
                    BroadcastTargetSelectionView(youtube: youtube)
                    Text(String(localized: "설정을 편집할 플랫폼", table: "Simulcast")).font(.caption).foregroundStyle(.secondary)
                    Picker(String(localized: "설정을 편집할 플랫폼", table: "Simulcast"), selection: Binding(
                        get: { youtube.selectedBroadcastProviders.contains(editor.provider) ? editor.provider
                            : (BroadcastSettingsProvider.allCases.first(where: youtube.selectedBroadcastProviders.contains) ?? .youtube) }, set: { editor.select($0); query = "" })) {
                        ForEach(BroadcastSettingsProvider.allCases.filter(youtube.selectedBroadcastProviders.contains)) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .disabled(youtube.isBroadcastSettingsLocked)

                    preparationSection

                    PlanUsageSection(authentication: authentication, youtube: youtube)

                    defaultsSection
                    ForEach(youtube.liveEditingTargets) { provider in
                        NavigationLink {
                            BroadcastSettingsView(authentication: authentication, youtube: youtube, liveProvider: provider)
                        } label: {
                            Label("\(provider.title) · \(String(localized: "방송 정보 수정"))", systemImage: "pencil")
                        }
                        .disabled(!youtube.canEditLiveBroadcast(provider) || youtube.isSavingLiveSettings)
                    }
                }
                if editor.provider == .youtube { youtubeForm } else { chzzkForm }

                if isLiveEditing {
                    Text(String(localized: "송출을 유지한 채 제목·카테고리를 수정합니다. 공개 범위·시청자층·썸네일은 방송 중 변경할 수 없습니다."))
                        .font(.footnote).foregroundStyle(.secondary)
                    if let liveProvider, !youtube.canEditLiveBroadcast(liveProvider) {
                        Text(String(localized: "현재 이 플랫폼의 방송 정보를 수정할 수 없습니다."))
                            .font(.footnote).foregroundStyle(.orange)
                    }
                    if let message = youtube.liveSettingsError {
                        Text(message).font(.footnote).foregroundStyle(.red)
                    }
                } else if modeProvider == nil && youtube.isBroadcastSettingsLocked {
                    Text(String(localized: "방송을 준비하거나 송출하는 동안에는 방송 정보를 변경할 수 없습니다."))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if onPrepare != nil, !youtube.selectedBroadcastProviders.allSatisfy(youtube.isSettingsAccountConnected) {
                    Text(String(localized: "선택한 플랫폼의 계정을 먼저 연결해 주세요."))
                        .font(.footnote).foregroundStyle(.orange)
                }
                if modeProvider != nil, let message = youtube.broadcastModeError {
                    Text(message).font(.footnote).foregroundStyle(.red)
                }
                if !isLiveEditing, let message = youtube.errorMessage {
                    BroadcastFeedbackBanner(feedback: BroadcastFeedback(message: message, isError: true),
                                            youtube: youtube, onDismiss: youtube.dismissError)
                }
                if isLiveEditing { fieldError("thumbnail") }
                Button(action: primaryAction) {
                    HStack {
                        if youtube.isChangingStreamState || youtube.isSavingLiveSettings || youtube.isChangingBroadcastMode { ProgressView() }
                        Text(prepareButtonTitle)
                            .font(.body.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity, minHeight: 46)
                }
                .innoLiveGlassButtonStyle(prominent: true)
                .tint(.blue)
                .disabled(isPrepareButtonDisabled)
                .broadcastTutorialAnchor(.startPreparation)
            }
            .padding(24)
        }
        .navigationTitle(isLiveEditing ? String(localized: "방송 정보 수정") : String(localized: "방송 설정"))
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: youtube.selectedBroadcastProviders) { _, selected in
            if !isLiveEditing, modeProvider == nil, !selected.contains(editor.provider),
               let provider = BroadcastSettingsProvider.allCases.first(where: selected.contains) {
                editor.select(provider)
                query = ""
            }
        }
        .onChange(of: query) { _, _ in editor.invalidateCategorySearch() }
        .task { if !isLiveEditing, !token.isEmpty { await youtube.refreshConnection(accessToken: token) } }
        .task(id: contextKey) {
            if let liveProvider {
                youtube.beginLiveEditing(liveProvider, accessToken: token)
            } else {
                youtube.configureSettingsEditor(accessToken: token)
                if let modeProvider {
                    editor.select(modeProvider)
                } else if !youtube.selectedBroadcastProviders.contains(editor.provider),
                   let provider = BroadcastSettingsProvider.allCases.first(where: youtube.selectedBroadcastProviders.contains) {
                    editor.select(provider)
                }
                await editor.loadDefaults(session: youtube.session, accessToken: token, provider: editor.provider)
            }
            let provider = editor.provider
            if provider == .youtube, !token.isEmpty { await editor.searchCategories(query: "", accessToken: token) }
        }
        .alert(String(localized: "방송 설정 저장 완료"), isPresented: $isSaved) {
            Button(String(localized: "확인"), role: .cancel) {}
        } message: {
            Text(isLiveEditing ? String(localized: "송출을 유지한 채 방송 정보를 반영했습니다.") : (youtube.session == nil ? String(localized: "기기에 저장했습니다. 방송 준비 시 서버에 적용합니다.") : String(localized: "서버에 방송 설정을 저장했습니다.")))
        }
        .sheet(isPresented: $isShowingMediaConsent) {
            MediaTransmissionConsentView { consent in
                guard authentication.acceptMediaTransmission(consent) else { return }
                continuePrepare()
            }
        }
        .sheet(isPresented: $isShowingTransmissionNotice) {
            YouTubeTransmissionNoticeView { consent in
                guard youtube.acknowledgeYouTubeTransmission(consent) else { return }
                if modeProvider != nil { saveModeSettings() }
                else { onPrepare?(editor.provider) }
            }
        }
    }

    private var prepareButtonTitle: String {
        if upgradeSettings { return String(localized: "저장 후 전환", table: "UpgradeOffer") }
        if isLiveEditing { return youtube.isSavingLiveSettings ? String(localized: "저장 중") : String(localized: "저장") }
        if onPrepare == nil { return String(localized: "저장") }
        if youtube.preparationStatus?.isFailed == true { return String(localized: "다시 시도") }
        return String(localized: "방송 준비")
    }

    private var isPrepareButtonDisabled: Bool {
        if isLiveEditing || modeProvider != nil { return isFormLocked }
        if onPrepare == nil { return youtube.isBroadcastSettingsLocked }
        if youtube.preparationStatus?.isRunning == true || youtube.preparationStatus?.phase == .cancelling {
            return true
        }
        if planStore.snapshot.map({ !$0.canPrepare(youtube.preparationPlanMode) }) == true { return true }
        if youtube.selectedBroadcastProviders.isEmpty || !youtube.selectedBroadcastProviders.allSatisfy(youtube.isSettingsAccountConnected) { return true }
        return youtube.isBroadcastSettingsLocked && youtube.preparationStatus?.isFailed != true
    }

    @ViewBuilder
    private var preparationSection: some View {
        if let status = youtube.preparationStatus {
            // 안내가 준비 진행 영역 전체를 한 번에 가리키도록 묶는다. 간격은 바깥 VStack과 같다.
            VStack(alignment: .leading, spacing: 16) {
                Text(status.isFailed ? (status.failedPhase?.failureMessage ?? status.phase.failureMessage) : status.phase.title)
                    .font(.footnote.weight(.semibold))
                if youtube.preparationFailures.isEmpty {
                    Text(status.message ?? status.phase.detail).font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(BroadcastSettingsProvider.allCases) { provider in
                    if let failure = youtube.preparationFailures[provider] {
                        Text(failure.message ?? failure.phase.failureMessage).font(.footnote).foregroundStyle(.orange)
                    } else if youtube.targetPolicy(provider).broadcastPhase == .prepared {
                        Text("\(provider.title) · \(String(localized: "준비 완료", table: "Simulcast"))")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if status.isFailed {
                    Button(String(localized: "실패한 플랫폼 다시 준비", table: "Simulcast"), action: primaryAction)
                        .disabled(isPrepareButtonDisabled)
                }
                if youtube.canContinuePreparedBroadcast {
                    Button(String(localized: "준비된 플랫폼으로 계속", table: "Simulcast")) {
                        if youtube.continueWithPreparedTargets() { onContinuePreparation?() }
                    }
                    .accessibilityIdentifier("simulcast-continue-ready")
                    Text(String(localized: "계속해도 바로 방송이 시작되지 않습니다. 방송 시작을 눌러 공개하세요.", table: "Simulcast"))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if onCancelPreparation != nil {
                    Button(status.phase == .cancelling ? String(localized: "준비 취소 중") : String(localized: "준비 취소"), role: .destructive) {
                        onCancelPreparation?()
                    }
                    .disabled(status.phase == .cancelling)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .broadcastTutorialAnchor(.preparationProgress)
        }
    }

    private var defaultsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(String(localized: "직전 방송 값 사용"), isOn: Binding(
                get: { editor.usesDefaults[editor.provider] == true },
                set: { enabled in
                    let provider = editor.provider
                    editor.setUsesDefaults(enabled, provider: provider)
                    if enabled { Task { await editor.loadDefaults(session: youtube.session, accessToken: token, provider: provider) } }
                }))
                .disabled(youtube.isBroadcastSettingsLocked)
            if editor.loadingDefaults.contains(editor.provider) {
                ProgressView(String(localized: "직전 방송 값 불러오는 중"))
            }
            if let message = editor.defaultsMessages[editor.provider] {
                Text(message).font(.footnote).foregroundStyle(.secondary)
                Button(String(localized: "다시 불러오기")) {
                    Task { await editor.loadDefaults(session: youtube.session, accessToken: token, provider: editor.provider) }
                }.disabled(isFormLocked)
            }
        }
    }

    private var youtubeForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            titleField(text: Binding(get: { editor.youtube.title }, set: { value in editor.editYouTube { $0.title = value } }))
            card {
                VStack(alignment: .leading) {
                    Text(String(localized: "YouTube 방송 설명")).font(.body.weight(.semibold))
                    TextEditor(text: Binding(get: { editor.youtube.description }, set: { value in editor.editYouTube { $0.description = value } }))
                        .frame(minHeight: 96).scrollContentBackground(.hidden)
                    fieldError("description")
                }
            }
            card {
                VStack(alignment: .leading, spacing: 8) {
                    Text(String(localized: "공개 범위")).font(.body.weight(.semibold))
                    Picker(String(localized: "공개 범위"), selection: Binding(get: { editor.youtube.privacy }, set: { value in editor.editYouTube { $0.privacy = value } })) {
                        ForEach(YouTubeBroadcastPrivacy.allCases) { Text($0.title).tag($0) }
                    }.labelsHidden().disabled(isLiveEditing)
                    fieldError("privacy")
                }
            }
            card {
                VStack(alignment: .leading, spacing: 8) {
                    Text(String(localized: "YouTube 시청자층")).font(.body.weight(.semibold))
                    Picker(String(localized: "YouTube 시청자층"), selection: Binding(get: { editor.youtube.audience }, set: { value in editor.editYouTube { $0.audience = value } })) {
                        Text(String(localized: "선택 필요")).tag(Optional<YouTubeBroadcastAudience>.none)
                        ForEach(YouTubeBroadcastAudience.allCases) { Text($0.title).tag(Optional($0)) }
                    }
                    .disabled(isLiveEditing)
                    fieldError("made_for_kids")
                }
            }
            card {
                VStack(alignment: .leading, spacing: 8) {
                    Text(String(localized: "카테고리")).font(.body.weight(.semibold))
                    Picker(String(localized: "카테고리"), selection: Binding(get: { editor.youtube.categoryID ?? "" }, set: { value in editor.editYouTube { $0.categoryID = value } })) {
                        Text(String(localized: "기본 카테고리")).tag("")
                        if let id = editor.youtube.categoryID, !id.isEmpty, !editor.youtubeCategories.contains(where: { $0.id == id }) {
                            Text(id).tag(id)
                        }
                        ForEach(editor.youtubeCategories) { Text($0.title).tag($0.id) }
                    }
                    categoryStatus
                    fieldError("category_id")
                    Button(String(localized: "카테고리 목록 새로고침")) {
                        Task { await editor.searchCategories(query: "", accessToken: token) }
                    }.disabled(editor.isSearching)
                }
            }
        }.disabled(isFormLocked)
    }

    private var chzzkForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            titleField(text: Binding(get: { editor.chzzk.title }, set: { value in editor.editCHZZK { $0.title = value } }))
            card {
                VStack(alignment: .leading, spacing: 10) {
                    Text(String(localized: "치지직 카테고리")).font(.body.weight(.semibold))
                    Text(editor.chzzk.categoryID.isEmpty ? String(localized: "카테고리 없음") : "\(editor.chzzk.categoryType) · \(editor.chzzk.categoryID)")
                        .font(.callout).foregroundStyle(.secondary)
                    Button(String(localized: "카테고리 없음")) {
                        editor.editCHZZK { $0.categoryType = ""; $0.categoryID = "" }
                    }
                    HStack {
                        TextField(String(localized: "카테고리 검색어"), text: $query).submitLabel(.search)
                            .onSubmit(searchCHZZK)
                        Button(String(localized: "검색"), action: searchCHZZK).disabled(editor.isSearching)
                    }
                    categoryStatus
                    ForEach(editor.chzzkCategories) { category in
                        Button(category.categoryValue) {
                            editor.editCHZZK { $0.categoryType = category.categoryType; $0.categoryID = category.categoryID }
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }
                    fieldError("category_type")
                    fieldError("category_id")
                }
            }
            card {
                VStack(alignment: .leading, spacing: 8) {
                    Text(String(localized: "치지직 태그")).font(.body.weight(.semibold))
                    TextField(String(localized: "쉼표로 구분해 입력"), text: Binding(
                        get: { editor.chzzk.tags.joined(separator: ",") },
                        set: { value in editor.editCHZZK { $0.tags = value.isEmpty ? [] : value.components(separatedBy: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) } } }))
                    Text(String(localized: "최대 5개 · 각 15자 · 한글, 영문, 숫자"))
                        .font(.footnote).foregroundStyle(.secondary)
                    fieldError("tags")
                }
            }
        }.disabled(isFormLocked)
    }

    private func titleField(text: Binding<String>) -> some View {
        card {
            VStack(alignment: .leading, spacing: 8) {
                Text(String(localized: "방송 제목")).font(.body.weight(.semibold))
                TextField(String(localized: "방송 제목을 입력해 주세요"), text: text)
                Text("\(text.wrappedValue.unicodeScalars.count)/100").font(.caption).foregroundStyle(.secondary)
                fieldError("title")
            }
        }
    }
    @ViewBuilder private var categoryStatus: some View {
        if editor.isSearching { ProgressView() }
        if let message = editor.categoryMessage { Text(message).font(.footnote).foregroundStyle(.secondary) }
    }
    @ViewBuilder private func fieldError(_ field: String) -> some View {
        if let message = editor.fieldErrors[field] { Text(message).font(.footnote).foregroundStyle(.red) }
    }
    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content().padding(16).frame(maxWidth: .infinity, alignment: .leading).innoLiveGlassBackground(cornerRadius: 16)
    }
    private func searchCHZZK() { Task { await editor.searchCategories(query: query, accessToken: token) } }
    private func primaryAction() {
        if isLiveEditing {
            Task { isSaved = await youtube.saveLiveSettings(accessToken: token) }
            return
        }
        if let modeProvider {
            guard !isFormLocked else { return }
            if modeProvider == .youtube, !youtube.hasAcknowledgedYouTubeTransmission {
                transmissionNotice.reset()
                isShowingTransmissionNotice = true
            } else {
                saveModeSettings()
            }
            return
        }
        editor.showValidation()
        guard editor.validation.isEmpty else { return }
        guard onPrepare != nil else {
            Task { isSaved = await youtube.saveEditorSettings(accessToken: token) }
            return
        }
        guard !youtube.selectedBroadcastProviders.isEmpty, youtube.selectedBroadcastProviders.allSatisfy(youtube.isSettingsAccountConnected) else { return }
        guard authentication.hasAcceptedMediaTransmission else {
            isShowingMediaConsent = true
            return
        }
        continuePrepare()
    }

    private func saveModeSettings() {
        guard let modeProvider, !isFormLocked else { return }
        Task {
            if upgradeSettings {
                let saved = await youtube.saveUpgradeOfferSettings(provider: modeProvider, accessToken: token)
                if saved { onModeSettingsSaved?() }
            } else {
                isSaved = await youtube.saveBroadcastModeSettings(provider: modeProvider, accessToken: token)
                if isSaved { onModeSettingsSaved?() }
            }
        }
    }

    private func continuePrepare() {
        if youtube.selectedBroadcastProviders.contains(.youtube), !youtube.hasAcknowledgedYouTubeTransmission {
            transmissionNotice.reset()
            isShowingTransmissionNotice = true
        } else {
            onPrepare?(editor.provider)
        }
    }
}

#Preview {
    NavigationStack { BroadcastSettingsView(authentication: AuthSession(), youtube: YouTubeIntegration()) }
}
