import SwiftUI

struct ConsentDocumentSection {
    let title: String
    let body: String
}

struct ConsentDocumentSheet: View {
    let navigationTitle: String
    let sections: [ConsentDocumentSection]
    let closingNotice: String
    let scrollHint: String
    let acceptTitle: String
    let acceptIdentifier: String
    let onAccept: (SignupConsent) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var consent = SignupConsent()
    @AccessibilityFocusState(for: .voiceOver) private var isEndFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    ForEach(sections, id: \.title) { section in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(section.title)
                                .font(.title2.weight(.semibold))
                                .accessibilityAddTraits(.isHeader)
                            Text(section.body).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Text(closingNotice)
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
                        Text(scrollHint)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    Button(acceptTitle) {
                        consent.accept()
                        guard consent.isAccepted else { return }
                        onAccept(consent)
                        dismiss()
                    }
                    .innoLiveGlassButtonStyle(prominent: true)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .disabled(!consent.hasReadToEnd)
                    .accessibilityIdentifier(acceptIdentifier)
                }
                .padding(20)
                .background(.regularMaterial)
            }
            .navigationTitle(navigationTitle)
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
}
