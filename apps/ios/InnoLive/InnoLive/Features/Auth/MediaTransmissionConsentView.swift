import SwiftUI

struct MediaTransmissionConsentView: View {
    let onAccept: (SignupConsent) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var consent = SignupConsent()
    @AccessibilityFocusState(for: .voiceOver) private var isEndFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    section(
                        String(localized: "전송하는 방송 정보"),
                        body: String(localized: "카메라와 마이크로 수집한 방송 영상·음성을 서버로 전송합니다. 비식별화 처리를 위해 AI 서비스가 이 영상·음성을 처리·중계합니다.")
                    )
                    section(
                        String(localized: "이용 목적"),
                        body: String(localized: "등록 얼굴과 다른 얼굴을 구분하고, 방송 영상에서 다른 얼굴을 비식별화하기 위해 이용합니다.")
                    )
                    section(
                        String(localized: "보유 및 처리"),
                        body: String(localized: "AI 서비스는 처리 중 얼굴 특징값을 메모리에 보관합니다. 보관한 얼굴 특징값은 등록 얼굴을 삭제하거나 계정 삭제가 완료되거나 AI 서비스가 종료될 때 정리합니다. 기준 사진 원본은 별도로 보관하지 않습니다.")
                    )
                    Text(String(localized: "동의하지 않으면 카메라와 마이크를 서버에 연결하지 않습니다. 개인정보 관련 문의와 동의 철회는 개인정보처리방침의 연락처로 요청할 수 있습니다."))
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
                    .buttonStyle(.glassProminent)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .disabled(!consent.hasReadToEnd)
                    .accessibilityIdentifier("mediaTransmissionConsent.continue")
                }
                .padding(20)
                .background(.regularMaterial)
            }
            .navigationTitle(String(localized: "방송 영상·음성 전송 동의"))
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
