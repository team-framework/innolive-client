import SwiftUI

enum BroadcastTutorialAnchor: Hashable {
    case primaryButton
    case connectAccount
    case startPreparation
    case preparationProgress
    case liveStatus
}

struct BroadcastTutorialAnchorKey: PreferenceKey {
    static let defaultValue: [BroadcastTutorialAnchor: Anchor<CGRect>] = [:]

    static func reduce(
        value: inout [BroadcastTutorialAnchor: Anchor<CGRect>],
        nextValue: () -> [BroadcastTutorialAnchor: Anchor<CGRect>]
    ) {
        value.merge(nextValue()) { _, next in next }
    }
}

extension View {
    /// 안내가 가리킬 화면 요소를 표시한다. 안내가 없는 화면에서는 아무 영향이 없다.
    func broadcastTutorialAnchor(_ anchor: BroadcastTutorialAnchor) -> some View {
        anchorPreference(key: BroadcastTutorialAnchorKey.self, value: .bounds) { [anchor: $0] }
    }

    @ViewBuilder
    func broadcastTutorialHost(_ tutorial: BroadcastTutorialCoordinator?, host: BroadcastTutorialHost) -> some View {
        if let tutorial {
            modifier(BroadcastTutorialHostModifier(tutorial: tutorial, host: host))
        } else {
            self
        }
    }
}

extension BroadcastTutorialStep {
    var title: String {
        switch self {
        case .openPreparation: return String(localized: "방송 준비부터 시작해요", table: "BroadcastGuide")
        case .connectAccount: return String(localized: "방송할 계정을 연결해요", table: "BroadcastGuide")
        case .startPreparation: return String(localized: "제목을 확인하고 준비해요", table: "BroadcastGuide")
        case .waitForPreparation: return String(localized: "방송을 준비하고 있어요", table: "BroadcastGuide")
        case .retryPreparation: return String(localized: "준비가 잘 되지 않았어요", table: "BroadcastGuide")
        case .goLive: return String(localized: "방송할 준비가 끝났어요", table: "BroadcastGuide")
        }
    }

    var message: String {
        switch self {
        case .openPreparation:
            return String(localized: "이 버튼을 누르면 방송할 플랫폼과 제목을 정할 수 있어요.", table: "BroadcastGuide")
        case .connectAccount:
            return String(localized: "YouTube나 치지직 계정을 연결해 주세요. 연결하고 돌아오면 다음 단계로 넘어가요.", table: "BroadcastGuide")
        case .startPreparation:
            return String(localized: "플랫폼과 방송 제목을 확인한 뒤 아래 방송 준비 버튼을 눌러 주세요. 준비만으로는 시청자에게 공개되지 않아요.", table: "BroadcastGuide")
        case .waitForPreparation:
            return String(localized: "서버와 연결하는 동안 잠시만 기다려 주세요. 준비가 끝나면 다음 단계를 알려 드릴게요.", table: "BroadcastGuide")
        case .retryPreparation:
            return String(localized: "안내 문구를 확인한 뒤 다시 시도해 주세요.", table: "BroadcastGuide")
        case .goLive:
            return String(localized: "방송 시작을 누르면 시청자에게 방송이 공개돼요. 준비되면 눌러 주세요.", table: "BroadcastGuide")
        }
    }

    func anchor(in host: BroadcastTutorialHost) -> BroadcastTutorialAnchor? {
        switch (self, host) {
        case (.openPreparation, _), (.goLive, _), (.retryPreparation, .home): return .primaryButton
        case (.connectAccount, _): return .connectAccount
        case (.startPreparation, _): return .startPreparation
        case (.waitForPreparation, .settingsSheet), (.retryPreparation, .settingsSheet): return .preparationProgress
        case (.waitForPreparation, .home): return nil
        }
    }
}

