import SwiftUI

struct AccountSettingsView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @State private var isShowingSignOutConfirmation = false

    var body: some View {
        ScrollView {
            GlassEffectContainer {
                Button {
                    isShowingSignOutConfirmation = true
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
        .confirmationDialog(
            "로그아웃하시겠습니까?",
            isPresented: $isShowingSignOutConfirmation,
            titleVisibility: .visible
        ) {
            Button("로그아웃", role: .destructive) {
                youtube.reset()
                authentication.signOut()
            }
            Button("취소", role: .cancel) { }
        } message: {
            Text("현재 계정에서 로그아웃합니다.")
        }
    }
}

#Preview {
    NavigationStack {
        AccountSettingsView(authentication: AuthSession(), youtube: YouTubeIntegration())
    }
}
