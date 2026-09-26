import SwiftUI
import Combine

struct BroadcastVideoControlsView: View {
    @ObservedObject var uplink: WebRTCVideoUplink
    @Environment(CameraManager.self) private var cameraManager
    @Environment(\.dismiss) private var dismiss
    @StateObject private var previews = PresetPreviewModel()

    var body: some View {
        NavigationStack {
            ScrollView {
               VStack(alignment: .leading, spacing: 20) {
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
                        cameraManager.setExposureEV(0)
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
            .onChange(of: uplink.videoQualitySettings.exposureEV) { _, value in
                previews.updateExposure(value)
            }
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
        cameraManager.setExposureEV(look.exposureEV)
        uplink.setColor(warmth: look.warmth, saturation: look.saturation)
    }

    private func startPresetPreviews() {
        stopPresetPreviews()
        previews.updateExposure(uplink.videoQualitySettings.exposureEV)
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
                cameraManager.setExposureEV(ev)
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
        trailing: String
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

            Slider(value: binding, in: range, step: step)
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

    func updateExposure(_ exposure: Float) {
        pump.setExposure(exposure)
    }
}

nonisolated private final class PresetPreviewPump: @unchecked Sendable {
    var onUpdate: (@MainActor ([BroadcastVideoLook: CGImage]) -> Void)?
    private let renderer = VideoLookPreviewRenderer()
    private let queue = DispatchQueue(label: "com.innolive.preset-preview", qos: .utility)
    private let lock = NSLock()
    private var busy = false
    private var exposure: Float = 0
    private var base: CGImage?
    private var lastSubmitTime: TimeInterval = 0

    func setExposure(_ exposure: Float) {
        lock.lock()
        self.exposure = exposure
        let base = base
        let canRender = base != nil && !busy
        if canRender { busy = true }
        lock.unlock()
        guard canRender, let base else { return }
        render(base: base, exposure: exposure)
    }

    func submit(pixelBuffer: CVPixelBuffer, rotation: Int) {
        lock.lock()
        let now = ProcessInfo.processInfo.systemUptime
        if busy || now - lastSubmitTime < 1.0 / 8.0 {
            lock.unlock()
            return
        }
        busy = true
        lastSubmitTime = now
        let exposure = exposure
        lock.unlock()
        guard let copy = renderer.ownedCopy(of: pixelBuffer) else {
            finish()
            return
        }
        queue.async { [renderer] in
            let adjustments = BroadcastVideoLook.allCases.map { look in
                (warmth: look.warmth, saturation: look.saturation, relativeExposureEV: look.exposureEV - exposure)
            }
            guard let set = renderer.makePreviewSet(
                pixelBuffer: copy,
                rotation: rotation,
                adjustments: adjustments
            ) else {
                self.finish()
                return
            }
            self.lock.lock()
            self.base = set.base
            self.lock.unlock()
            var rendered: [BroadcastVideoLook: CGImage] = [:]
            for (look, image) in zip(BroadcastVideoLook.allCases, set.previews) {
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

    private func render(base: CGImage, exposure: Float, alreadyBusy: Bool = false) {
        let looks = BroadcastVideoLook.allCases.map { look in
            (look, look.warmth, look.saturation, look.exposureEV - exposure)
        }
        let update = onUpdate
        let work = { [renderer] in
            var rendered: [BroadcastVideoLook: CGImage] = [:]
            for (look, warmth, saturation, relativeExposure) in looks {
                if let image = renderer.makePreview(
                    from: base,
                    warmth: warmth,
                    saturation: saturation,
                    relativeExposureEV: relativeExposure
                ) {
                    rendered[look] = image
                }
            }
            let output = rendered
            Task { @MainActor in
                update?(output)
            }
            self.finish()
        }
        if alreadyBusy {
            work()
        } else {
            queue.async(execute: work)
        }
    }

    private func finish() {
        lock.lock()
        busy = false
        lock.unlock()
    }
}