struct BroadcastTutorialCallout: View {
    let progress: BroadcastTutorialProgress?
    let title: String
    let message: String
    var primaryTitle: String?
    var onPrimary: (() -> Void)?
    var onSkip: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let progress {
                Text(verbatim: "\(progress.index)/\(progress.total)")
                    .font(.caption.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                    .accessibilityLabel(String(localized: "전체 \(progress.total)단계 중 \(progress.index)단계", table: "BroadcastGuide"))
            }
            Text(title)
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            if onSkip != nil || onPrimary != nil {
                HStack(spacing: 12) {
                    if let onSkip {
                        Button(String(localized: "건너뛰기", table: "BroadcastGuide"), action: onSkip)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .buttonStyle(.plain)
                            .accessibilityHint(String(localized: "안내는 설정에서 다시 볼 수 있어요.", table: "BroadcastGuide"))
                    }
                    Spacer(minLength: 0)
                    if let primaryTitle, let onPrimary {
                        Button(action: onPrimary) {
                            Text(primaryTitle)
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 4)
                        }
                        .innoLiveGlassButtonStyle(prominent: true)
                        .buttonBorderShape(.capsule)
                    }
                }
                .padding(.top, 6)
            }
        }
        .padding(16)
        .frame(maxWidth: 360, alignment: .leading)
        // 유리 효과만 쓰면 시트의 입력 칸이나 카메라 화면이 비쳐 글이 겹쳐 보여 바탕을 거의 불투명하게 깐다.
        .background(Color(uiColor: .systemBackground).opacity(0.94), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .innoLiveGlassBackground(cornerRadius: 20)
        .shadow(color: .black.opacity(0.18), radius: 14, y: 4)
        .accessibilityElement(children: .contain)
    }
}

/// 강조할 요소 주변만 밝게 남기는 반투명 막
private struct BroadcastTutorialDimShape: Shape {
    let cutout: CGRect?
    let cornerRadius: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path(rect)
        if let cutout {
            path.addRoundedRect(in: cutout, cornerSize: CGSize(width: cornerRadius, height: cornerRadius))
        }
        return path
    }
}

struct BroadcastTutorialOverlay: View {
    let targetRect: CGRect?
    let dimsBackground: Bool
    let blocksOutsideTarget: Bool
    /// 어두운 막이나 카메라 화면 위에서는 흰색, 밝은 시트 위에서는 강조색이 잘 보인다.
    let highlightColor: Color
    let safeAreaInsets: EdgeInsets
    let callout: BroadcastTutorialCallout

    private static let highlightPadding: CGFloat = 6
    private static let calloutSpacing: CGFloat = 12

    var body: some View {
        GeometryReader { proxy in
            let bounds = CGRect(origin: .zero, size: proxy.size)
            let visibleTarget = targetRect.flatMap { $0.intersects(bounds) ? $0 : nil }
            let highlight = visibleTarget?.insetBy(dx: -Self.highlightPadding, dy: -Self.highlightPadding)
            let radius = highlight.map { min($0.height / 2, 22) } ?? 0

            ZStack(alignment: .topLeading) {
                if dimsBackground {
                    BroadcastTutorialDimShape(cutout: highlight, cornerRadius: radius)
                        .fill(Color.black.opacity(0.45), style: FillStyle(eoFill: true))
                        // 강조된 버튼 자리는 터치가 그대로 아래 버튼에 전달된다.
                        .contentShape(BroadcastTutorialDimShape(cutout: highlight, cornerRadius: radius), eoFill: true)
                        .allowsHitTesting(blocksOutsideTarget)
                        .accessibilityHidden(true)
                }

                if let highlight {
                    RoundedRectangle(cornerRadius: radius)
                        .stroke(highlightColor.opacity(0.9), lineWidth: 2)
                        .frame(width: highlight.width, height: highlight.height)
                        .offset(x: highlight.minX, y: highlight.minY)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }

                calloutLayer(highlight: highlight, size: proxy.size)
            }
        }
    }

    @ViewBuilder
    private func calloutLayer(highlight: CGRect?, size: CGSize) -> some View {
        let horizontalPadding = max(16, max(safeAreaInsets.leading, safeAreaInsets.trailing) + 8)
        if let highlight, highlight.midY > size.height / 2 {
            callout
                .padding(.horizontal, horizontalPadding)
                .padding(.bottom, size.height - highlight.minY + Self.calloutSpacing)
                .frame(width: size.width, height: size.height, alignment: .bottom)
        } else if let highlight {
            callout
                .padding(.horizontal, horizontalPadding)
                .padding(.top, highlight.maxY + Self.calloutSpacing)
                .frame(width: size.width, height: size.height, alignment: .top)
        } else {
            callout
                .padding(.horizontal, horizontalPadding)
                .padding(.bottom, safeAreaInsets.bottom + 24)
                .frame(width: size.width, height: size.height, alignment: .bottom)
        }
    }
}

