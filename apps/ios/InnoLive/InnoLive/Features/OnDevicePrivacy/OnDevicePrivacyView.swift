#if DEBUG
import AVFoundation
import Combine
import SwiftUI

@MainActor
final class OnDevicePrivacyController: ObservableObject {
    @Published var image: CGImage?
    @Published var status = "카메라를 준비합니다."
    @Published var running = false
    @Published var front = true
    @Published var objects = 0
    @Published var milliseconds = 0.0
    @Published var fps = 0.0
    @Published var breakdown = ""
    private let camera = PrivacyCamera()
    private var generation = 0

    func start() async {
        guard !running else { return }
        generation += 1
        let current = generation
        running = true
        image = nil
        status = "모델과 카메라를 준비합니다."
        let granted = await AVCaptureDevice.requestAccess(for: .video)
        guard current == generation else { return }
        guard granted else {
            running = false
            status = "설정에서 카메라 접근을 허용해 주세요."
            return
        }
        camera.start(front: front) { [weak self] image, count, timings, fps in
            Task { @MainActor [weak self] in
                guard let self, self.generation == current, self.running else { return }
                self.image = image
                self.objects = count
                self.milliseconds = timings.total
                self.fps = fps
                self.breakdown = String(format: "전처리 %.0f · 추론 %.0f · 마스크 %.0f · 합성 %.0f ms",
                                        timings.prepare, timings.inference, timings.mask, timings.render)
                self.status = "얼굴·번호판 비식별화 중"
            }
        } onError: { [weak self] message in
            Task { @MainActor [weak self] in
                guard let self, self.generation == current else { return }
                self.image = nil
                self.running = false
                self.status = message
            }
        }
    }

    func stop() {
        generation += 1
        camera.stop()
        running = false
        image = nil
        status = "중지됨"
    }

    func switchCamera() async {
        stop()
        front.toggle()
        await start()
    }
}

struct OnDevicePrivacyView: View {
    @StateObject private var controller = OnDevicePrivacyController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(spacing: 16) {
            VStack(spacing: 4) {
                Text("온디바이스 비식별화").font(.title2.bold())
                Text("로컬 테스트 · 영상 저장 및 전송 없음")
                    .font(.caption).foregroundStyle(.secondary)
            }
            ZStack {
                RoundedRectangle(cornerRadius: 20).fill(.black)
                if let image = controller.image {
                    Image(decorative: image, scale: 1).resizable().scaledToFit()
                } else {
                    VStack(spacing: 12) {
                        if controller.running { ProgressView().tint(.white) }
                        Text(controller.status).multilineTextAlignment(.center).foregroundStyle(.white)
                    }.padding()
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 20))
            .accessibilityLabel("비식별화 처리 영상")
            Text(controller.status).font(.subheadline)
            HStack {
                metric("탐지", "\(controller.objects)개")
                Spacer()
                metric("처리 시간", String(format: "%.0f ms", controller.milliseconds))
                Spacer()
                metric("처리 FPS", String(format: "%.1f", controller.fps))
            }.monospacedDigit()
            Text(controller.breakdown).font(.caption2).monospacedDigit().foregroundStyle(.secondary)
            Text("탐지한 얼굴과 번호판을 블러 처리합니다. 탐지하지 못한 영역은 그대로 보일 수 있습니다.")
                .font(.caption).foregroundStyle(.secondary)
            HStack(spacing: 16) {
                Button(controller.running ? "중지" : "시작", systemImage: controller.running ? "pause.fill" : "play.fill") {
                    if controller.running { controller.stop() } else { Task { await controller.start() } }
                }.buttonStyle(.borderedProminent)
                Button("카메라 전환", systemImage: "arrow.triangle.2.circlepath.camera") {
                    Task { await controller.switchCamera() }
                }.buttonStyle(.bordered)
            }
        }
        .padding()
        .background(Color(.systemBackground))
        .task { await controller.start() }
        .onDisappear { controller.stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { controller.stop() }
        }
    }

    private func metric(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.headline)
        }
    }
}
#endif
