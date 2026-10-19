import SwiftUI

private struct InnoLiveGlassFallbackKey: EnvironmentKey {
    static let defaultValue = false
}

private struct InnoLiveGlassOpaqueKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var innoLiveGlassForceOpaque: Bool {
        get { self[InnoLiveGlassOpaqueKey.self] }
        set { self[InnoLiveGlassOpaqueKey.self] = newValue }
    }

    /// Allows previews and rendering tests to exercise the older-OS appearance.
    var innoLiveGlassForceFallback: Bool {
        get { self[InnoLiveGlassFallbackKey.self] }
        set { self[InnoLiveGlassFallbackKey.self] = newValue }
    }
}

struct InnoLiveGlassContainer<Content: View>: View {
    @Environment(\.innoLiveGlassForceFallback) private var forceFallback
    private let content: Content

    init(@ViewBuilder content: () -> Content) { self.content = content() }

    var body: some View {
        if #available(iOS 26, *), !forceFallback {
            GlassEffectContainer { content }
        } else {
            content
        }
    }
}

private struct InnoLiveGlassSurface<S: InsettableShape>: ViewModifier {
    let shape: S
    let interactive: Bool
    @Environment(\.innoLiveGlassForceFallback) private var forceFallback
    @Environment(\.innoLiveGlassForceOpaque) private var forceOpaque
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    func body(content: Content) -> some View {
        if forceOpaque || reduceTransparency || contrast == .increased {
            content
                .background(Color(uiColor: .secondarySystemBackground), in: shape)
                .overlay(shape.strokeBorder(.primary.opacity(0.25), lineWidth: 1))
        } else if #available(iOS 26, *), !forceFallback {
            content.glassEffect(.regular.interactive(interactive), in: shape)
        } else {
            content
                .background(.regularMaterial, in: shape)
                .overlay(shape.strokeBorder(.primary.opacity(0.15), lineWidth: 0.5))
        }
    }
}

private struct InnoLiveGlassButtonModifier: ViewModifier {
    let prominent: Bool
    @Environment(\.innoLiveGlassForceFallback) private var forceFallback
    @Environment(\.innoLiveGlassForceOpaque) private var forceOpaque
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26, *), !forceFallback, !forceOpaque, !reduceTransparency, contrast != .increased {
            if prominent { content.buttonStyle(.glassProminent) }
            else { content.buttonStyle(.glass) }
        } else if prominent {
            content.buttonStyle(.borderedProminent)
        } else {
            content.buttonStyle(.bordered)
        }
    }
}

extension View {
    func innoLiveGlassSurface<S: InsettableShape>(in shape: S, interactive: Bool = false) -> some View {
        modifier(InnoLiveGlassSurface(shape: shape, interactive: interactive))
    }

    func innoLiveGlassBackground(cornerRadius: CGFloat) -> some View {
        innoLiveGlassSurface(in: RoundedRectangle(cornerRadius: cornerRadius))
    }

    func innoLiveGlassButtonStyle(prominent: Bool = false) -> some View {
        modifier(InnoLiveGlassButtonModifier(prominent: prominent))
    }
}
