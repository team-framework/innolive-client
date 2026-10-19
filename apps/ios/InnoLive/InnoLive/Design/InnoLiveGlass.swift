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
    let overVideo: Bool
    @Environment(\.innoLiveGlassForceFallback) private var forceFallback
    @Environment(\.innoLiveGlassForceOpaque) private var forceOpaque
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.colorSchemeContrast) private var contrast

    func body(content: Content) -> some View {
        if forceOpaque || reduceTransparency || contrast == .increased {
            content
                .background(overVideo ? Color.black : Color(uiColor: .secondarySystemBackground), in: shape)
                .overlay(shape.strokeBorder(.primary.opacity(0.25), lineWidth: 1))
        } else if #available(iOS 26, *), !forceFallback {
            content.glassEffect((overVideo ? Glass.regular.tint(.black.opacity(0.45)) : .regular).interactive(interactive), in: shape)
        } else {
            content
                .background {
                    shape.fill(.regularMaterial)
                        .environment(\.colorScheme, overVideo ? .dark : colorScheme)
                }
                .overlay(shape.strokeBorder(.primary.opacity(0.15), lineWidth: 0.5))
        }
    }
}

private struct InnoLiveGlassButtonModifier: ViewModifier {
    let prominent: Bool
    let overVideo: Bool
    @Environment(\.innoLiveGlassForceFallback) private var forceFallback
    @Environment(\.innoLiveGlassForceOpaque) private var forceOpaque
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.colorSchemeContrast) private var contrast

    @ViewBuilder
    func body(content: Content) -> some View {
        if forceOpaque || reduceTransparency || contrast == .increased {
            content.buttonStyle(.borderedProminent)
                .tint(overVideo ? Color(white: 0.12) : Color.accentColor)
        } else if #available(iOS 26, *), !forceFallback {
            if prominent { content.buttonStyle(.glassProminent) }
            else { content.buttonStyle(.glass(overVideo ? .regular.tint(.black.opacity(0.45)) : .regular)) }
        } else if prominent {
            content.buttonStyle(.borderedProminent)
        } else {
            content.buttonStyle(.bordered)
                .environment(\.colorScheme, overVideo ? .dark : colorScheme)
        }
    }
}

extension View {
    func innoLiveGlassSurface<S: InsettableShape>(in shape: S, interactive: Bool = false, overVideo: Bool = false) -> some View {
        modifier(InnoLiveGlassSurface(shape: shape, interactive: interactive, overVideo: overVideo))
    }

    func innoLiveGlassBackground(cornerRadius: CGFloat, overVideo: Bool = false) -> some View {
        innoLiveGlassSurface(in: RoundedRectangle(cornerRadius: cornerRadius), overVideo: overVideo)
    }

    func innoLiveGlassButtonStyle(prominent: Bool = false, overVideo: Bool = false) -> some View {
        modifier(InnoLiveGlassButtonModifier(prominent: prominent, overVideo: overVideo))
    }
}
