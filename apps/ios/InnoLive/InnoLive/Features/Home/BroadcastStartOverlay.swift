import SwiftUI

struct BroadcastStartOverlay: View {
    @ObservedObject var lifecycle: BroadcastLifecycle
    let orientation: BroadcastInterfaceOrientation?
    let onCancel: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        if case .countdown(let value) = lifecycle.phase {
            backdrop {
                VStack(spacing: 28) {
                    ZStack {
                        Circle().stroke(.white.opacity(0.22), lineWidth: 3)
                        Circle().trim(from: 0, to: CGFloat(value) / 3)
                            .stroke(.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                        Text(value.formatted())
                            .font(.system(size: 76, weight: .medium, design: .rounded))
                            .monospacedDigit()
                            .contentTransition(reduceMotion ? .identity : .numericText(countsDown: true))
                    }
                    .frame(width: 160, height: 160)
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: value)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(String(localized: "방송 시작까지"))
                    .accessibilityValue(value.formatted())
                    .accessibilityIdentifier("broadcast-countdown")

                    Label(orientation?.isLandscape == true ? String(localized: "가로 방향으로 방송합니다") : String(localized: "세로 방향으로 방송합니다"),
                          systemImage: orientation?.isLandscape == true ? "iphone.gen3.landscape" : "iphone.gen3")
                        .font(.subheadline.weight(.medium))
                        .fixedSize(horizontal: false, vertical: true)
                    Button(String(localized: "취소"), action: onCancel)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 44)
                        .innoLiveGlassButtonStyle()
                        .buttonBorderShape(.capsule)
                        .accessibilityIdentifier("broadcast-countdown-cancel")
                }
            }
        } else if lifecycle.phase == .starting {
            backdrop {
                VStack(spacing: 20) {
                    ProgressView().controlSize(.large).tint(.white)
                    Text(String(localized: "방송 시작 중"))
                        .font(.title3.weight(.semibold))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(String(localized: "플랫폼의 시작 응답을 기다리고 있어요."))
                        .font(.subheadline).foregroundStyle(.white.opacity(contrast == .increased ? 1 : 0.85))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityIdentifier("broadcast-starting")
            }
        }
    }

    private func backdrop<Content: View>(@ViewBuilder content: @escaping () -> Content) -> some View {
        ZStack {
            Color.black.opacity(reduceTransparency ? 1 : (contrast == .increased ? 0.82 : 0.62))
                .ignoresSafeArea()
            GeometryReader { geometry in
                ScrollView {
                    content()
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 480)
                        .padding(24)
                        .frame(maxWidth: .infinity, minHeight: geometry.size.height)
                }
                .scrollIndicators(.hidden)
                .accessibilityIdentifier("broadcast-start-scroll")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .accessibilityAddTraits(.isModal)
    }
}
