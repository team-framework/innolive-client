import SwiftUI

struct AISettingsView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @State private var mode = AIProcessingMode.selected
    @State private var isChanging = false

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                ForEach(AIProcessingMode.allCases) { option in
                    Button {
                        guard mode != option || youtube.isAIProcessingUnconfirmed else { return }
                        isChanging = true
                        Task {
                            _ = await youtube.changeAIProcessingMode(option, accessToken: authentication.currentAccessToken())
                            mode = AIProcessingMode.selected
                            isChanging = false
                        }
                    } label: {
                        SettingsGlassRow {
                            HStack {
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(option.title).font(.body.weight(.semibold))
                                    Text(option == .server ? String(localized: "서버에서 영상을 비식별화합니다.") : String(localized: "이 기기에서 비식별화한 영상을 송출합니다."))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if option == mode { Image(systemName: "checkmark.circle.fill").foregroundStyle(.blue) }
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(isChanging || youtube.isYouTubeBroadcastActive || youtube.isChangingStreamState || youtube.isPreparingSession || youtube.isConnectingVideo)
                }
                if isChanging { ProgressView() }
                Text(youtube.isYouTubeBroadcastActive ? String(localized: "방송을 종료한 뒤 AI 처리 방식을 변경할 수 있습니다.") : String(localized: "영상 연결을 유지하며 AI 처리 위치를 변경합니다. 얼굴 등록은 서버와 이 기기에 각각 저장됩니다."))
                    .font(.footnote).foregroundStyle(.secondary)
                NavigationLink {
                    FaceManagementView(authentication: authentication, youtube: youtube, mode: mode)
                        .id(mode)
                } label: {
                    SettingsGlassRow {
                        HStack {
                            Image(systemName: "faceid")
                            Text(mode == .server ? String(localized: "서버 얼굴 관리") : String(localized: "이 기기 얼굴 관리"))
                            Spacer()
                            Image(systemName: "chevron.right")
                        }
                    }
                }
                .buttonStyle(.plain).disabled(isChanging)
                if let error = youtube.errorMessage { Text(error).font(.footnote).foregroundStyle(.red) }
            }.padding(24)
        }
        .navigationTitle(String(localized: "AI & Faces Setting"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
