import SwiftUI

struct YouTubeTransmissionNoticeView: View {
    let onConfirm: (SignupConsent) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var consent = SignupConsent()
    @AccessibilityFocusState(for: .voiceOver) private var isEndFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    section(
                        String(localized: "전송하는 정보"),
                        body: String(localized: "처리된 방송 영상·음성을 연결한 YouTube 채널로 전송합니다. YouTube 연결과 방송을 위해 채널 식별 정보, 암호화한 연결 토큰과 방송 연결 정보를 처리합니다.")
                    )
                    section(
                        String(localized: "이용 목적"),
                        body: String(localized: "연결한 YouTube 채널로 처리한 영상과 음성을 전송하고 방송 상태를 관리합니다.")
                    )
                    section(
                        String(localized: "YouTube에서의 처리"),
                        body: String(localized: "YouTube에 전송된 영상은 YouTube의 정책과 사용자 채널 설정에 따라 처리됩니다. 계정 삭제는 YouTube 채널의 과거 영상을 삭제하지 않습니다.")
                    )
                    Text(String(localized: "이 안내를 확인하지 않으면 방송 준비를 진행하지 않습니다. 개인정보 관련 문의는 개인정보처리방침의 연락처로 요청할 수 있습니다."))
                    if let url = InnoLiveLinks.privacyPolicyURL {
                        Link(String(localized: "개인정보처리방침 전문 보기"), destination: url)
                            .accessibilityFocused($isEndFocused)
                    }
                }
                .font(.callout)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
            }
            .onScrollGeometryChange(for: Bool.self) { geometry in
                let position = SignupConsent.scrollPosition(
                    contentHeight: geometry.contentSize.height,
                    visibleHeight: geometry.containerSize.height,
                    offset: geometry.contentOffset.y,
                    topInset: geometry.contentInsets.top,
                    bottomInset: geometry.contentInsets.bottom
                )
                return SignupConsent.hasReachedEnd(
                    contentHeight: position.contentHeight,
                    visibleHeight: position.visibleHeight,
                    offset: position.offset
                )
            } action: { _, reachedEnd in
                if reachedEnd { consent.reachedEnd() }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(alignment: .leading, spacing: 12) {
                    if !consent.hasReadToEnd {
                        Text(String(localized: "내용을 끝까지 스크롤하면 계속할 수 있습니다."))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    Button(String(localized: "확인하고 방송 준비")) {
                        consent.accept()
                        guard consent.isAccepted else { return }
                        onConfirm(consent)
                        dismiss()
                    }
                    .buttonStyle(.glassProminent)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .disabled(!consent.hasReadToEnd)
                    .accessibilityIdentifier("youtubeTransmissionNotice.continue")
                }
                .padding(20)
                .background(.regularMaterial)
            }
            .navigationTitle(String(localized: "YouTube 전송 안내"))
            .onChange(of: isEndFocused) { _, focused in
                if focused { consent.reachedEnd() }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "취소")) { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
    }

    private func section(_ title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.title2.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text(body).fixedSize(horizontal: false, vertical: true)
        }
    }
}
