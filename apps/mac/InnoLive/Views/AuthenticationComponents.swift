import AppKit
import GoogleSignIn
import SwiftUI

struct AuthenticationBrand: View {
    var body: some View {
        Image("InnoLiveLightText")
            .resizable()
            .scaledToFit()
            .frame(width: 96, height: 25)
            .accessibilityLabel("InnoLive")
    }
}

struct AuthenticationDivider: View {
    var body: some View {
        HStack(spacing: 12) {
            Rectangle()
                .fill(.separator)
                .frame(height: 1)

            Text("또는")
                .font(.caption)
                .foregroundStyle(.tertiary)

            Rectangle()
                .fill(.separator)
                .frame(height: 1)
        }
    }
}

struct GoogleOAuthButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let logo = Self.googleLogo {
                    Image(nsImage: logo)
                        .resizable()
                        .interpolation(.high)
                        .scaledToFit()
                        .frame(width: 18, height: 18)
                }

                Text("Google 계정으로 로그인")
            }
        }
        .buttonStyle(AuthenticationProviderButtonStyle())
        .accessibilityLabel("Google 계정으로 로그인")
    }

    private static let googleLogo: NSImage? = {
        let frameworkBundle = Bundle(for: GIDSignIn.self)
        guard let bundlePath = frameworkBundle.path(
            forResource: "GoogleSignIn_GoogleSignIn",
            ofType: "bundle"
        ),
        let resourceBundle = Bundle(path: bundlePath),
        let logoURL = resourceBundle.url(forResource: "google", withExtension: "png") else {
            return nil
        }
        return NSImage(contentsOf: logoURL)
    }()
}

struct AuthenticationProviderButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.colorScheme) private var colorScheme

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(colorScheme == .dark ? .white : .black)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background(
                colorScheme == .dark ? Color.black : Color.white,
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(.separator, lineWidth: 1)
            }
            .opacity(isEnabled ? 1 : 0.45)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct AuthenticationGlassSurface: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: 24, style: .continuous)
        let surfaceColor = colorScheme == .dark
            ? Color.white.opacity(0.08)
            : Color.black.opacity(0.04)

        if #available(macOS 26.0, *) {
            content
                .background(surfaceColor, in: shape)
                .glassEffect(.regular, in: shape)
        } else {
            content
                .background(.regularMaterial, in: shape)
                .overlay {
                    shape
                        .strokeBorder(.separator, lineWidth: 1)
                }
        }
    }
}

struct AuthenticationPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 42)
            .background(
                Color.accentColor,
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )
            .opacity(isEnabled ? 1 : 0.45)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

extension View {
    func authenticationGlassSurface() -> some View {
        modifier(AuthenticationGlassSurface())
    }
}

struct AuthenticationErrorBanner: View {
    let message: String

    var body: some View {
        Label {
            Text(message)
                .font(.callout)
                .multilineTextAlignment(.leading)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
        }
        .foregroundStyle(.red)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("인증 오류: \(message)")
    }
}

struct AuthenticationStepHeader: View {
    let title: String
    let message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.title2.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)

            if let message {
                Text(message)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
