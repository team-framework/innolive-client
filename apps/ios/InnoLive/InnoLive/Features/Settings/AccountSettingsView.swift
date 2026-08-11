import SwiftUI

struct AccountSettingsView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration

    var body: some View {
        ScrollView {
            GlassEffectContainer {
                Button {
                    youtube.reset()
                    authentication.signOut()
                } label: {
                    SettingsGlassRow {
                        HStack(spacing: 12) {
                            Image(systemName: "rectangle.portrait.and.arrow.right")
                                .frame(width: 28)
                            Text("로그아웃")
                                .font(.body.weight(.semibold))
                            Spacer()
                        }
                        .foregroundStyle(.red)
                    }
                }
                .buttonStyle(.plain)
            }
            .padding(24)
        }
        .navigationTitle("계정 설정")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    NavigationStack {
        AccountSettingsView(authentication: AuthSession(), youtube: YouTubeIntegration())
    }
}
