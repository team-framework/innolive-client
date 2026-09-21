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
    @Published var faces = PrivacyFaceSnapshot(people: [], message: "이름을 입력하고 한 명씩 등록하세요.",
                                              enrolling: false, allowed: 0, milliseconds: 0, ready: false)
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
        camera.start(front: front) { [weak self] image, count, timings, fps, faces in
            Task { @MainActor [weak self] in
                guard let self, self.generation == current, self.running else { return }
                self.faces = faces
                self.image = image
                self.objects = count
                self.milliseconds = timings.total
                self.fps = fps
                self.breakdown = String(format: "전처리 %.0f · 추론 %.0f · 마스크 %.0f · 합성 %.0f ms",
                                        timings.prepare, timings.inference, timings.mask, timings.render)
                self.status = "등록 \(faces.people.count)명 · 블러 해제 \(faces.allowed)명"
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

    func enroll(name: String) { camera.enroll(name: name) }
    func cancelEnrollment() { camera.cancelEnrollment() }
    func deleteFace(id: UUID) {
        camera.deleteFace(id: id) { [weak self] snapshot in
            Task { @MainActor [weak self] in self?.faces = snapshot }
        }
    }

    func switchCamera() async {
        stop()
        front.toggle()
        await start()
    }
}

struct OnDevicePrivacyView: View {
    @StateObject private var controller = OnDevicePrivacyController()
    @State private var faceName = ""
    @State private var showingFaces = false
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
            .overlay {
                if controller.faces.enrolling, let image = controller.image {
                    GeometryReader { geometry in
                        let scale = min(geometry.size.width / CGFloat(image.width), geometry.size.height / CGFloat(image.height))
                        let side = CGFloat(min(image.width, image.height)) * scale
                        RoundedRectangle(cornerRadius: 16).stroke(.blue, lineWidth: 3)
                            .frame(width: side, height: side)
                            .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
                    }.allowsHitTesting(false)
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
            Text("등록자와 일치한 얼굴의 블러를 해제합니다. 미탐지·오인식은 실제 장면에서 확인해 주세요.")
                .font(.caption).foregroundStyle(.secondary)
            HStack(spacing: 16) {
                Button(controller.running ? "중지" : "시작", systemImage: controller.running ? "pause.fill" : "play.fill") {
                    if controller.running { controller.stop() } else { Task { await controller.start() } }
                }.buttonStyle(.borderedProminent)
                Button("카메라 전환", systemImage: "arrow.triangle.2.circlepath.camera") {
                    Task { await controller.switchCamera() }
                }.buttonStyle(.bordered)
            }
            Button("얼굴 등록 관리 · \(controller.faces.people.count)명", systemImage: "person.crop.rectangle.badge.plus") {
                showingFaces = true
            }.buttonStyle(.bordered)
            Text(String(format: "얼굴 인식 %.0f ms · %@", controller.faces.milliseconds, controller.faces.message))
                .font(.caption2).foregroundStyle(.secondary).lineLimit(2)
        }
        .padding()
        .background(Color(.systemBackground))
        .sheet(isPresented: $showingFaces) { faceManagement }
        .task { await controller.start() }
        .onDisappear { controller.stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { controller.stop() }
        }
    }

    private var faceManagement: some View {
        NavigationStack {
            List {
                Section("얼굴 등록") {
                    TextField("이름", text: $faceName).textInputAutocapitalization(.never)
                    Button(controller.faces.enrolling ? "등록 중…" : "카메라로 등록") {
                        controller.enroll(name: faceName)
                        showingFaces = false
                    }.disabled(!controller.running || !controller.faces.ready || controller.faces.enrolling || faceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if controller.faces.enrolling {
                        Button("등록 취소") { controller.cancelEnrollment() }
                    }
                    Text("얼굴을 가운데에 맞춰 잠시 유지하세요. 준비가 끝나면 사진 한 장으로 등록하며 최대 20명을 저장합니다.")
                        .font(.caption).foregroundStyle(.secondary)
                    Text(controller.faces.message).font(.caption)
                }
                Section("등록된 얼굴") {
                    ForEach(controller.faces.people) { person in
                        HStack {
                            Text(person.name)
                            Spacer()
                            Button("삭제", role: .destructive) { controller.deleteFace(id: person.id) }
                                .buttonStyle(.borderless)
                        }
                    }
                    if controller.faces.people.isEmpty { Text("등록된 얼굴이 없습니다.").foregroundStyle(.secondary) }
                }
                Section {
                    Text("이름과 얼굴 특징값을 이 기기에 저장합니다. 사진은 저장하지 않습니다. 등록을 삭제하면 블러 예외도 해제합니다.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("얼굴 등록 관리")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("완료") { showingFaces = false } } }
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
