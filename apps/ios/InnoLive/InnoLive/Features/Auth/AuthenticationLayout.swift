import SwiftUI

struct AuthenticationLayout<Content: View>: View {
    private let content: Content
    private let alignment: Alignment

    init(alignment: Alignment = .center, @ViewBuilder content: () -> Content) {
        self.alignment = alignment
        self.content = content()
    }

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                content
                    .frame(maxWidth: 420, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.horizontal, proxy.size.width >= 600 ? 40 : 24)
                    .padding(.vertical, 32)
                    .frame(minHeight: proxy.size.height, alignment: alignment)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
        }
    }
}
