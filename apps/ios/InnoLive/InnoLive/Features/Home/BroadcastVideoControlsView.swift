import SwiftUI
import Combine

struct BroadcastVideoControlsView: View {
    @ObservedObject var uplink: WebRTCVideoUplink
    @ObservedObject var youtube: YouTubeIntegration
    let accessToken: () -> String?
    @Environment(CameraManager.self) private var cameraManager
    @Environment(\.dismiss) private var dismiss
    @StateObject private var previews = PresetPreviewModel()
    // 슬라이더를 움직이는 동안의 값이다. 손을 떼면 서버로 보내고, 응답 후에는 확정값을 보인다.
    @State private var mosaicDraft: MosaicDraft?
    @State private var isEditingMosaic = false
    // 처음에는 모자이크 영역만 보이게 열고, 색감은 위로 끌어 올려 본다.
    // 내비게이션 바·여백 높이를 더해 모자이크 영역 실측값으로 시트 높이를 정한다.
    private static let mosaicSheetChrome: CGFloat = 92
    @State private var mosaicSectionHeight: CGFloat = 246
    @State private var sheetDetent: PresentationDetent = .height(338)

    var body: some View {
        NavigationStack {
            ScrollView {
               VStack(alignment: .leading, spacing: 20) {
                    mosaicSection
                        .onGeometryChange(for: CGFloat.self) { $0.size.height.rounded() } action: { height in
                            let isCompact = sheetDetent != .large
                            mosaicSectionHeight = height
                            if isCompact { sheetDetent = mosaicDetent }
                        }

                    Divider()

                    VStack(alignment: .leading, spacing: 8) {
                        Text(String(localized: "프리셋"))
                            .font(.body.weight(.semibold))
                        HStack(spacing: 8) {
                            ForEach(BroadcastVideoLook.allCases) { look in
                                presetColumn(look)
                            }
                        }
                    }

                    control(
                        title: String(localized: "노출"),
                        value: String(format: "%+.1f EV", uplink.videoQualitySettings.exposureEV),
                        binding: exposureBinding,
                        range: -2...2,
                        step: 0.1,
                        leading: String(localized: "어둡게"),
                        trailing: String(localized: "밝게")
                    )

                    control(
                        title: String(localized: "색온도"),
                        value: String(format: "%+.2f", uplink.videoQualitySettings.warmth),
                        binding: warmthBinding,
                        range: -1...1,
                        step: 0.05,
                        leading: String(localized: "차갑게"),
                        trailing: String(localized: "따뜻하게")
                    )

                    control(
                        title: String(localized: "채도"),
                        value: "\(Int((uplink.videoQualitySettings.saturation * 100).rounded()))%",
                        binding: saturationBinding,
                        range: 0...2,
                        step: 0.05,
                        leading: "0%",
                        trailing: "200%"
                    )

                    Button {
                        uplink.setExposureEV(0)
                        uplink.setColor(warmth: 0, saturation: 1)
                    } label: {
                        Label(String(localized: "초기화"), systemImage: "arrow.counterclockwise")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
                .padding(.horizontal, 24)
                .padding(.top, 16)
                .padding(.bottom, 10)
            }
            .navigationTitle(String(localized: "영상 조절"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "닫기")) { dismiss() }
                }
            }
            .onAppear(perform: startPresetPreviews)
            .onDisappear(perform: stopPresetPreviews)
            .onChange(of: uplink.isCapturingMedia) { _, _ in
                startPresetPreviews()
            }
        }
        .presentationDetents([mosaicDetent, .large], selection: $sheetDetent)
        .presentationDragIndicator(.visible)
    }

    private var mosaicDetent: PresentationDetent {
        .height(Self.mosaicSheetChrome + mosaicSectionHeight)
    }

