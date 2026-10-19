import SwiftUI

enum InnoLiveSettingsLayout {
    static let pageInset: CGFloat = 16
    static let cardInset: CGFloat = 12
    static let cornerRadius: CGFloat = 14
    static let minimumControlHeight: CGFloat = 44
}

private struct InnoLiveSettingsPageStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .font(.subheadline)
            .buttonBorderShape(.roundedRectangle(radius: InnoLiveSettingsLayout.cornerRadius))
            .innoLiveGlassButtonStyle()
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationBarTitleDisplayMode(.inline)
    }
}

extension View {
    func innoLiveSettingsPage() -> some View {
        modifier(InnoLiveSettingsPageStyle())
    }

    func innoLiveSettingsCard() -> some View {
        padding(InnoLiveSettingsLayout.cardInset)
            .frame(maxWidth: .infinity, alignment: .leading)
            .innoLiveGlassBackground(cornerRadius: InnoLiveSettingsLayout.cornerRadius)
    }
}
