import SwiftUI

struct SignupConsentView: View {
    let onAccept: (SignupConsent) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var consent = SignupConsent()
    @AccessibilityFocusState(for: .voiceOver) private var isEndFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    section(String(localized: "수집하는 계정 정보"), body: String(localized: "회원가입·로그인·계정 관리를 위해 이름, 이메일 주소, 회원 식별자, 외부 로그인 식별자, 이메일 로그인 비밀번호 해시, 인증 토큰 정보(갱신 토큰 해시 포함), IP 주소와 User-Agent 정보를 처리합니다."))
                    section(String(localized: "계정 정보 이용 목적"), body: String(localized: "회원가입, 로그인, 세션 유지, 계정 관리와 인증 보안을 위해 이용합니다."))
                    section(String(localized: "계정 정보 보유 및 삭제"), body: String(localized: "계정·로그인·방송 연결 정보는 서비스 운영과 계정 삭제 처리에 필요한 범위에서 보관합니다. 계정 삭제가 완료되면 계정·로그인·방송 연결 정보와 얼굴 등록 데이터를 정리합니다."))
                    section(String(localized: "외부 로그인 안내"), body: String(localized: "Google과 Apple은 각 제공자가 인증을 처리합니다. InnoLive는 로그인에 필요한 외부 식별자와 인증 결과를 처리하며, 각 제공자가 처리하는 정보에는 해당 제공자의 정책이 적용됩니다."))
                    Text(String(localized: "동의하지 않으면 이 가입·소셜 로그인 절차를 진행하지 않습니다. 개인정보 관련 문의와 동의 철회는 개인정보처리방침의 연락처로 요청할 수 있습니다."))
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
                        Text(String(localized: "내용을 끝까지 스크롤하면 동의할 수 있습니다."))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    Button(String(localized: "동의하고 계속")) {
                        consent.accept()
                        guard consent.isAccepted else { return }
                        onAccept(consent)
                        dismiss()
                    }
                    .innoLiveGlassButtonStyle(prominent: true)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .disabled(!consent.hasReadToEnd)
                    .accessibilityIdentifier("signupConsent.continue")
                }
                .padding(20)
                .background(.regularMaterial)
            }
            .navigationTitle(String(localized: "계정 정보 수집·이용 동의"))
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

struct ConsentCheckboxStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .font(.title2)
                configuration.label
                    .foregroundStyle(.primary)
                Spacer(minLength: 0)
            }
            .frame(minHeight: 44)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityRepresentation {
            Toggle(isOn: configuration.$isOn) { configuration.label }
        }
    }
}