private struct BroadcastTutorialHostModifier: ViewModifier {
    @ObservedObject var tutorial: BroadcastTutorialCoordinator
    let host: BroadcastTutorialHost
    @Environment(\.accessibilityVoiceOverEnabled) private var isVoiceOverEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var guideStep: BroadcastTutorialStep? {
        guard let stage = tutorial.stage, stage.host == host else { return nil }
        return stage.step
    }

    private var showsLiveStatusTip: Bool {
        host == .home && guideStep == nil && tutorial.isShowingLiveStatusTip
    }

    private var announcement: String? {
        if let guideStep { return "\(guideStep.title). \(guideStep.message)" }
        if showsLiveStatusTip { return "\(BroadcastLiveStatusTip.title). \(BroadcastLiveStatusTip.message)" }
        return nil
    }

    func body(content: Content) -> some View {
        content
            .overlayPreferenceValue(BroadcastTutorialAnchorKey.self) { anchors in
                // 어둡게 하는 막이 화면 끝까지 덮도록 안전 영역을 무시하고, 말풍선 위치에만 안전 영역 값을 쓴다.
                GeometryReader { proxy in
                    overlay(anchors: anchors, proxy: proxy, safeAreaInsets: proxy.safeAreaInsets)
                }
                .ignoresSafeArea()
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: guideStep)
                .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: showsLiveStatusTip)
            }
            .onChange(of: announcement) { _, message in
                guard let message else { return }
                AccessibilityNotification.Announcement(message).post()
            }
    }

    @ViewBuilder
    private func overlay(
        anchors: [BroadcastTutorialAnchor: Anchor<CGRect>],
        proxy: GeometryProxy,
        safeAreaInsets: EdgeInsets
    ) -> some View {
        if let guideStep {
            let anchor = guideStep.anchor(in: host).flatMap { anchors[$0] }
            BroadcastTutorialOverlay(
                targetRect: anchor.map { proxy[$0] },
                // 홈에서는 화면을 어둡게 해 버튼에 집중시키고, 시트에서는 입력을 막지 않도록 강조 테두리만 둔다.
                dimsBackground: host == .home,
                blocksOutsideTarget: host == .home && !isVoiceOverEnabled,
                highlightColor: host == .home ? .white : .accentColor,
                safeAreaInsets: safeAreaInsets,
                callout: BroadcastTutorialCallout(
                    progress: tutorial.progress,
                    title: guideStep.title,
                    message: guideStep.message,
                    primaryTitle: guideStep == .goLive ? String(localized: "알겠어요", table: "BroadcastGuide") : nil,
                    onPrimary: guideStep == .goLive ? { tutorial.finish() } : nil,
                    onSkip: guideStep == .goLive ? nil : { tutorial.skip() }
                )
            )
            .transition(.opacity)
        } else if showsLiveStatusTip {
            BroadcastTutorialOverlay(
                targetRect: anchors[.liveStatus].map { proxy[$0] },
                // 방송 중에는 종료·일시 중지를 바로 누를 수 있어야 하므로 화면을 가리지 않는다.
                dimsBackground: false,
                blocksOutsideTarget: false,
                highlightColor: .white,
                safeAreaInsets: safeAreaInsets,
                callout: BroadcastTutorialCallout(
                    progress: nil,
                    title: BroadcastLiveStatusTip.title,
                    message: BroadcastLiveStatusTip.message,
                    primaryTitle: String(localized: "알겠어요", table: "BroadcastGuide"),
                    onPrimary: { tutorial.dismissLiveStatusTip() }
                )
            )
            .transition(.opacity)
        }
    }
}

enum BroadcastLiveStatusTip {
    static var title: String { String(localized: "방송 상태는 여기서 확인해요", table: "BroadcastGuide") }
    static var message: String {
        String(localized: "플랫폼별 송출 상태와 업로드 화질을 보여 드려요. 문제가 생기면 여기에 알려 드릴게요.", table: "BroadcastGuide")
    }
}
