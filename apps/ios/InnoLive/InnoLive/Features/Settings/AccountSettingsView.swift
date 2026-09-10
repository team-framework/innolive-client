import SwiftUI

struct AccountSettingsView: View {
    @ObservedObject var authentication: AuthSession
    @ObservedObject var youtube: YouTubeIntegration
    @State private var isShowingDeleteConfirmation = false

    private var isYouTubeBusy: Bool {
        youtube.isConnecting
            || youtube.isPreparingSession
            || youtube.isConnectingVideo
            || youtube.isChangingStreamState
            || youtube.isRecoveringVideoFailure
            || youtube.isTogglingAnonymization
    }

    private var isDeletionBlocked: Bool {
        authentication.isDeletingAccount || isYouTubeBusy
    }

    var body: some View {
        ScrollView {
            GlassEffectContainer {
                VStack(spacing: 12) {
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
                    .disabled(authentication.isDeletingAccount)

                    Button(role: .destructive) {
                        isShowingDeleteConfirmation = true
                    } label: {
                        SettingsGlassRow {
                            HStack(spacing: 12) {
                                Image(systemName: "trash")
                                    .frame(width: 28)
                                Text("계정 삭제")
                                    .font(.body.weight(.semibold))
                                Spacer()
                            }
                            .foregroundStyle(.red)
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(isDeletionBlocked)

                    if authentication.isDeletingAccount {
                        SettingsGlassRow {
                            HStack(spacing: 12) {
                                ProgressView()
                                    .controlSize(.small)
                                Text("계정 삭제 중…")
                                    .font(.body.weight(.semibold))
                                Spacer()
                            }
                        }
                    }

                    if let errorMessage = authentication.errorMessage,
                       !authentication.isDeletingAccount {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 4)
                    }
                }
            }
            .padding(24)
        }
        .navigationTitle("계정 설정")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            "계정을 삭제할까요?",
            isPresented: $isShowingDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("계정 삭제", role: .destructive) {
                Task { @MainActor in
                    guard !isDeletionBlocked else { return }
                    await authentication.deleteAccount {
                        youtube.resetForAccountDeletion()
                    }
                }
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("계정 삭제가 완료되면 로그아웃되며 이 기기의 YouTube 연결과 방송 설정이 초기화됩니다.")
        }
    }
}

#Preview {
    NavigationStack {
        AccountSettingsView(authentication: AuthSession(), youtube: YouTubeIntegration())
    }
}