    private var mosaicSection: some View {
        let available = youtube.canChangeMosaicConfig
        let shown = mosaicDraft ?? MosaicDraft(youtube.mosaicConfig)
        return VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 4) {
                Text(String(localized: "모자이크"))
                    .font(.body.weight(.semibold))
                if let note = mosaicNote(available: available) {
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            control(
                title: String(localized: "블러 강도"),
                value: "\(shown.blurRadius)",
                binding: mosaicBinding(\.blurRadius),
                range: Double(BroadcastMosaicConfig.blurRadiusRange.lowerBound)...Double(BroadcastMosaicConfig.blurRadiusRange.upperBound),
                step: 1,
                leading: String(localized: "약하게"),
                trailing: String(localized: "강하게"),
                onEditingChanged: mosaicEditingChanged
            )
            .disabled(!available)

            control(
                title: String(localized: "가림 영역 너비"),
                value: "\(shown.maskMargin)px",
                binding: mosaicBinding(\.maskMargin),
                range: Double(BroadcastMosaicConfig.maskMarginRange.lowerBound)...Double(BroadcastMosaicConfig.maskMarginRange.upperBound),
                step: 1,
                leading: String(localized: "좁게"),
                trailing: String(localized: "넓게"),
                onEditingChanged: mosaicEditingChanged
            )
            .disabled(!available)

            if let message = youtube.mosaicConfigErrorMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            Button {
                mosaicDraft = nil
                submitMosaic(youtube.mosaicConfig.adjusted(blurRadius: nil, maskMargin: nil))
            } label: {
                HStack(spacing: 8) {
                    if youtube.isChangingMosaicConfig { ProgressView().controlSize(.small) }
                    Label(String(localized: "모자이크 기본값"), systemImage: "arrow.counterclockwise")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(!available)
        }
    }

    private func mosaicNote(available: Bool) -> String? {
        guard let session = youtube.session else {
            return String(localized: "방송을 준비하면 조절할 수 있습니다.")
        }
        if session.processingMode != .server {
            return String(localized: "서버 AI 처리에서만 조절할 수 있습니다.")
        }
        if available && !youtube.isAnonymizationEnabled {
            return String(localized: "비식별화가 켜져 있을 때 적용됩니다.")
        }
        return nil
    }

    private func mosaicBinding(_ keyPath: WritableKeyPath<MosaicDraft, Int>) -> Binding<Double> {
        Binding(
            get: { Double((mosaicDraft ?? MosaicDraft(youtube.mosaicConfig))[keyPath: keyPath]) },
            set: { value in
                var draft = mosaicDraft ?? MosaicDraft(youtube.mosaicConfig)
                draft[keyPath: keyPath] = Int(value.rounded())
                mosaicDraft = draft
                // VoiceOver 조절처럼 드래그 없이 바뀐 값은 바로 보낸다.
                if !isEditingMosaic { commitMosaicDraft() }
            }
        )
    }

    private func mosaicEditingChanged(_ editing: Bool) {
        isEditingMosaic = editing
        if !editing { commitMosaicDraft() }
    }

    private func commitMosaicDraft() {
        guard let draft = mosaicDraft else { return }
        submitMosaic(youtube.mosaicConfig.adjusted(blurRadius: draft.blurRadius, maskMargin: draft.maskMargin))
    }

    private func submitMosaic(_ config: BroadcastMosaicConfig) {
        guard config != youtube.mosaicConfig || youtube.isChangingMosaicConfig else {
            mosaicDraft = nil
            return
        }
        Task {
            await youtube.updateMosaicConfig(config, accessToken: accessToken())
            if !youtube.isChangingMosaicConfig && !isEditingMosaic { mosaicDraft = nil }
        }
    }

    private func presetColumn(_ look: BroadcastVideoLook) -> some View {
        let selected = look.matches(uplink.videoQualitySettings)
        return Button(action: { apply(look) }) {
            VStack(spacing: 8) {
                Text(look.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .foregroundStyle(selected ? Color.accentColor : Color.primary)
                presetPreview(previews.images[look], selected: selected)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(look.title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func presetPreview(_ image: CGImage?, selected: Bool) -> some View {
        let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
        return Group {
            if let image {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .scaledToFill()
            } else {
                Color.white.opacity(0.12)
            }
        }
        .frame(maxWidth: .infinity)
        .aspectRatio(3.0 / 4.0, contentMode: .fit)
        .clipShape(shape)
        .overlay {
            shape.strokeBorder(selected ? Color.white : Color.white.opacity(0.28), lineWidth: selected ? 3 : 1)
        }
    }

    private func apply(_ look: BroadcastVideoLook) {
        uplink.setExposureEV(look.exposureEV)
        uplink.setColor(warmth: look.warmth, saturation: look.saturation)
    }

    private func startPresetPreviews() {
        stopPresetPreviews()
        let pump = previews.pump
        let deliver: @Sendable (CVPixelBuffer, Int) -> Void = { buffer, rotation in
            pump.submit(pixelBuffer: buffer, rotation: rotation)
        }
        if uplink.isCapturingMedia {
            uplink.setUnprocessedPreviewHandler(deliver)
        } else {
            cameraManager.startPresetPreviewFrames(deliver)
        }
    }

    private func stopPresetPreviews() {
        uplink.setUnprocessedPreviewHandler(nil)
        cameraManager.stopPresetPreviewFrames()
    }

    private var exposureBinding: Binding<Double> {
        Binding(
            get: { Double(uplink.videoQualitySettings.exposureEV) },
            set: { value in
                let ev = Float(value)
                uplink.setExposureEV(ev)
            }
        )
    }

    private var warmthBinding: Binding<Double> {
        Binding(
            get: { Double(uplink.videoQualitySettings.warmth) },
            set: { uplink.setColor(warmth: Float($0), saturation: uplink.videoQualitySettings.saturation) }
        )
    }

    private var saturationBinding: Binding<Double> {
        Binding(
            get: { Double(uplink.videoQualitySettings.saturation) },
            set: { uplink.setColor(warmth: uplink.videoQualitySettings.warmth, saturation: Float($0)) }
        )
    }

    private func control(
        title: String,
        value: String,
        binding: Binding<Double>,
        range: ClosedRange<Double>,
        step: Double,
        leading: String,
        trailing: String,
        onEditingChanged: @escaping (Bool) -> Void = { _ in }
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                    .font(.body.weight(.semibold))
                Spacer()
                Text(value)
                    .font(.body.monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            Slider(value: binding, in: range, step: step, onEditingChanged: onEditingChanged)
                .accessibilityLabel(title)
                .accessibilityValue(value)

            HStack {
                Text(leading)
                Spacer()
                Text(trailing)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }
}

private struct MosaicDraft: Equatable {
    var blurRadius: Int
    var maskMargin: Int

    init(_ config: BroadcastMosaicConfig) {
        blurRadius = config.effectiveBlurRadius
        maskMargin = config.effectiveMaskMargin
    }
}

private enum BroadcastVideoLook: CaseIterable, Identifiable, Sendable {
    case vivid
    case bright
    case warm

    var id: Self { self }

    var title: String {
        switch self {
        case .vivid: String(localized: "선명하게")
        case .bright: String(localized: "화사하게")
        case .warm: String(localized: "따뜻하게")
        }
    }

    var exposureEV: Float {
        switch self {
        case .bright: 0.8
        case .vivid: 0
        case .warm: 0.2
        }
    }

    var warmth: Float {
        switch self {
        case .bright: 0.2
        case .vivid: -0.2
        case .warm: 0.6
        }
    }

    var saturation: Float {
        switch self {
        case .bright: 1.2
        case .vivid: 1.4
        case .warm: 1.1
        }
    }

    func matches(_ settings: BroadcastVideoQualitySettings) -> Bool {
        abs(settings.exposureEV - exposureEV) < 0.001
            && abs(settings.warmth - warmth) < 0.001
            && abs(settings.saturation - saturation) < 0.001
    }
}

@MainActor
private final class PresetPreviewModel: ObservableObject {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    @Published private(set) var images: [BroadcastVideoLook: CGImage] = [:]
    let pump = PresetPreviewPump()

    init() {
        pump.onUpdate = { [weak self] images in
            self?.images = images
        }
    }
}

nonisolated private final class PresetPreviewPump: @unchecked Sendable {
    var onUpdate: (@MainActor ([BroadcastVideoLook: CGImage]) -> Void)?
    private let renderer = VideoLookPreviewRenderer()
    private let queue = DispatchQueue(label: "com.innolive.preset-preview", qos: .utility)
    private let lock = NSLock()
    private var busy = false
    private var lastSubmitTime: TimeInterval = 0

    func submit(pixelBuffer: CVPixelBuffer, rotation: Int) {
        lock.lock()
        let now = ProcessInfo.processInfo.systemUptime
        if busy || now - lastSubmitTime < 1.0 / 8.0 {
            lock.unlock()
            return
        }
        busy = true
        lastSubmitTime = now
        lock.unlock()
        guard let copy = renderer.ownedCopy(of: pixelBuffer) else {
            finish()
            return
        }
        queue.async { [renderer] in
            let adjustments = BroadcastVideoLook.allCases.map { look in
                (warmth: look.warmth, saturation: look.saturation, exposureEV: look.exposureEV)
            }
            let images = renderer.makePreviewSet(
                pixelBuffer: copy,
                rotation: rotation,
                adjustments: adjustments
            )
            var rendered: [BroadcastVideoLook: CGImage] = [:]
            for (look, image) in zip(BroadcastVideoLook.allCases, images) {
                if let image {
                    rendered[look] = image
                }
            }
            let output = rendered
            let update = self.onUpdate
            Task { @MainActor in
                update?(output)
            }
            self.finish()
        }
    }

    private func finish() {
        lock.lock()
        busy = false
        lock.unlock()
    }
}
