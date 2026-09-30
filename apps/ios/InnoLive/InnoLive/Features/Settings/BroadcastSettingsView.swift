import SwiftUI

struct BroadcastSettingsView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @ObservedObject private var editor: BroadcastSettingsEditor
    @State private var query = ""
    @State private var isSaved = false
    @State private var isShowingTransmissionNotice = false
    @State private var transmissionNotice = YouTubeTransmissionNotice()
    private let onPrepare: ((BroadcastSettingsProvider) -> Void)?

    init(authentication: AuthSession, youtube: YouTubeIntegration,
         onPrepare: ((BroadcastSettingsProvider) -> Void)? = nil) {
        self.authentication = authentication
        self.youtube = youtube
        self.onPrepare = onPrepare
        _editor = ObservedObject(wrappedValue: youtube.settingsEditor)
    }

    private var token: String { authentication.currentAccessToken() ?? "" }
    private var contextKey: String {
        youtube.settingsScopeKey(accessToken: token) + ":" + editor.provider.rawValue + ":" + (youtube.session?.sessionID ?? "")
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                NavigationLink {
                    BroadcastPlatformSelectionView(authentication: authentication, youtube: youtube)
                } label: {
                    Label(String(localized: "플랫폼 계정 연결"), systemImage: "person.crop.circle")
                }
                Picker(String(localized: "방송할 플랫폼"), selection: Binding(
                    get: { editor.provider }, set: { editor.select($0); query = "" })) {
                    ForEach(BroadcastSettingsProvider.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                .disabled(youtube.isBroadcastSettingsLocked)

                defaultsSection
                if editor.provider == .youtube { youtubeForm } else { chzzkForm }

                if youtube.isBroadcastSettingsLocked {
                    Text(String(localized: "방송을 준비하거나 송출하는 동안에는 방송 정보를 변경할 수 없습니다."))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if onPrepare != nil, !youtube.isSettingsAccountConnected(editor.provider) {
                    Text(String(localized: "선택한 플랫폼의 계정을 먼저 연결해 주세요."))
                        .font(.footnote).foregroundStyle(.orange)
                }
                if onPrepare != nil, !youtube.isVideoConnected {
                    Text(String(localized: "서버 영상 연결을 확인해 주세요."))
                        .font(.footnote).foregroundStyle(.orange)
                }
                if let message = youtube.errorMessage {
                    BroadcastFeedbackBanner(feedback: BroadcastFeedback(message: message, isError: true),
                                            youtube: youtube, onDismiss: youtube.dismissError)
                }
                Button(action: primaryAction) {
                    HStack {
                        if youtube.isChangingStreamState { ProgressView() }
                        Text(onPrepare == nil ? String(localized: "저장") : String(localized: "방송 준비"))
                            .font(.body.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity, minHeight: 46)
                }
                .innoLiveGlassButtonStyle(prominent: true)
                .tint(.blue)
                .disabled(youtube.isBroadcastSettingsLocked ||
                          (onPrepare != nil && (!youtube.isSettingsAccountConnected(editor.provider) || !youtube.isVideoConnected)))
            }
            .padding(24)
        }
        .navigationTitle(String(localized: "방송 설정"))
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: query) { _, _ in editor.invalidateCategorySearch() }
        .task { if !token.isEmpty { await youtube.refreshConnection(accessToken: token) } }
        .task(id: contextKey) {
            youtube.configureSettingsEditor(accessToken: token)
            let provider = editor.provider
            await editor.loadDefaults(session: youtube.session, accessToken: token, provider: provider)
            if provider == .youtube, !token.isEmpty { await editor.searchCategories(query: "", accessToken: token) }
        }
        .alert(String(localized: "방송 설정 저장 완료"), isPresented: $isSaved) {
            Button(String(localized: "확인"), role: .cancel) {}
        } message: {
            Text(youtube.session == nil ? String(localized: "기기에 저장했습니다. 방송 준비 시 서버에 적용합니다.") : String(localized: "서버에 방송 설정을 저장했습니다."))
        }
        .sheet(isPresented: $isShowingTransmissionNotice) {
            YouTubeTransmissionNoticeView { consent in
                guard youtube.acknowledgeYouTubeTransmission(consent) else { return }
                onPrepare?(.youtube)
            }
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
                }.disabled(youtube.isBroadcastSettingsLocked)
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
                    }.labelsHidden()
                }
            }
            card {
                VStack(alignment: .leading, spacing: 8) {
                    Text(String(localized: "YouTube 시청자층")).font(.body.weight(.semibold))
                    Picker(String(localized: "YouTube 시청자층"), selection: Binding(get: { editor.youtube.audience }, set: { value in editor.editYouTube { $0.audience = value } })) {
                        Text(String(localized: "선택 필요")).tag(Optional<YouTubeBroadcastAudience>.none)
                        ForEach(YouTubeBroadcastAudience.allCases) { Text($0.title).tag(Optional($0)) }
                    }
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
        }.disabled(youtube.isBroadcastSettingsLocked)
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
        }.disabled(youtube.isBroadcastSettingsLocked)
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
        editor.showValidation()
        guard editor.validation.isEmpty else { return }
        if let onPrepare {
            if editor.provider == .youtube, !youtube.hasAcknowledgedYouTubeTransmission {
                transmissionNotice.reset()
                isShowingTransmissionNotice = true
            } else { onPrepare(editor.provider) }
        } else {
            Task { isSaved = await youtube.saveEditorSettings(accessToken: token) }
        }
    }
}

#Preview {
    NavigationStack { BroadcastSettingsView(authentication: AuthSession(), youtube: YouTubeIntegration()) }
}
