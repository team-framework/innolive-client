#if DEBUG
import AVFoundation
import Combine
import SwiftUI
import UniformTypeIdentifiers
@preconcurrency import LiveKitWebRTC

@MainActor
final class DebugMediaSourcePreviewModel: ObservableObject {
    @Published private(set) var image: UIImage?
    @Published private(set) var metrics = MediaSourceMetrics()
    @Published private(set) var status = "파일을 선택해 주세요."
    @Published private(set) var isPlaying = false

    private static let sslInitialized = LKRTCInitializeSSL()
    private let factory: LKRTCPeerConnectionFactory
    private let imageContext = CIContext()
    private var source: FileSource?
    private var consumer: WebRTCVideoFrameConsumer?
    private var playbackTask: Task<Void, Never>?
    private var generation: UInt = 0
    private var importedFileURL: URL?
    private(set) var url: URL?

    init(url: URL? = nil) {
        _ = Self.sslInitialized
        factory = LKRTCPeerConnectionFactory(
            encoderFactory: LKRTCDefaultVideoEncoderFactory(),
            decoderFactory: LKRTCDefaultVideoDecoderFactory()
        )
        self.url = url
        if url == nil {
            status = "SimulatorFixture.mp4 또는 파일을 선택해 주세요."
        }
    }

    func choose(url: URL) {
        guard let importedURL = copyImportedFile(from: url) else {
            status = "선택한 파일을 앱 저장소로 복사하지 못했습니다."
            return
        }
        stop()
        self.url = importedURL
        importedFileURL = importedURL
        MediaSourceDebugConfiguration.setImportedFileURL(importedURL)
        start()
    }

    func selectCamera() {
        stop()
        url = nil
        MediaSourceDebugConfiguration.selectCamera()
        image = nil
        status = "카메라 입력을 선택했습니다."
    }

    func start() {
        guard let url else {
            status = "파일을 선택해 주세요."
            return
        }
        stop()
        let playbackGeneration = generation
        let rtcSource = factory.videoSource()
        let consumer = WebRTCVideoFrameConsumer(
            target: rtcSource,
            frameObserver: { [weak self] frame in
                guard let pixelBuffer = frame.pixelBuffer,
                      let self else { return }
                let orientedImage = CIImage(cvPixelBuffer: pixelBuffer)
                    .oriented(forExifOrientation: frame.rotation.exifOrientation)
                guard let cgImage = self.imageContext.createCGImage(
                    orientedImage,
                    from: orientedImage.extent
                ) else { return }
                let image = UIImage(cgImage: cgImage)
                Task { @MainActor [weak self] in
                    guard let self, self.generation == playbackGeneration else { return }
                    self.image = image
                    self.metrics = self.source?.metrics ?? self.metrics
                }
            }
        )
        let source = FileSource(
            url: url,
            consumer: consumer,
            errorHandler: { [weak self] error in
                Task { @MainActor [weak self] in
                    guard let self, self.generation == playbackGeneration else { return }
                    self.status = (error as? LocalizedError)?.errorDescription
                        ?? "파일 영상을 읽지 못했습니다."
                    self.isPlaying = false
                }
            },
            completionHandler: { [weak self] in
                Task { @MainActor [weak self] in
                    guard let self, self.generation == playbackGeneration else { return }
                    self.status = "영상 재생이 끝났습니다."
                    self.isPlaying = false
                }
            }
        )
        self.consumer = consumer
        self.source = source
        status = "파일을 디코드하는 중…"
        isPlaying = true
        let task = Task { @MainActor [weak self, source] in
            do {
                try await source.start()
                guard let self, self.generation == playbackGeneration else {
                    await source.stop()
                    return
                }
                self.status = "재생 중"
            } catch {
                guard let self, self.generation == playbackGeneration else { return }
                self.status = (error as? LocalizedError)?.errorDescription
                    ?? "파일 영상을 시작하지 못했습니다."
                self.isPlaying = false
            }
        }
        playbackTask = task
    }

    func stop() {
        generation &+= 1
        playbackTask?.cancel()
        playbackTask = nil
        let wasPlaying = isPlaying
        guard let source else {
            isPlaying = false
            return
        }
        self.source = nil
        isPlaying = false
        if wasPlaying {
            status = "중지됨"
        }
        Task {
            await source.stop()
        }
    }

    func updateMetrics() {
        metrics = source?.metrics ?? metrics
    }

    private func copyImportedFile(from url: URL) -> URL? {
        let didStartAccessing = url.startAccessingSecurityScopedResource()
        defer {
            if didStartAccessing {
                url.stopAccessingSecurityScopedResource()
            }
        }

        do {
            let directory = FileManager.default.urls(
                for: .cachesDirectory,
                in: .userDomainMask
            )[0].appendingPathComponent("InnoLive/Media", isDirectory: true)
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true
            )
            let fileExtension = url.pathExtension.isEmpty ? "mp4" : url.pathExtension
            let destination = directory.appendingPathComponent(
                "imported-\(UUID().uuidString).\(fileExtension)"
            )
            try FileManager.default.copyItem(at: url, to: destination)
            if let importedFileURL {
                try? FileManager.default.removeItem(at: importedFileURL)
            }
            return destination
        } catch {
            return nil
        }
    }
}

struct DebugMediaSourceHarness: View {
    let onExit: () -> Void
    @StateObject private var model: DebugMediaSourcePreviewModel
    @State private var isShowingImporter = false

    init(onExit: @escaping () -> Void = {}) {
        self.onExit = onExit
        let initialURL: URL?
        if case let .file(url) = MediaSourceDebugConfiguration.selection() {
            initialURL = url
        } else {
            initialURL = nil
        }
        _model = StateObject(wrappedValue: DebugMediaSourcePreviewModel(url: initialURL))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Group {
                    if let image = model.image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                    } else {
                        ContentUnavailableView(
                            "파일 프리뷰 대기 중",
                            systemImage: "film",
                            description: Text(model.status)
                        )
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(.black)
                .clipShape(RoundedRectangle(cornerRadius: 20))

                Text(model.status)
                    .font(.headline)
                Text(metricsText)
                    .font(.footnote.monospaced())
                    .foregroundStyle(.secondary)

                HStack {
                    Button("파일 선택") {
                        isShowingImporter = true
                    }
                    .buttonStyle(.borderedProminent)
                    Button("재생") {
                        model.start()
                    }
                    .buttonStyle(.bordered)
                    .disabled(model.url == nil || model.isPlaying)
                    Button("중지") {
                        model.stop()
                    }
                    .buttonStyle(.bordered)
                }

                HStack {
                    Button("카메라") {
                        model.selectCamera()
                    }
                    .buttonStyle(.bordered)
                    Button("앱으로 돌아가기") {
                        model.stop()
                        onExit()
                    }
                    .buttonStyle(.bordered)
                }
            }
            .padding()
            .navigationTitle("DEBUG MediaSource")
        }
        .fileImporter(
            isPresented: $isShowingImporter,
            allowedContentTypes: [.movie, .mpeg4Movie]
        ) { result in
            guard case let .success(url) = result else { return }
            model.choose(url: url)
        }
        .task {
            if model.url != nil, !model.isPlaying {
                model.start()
            }
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                model.updateMetrics()
            }
        }
        .onDisappear {
            model.stop()
        }
    }

    private var metricsText: String {
        let metrics = model.metrics
        return String(
            format: "input %.1f fps · %dx%d · dropped %d",
            metrics.inputFPS,
            metrics.width,
            metrics.height,
            metrics.droppedFrames
        )
    }
}
#endif
