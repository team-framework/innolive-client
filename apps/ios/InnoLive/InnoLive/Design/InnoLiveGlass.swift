import SwiftUI

/// Keeps Liquid Glass on iOS 26 while preserving the same content on iOS 18.
struct InnoLiveGlassContainer<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        if #available(iOS 26, *) {
            GlassEffectContainer { content }
        } else {
            content
        }
    }
}

extension View {
    @ViewBuilder
    func innoLiveGlassBackground(cornerRadius: CGFloat) -> some View {
        if #available(iOS 26, *) {
            glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
        } else {
            background(.regularMaterial, in: RoundedRectangle(cornerRadius: cornerRadius))
        }
    }

    @ViewBuilder
    func innoLiveGlassButtonStyle(prominent: Bool = false) -> some View {
        if #available(iOS 26, *) {
            if prominent {
                buttonStyle(.glassProminent)
            } else {
                buttonStyle(.glass)
            }
        } else if prominent {
            buttonStyle(.borderedProminent)
        } else {
            buttonStyle(.bordered)
        }
    }
}
