//
//  CameraManager.swift
//  InnoLive
//
//  Created by chaeyn on 5/22/26.
//

import AppKit
import AVFoundation
import Combine
import CoreGraphics
import CoreImage
import ScreenCaptureKit

struct CaptureDeviceOption: Identifiable, Hashable {
    let id: String
    let name: String
    let isDefault: Bool
}

struct ScreenCaptureDisplayOption: Identifiable, Hashable {
    let id: UInt32
    let name: String
    let frame: CGRect
    let pixelWidth: Int
    let pixelHeight: Int
    let isMain: Bool

    var detailText: String {
        "\(pixelWidth) x \(pixelHeight)"
    }
}

final class CameraManager: NSObject, ObservableObject {
    let session = AVCaptureSession()

    @Published private(set) var isRunning = false
    @Published private(set) var isRecording = false
    @Published private(set) var delayedFrame: CGImage?
    @Published private(set) var microphoneLevel = 0.0
    @Published private(set) var microphoneMeter = AudioMeterSample.zero
    @Published private(set) var cameraDevices: [CaptureDeviceOption] = []
    @Published private(set) var selectedCameraID: String?
    @Published private(set) var isSwitchingCamera = false
    @Published private(set) var microphoneDevices: [CaptureDeviceOption] = []
    @Published private(set) var selectedMicrophoneID: String?
    @Published private(set) var screenCaptureDisplays: [ScreenCaptureDisplayOption] = []
    @Published private(set) var screenCaptureFrames: [StudioSource.ID: CGImage] = [:]
    @Published private(set) var screenCaptureStatusText = "화면 캡처 대기"
    @Published private(set) var lastRecordingURL: URL?
    @Published private(set) var statusText = "카메라 준비 중"
    @Published private(set) var recordingStatusText = "녹화 대기"
    @Published private(set) var recordingSettings = RecordingSettings()
    @Published var isMicrophoneEnabled = true

    private let sessionQueue = DispatchQueue(label: "com.framework.InnoLive.camera-session", qos: .userInitiated)
    private let videoOutputQueue = DispatchQueue(label: "com.framework.InnoLive.video-output", qos: .userInitiated)
    private let audioOutputQueue = DispatchQueue(label: "com.framework.InnoLive.audio-output", qos: .userInitiated)
    private let screenCaptureQueue = DispatchQueue(label: "com.framework.InnoLive.screen-capture", qos: .userInitiated)
    private let latestLiveFrameLock = NSLock()
    private let delayedFrameOutput = DelayedFrameOutput(delay: 3)
    private let audioLevelOutput = AudioLevelOutput()
    private let sceneRecorder = SceneRecorder()

    private var videoInput: AVCaptureDeviceInput?
    private var audioInput: AVCaptureDeviceInput?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var audioOutput: AVCaptureAudioDataOutput?
    private var screenCaptureTimer: DispatchSourceTimer?
    private var activeScreenCaptureSources: [StudioSource.ID: ScreenCaptureSettings] = [:]
    private var isCapturingScreenFrames = false
    private var isConfigured = false
    private var activeRecordingURL: URL?
    private var latestLiveFrame: CGImage?

    override init() {
        super.init()
        refreshCameraDevices()
        refreshMicrophoneDevices()
        refreshScreenCaptureDisplays()

        delayedFrameOutput.onFrameReady = { [weak self] frame in
            self?.sceneRecorder.updateDelayedFrame(frame)
            DispatchQueue.main.async {
                self?.delayedFrame = frame
            }
        }

        delayedFrameOutput.onLiveFrame = { [weak self] frame, presentationTime in
            self?.storeLatestLiveFrame(frame)
            self?.sceneRecorder.appendVideo(liveFrame: frame, presentationTime: presentationTime)
        }

        audioLevelOutput.onMeter = { [weak self] meter in
            DispatchQueue.main.async {
                guard let self, self.isMicrophoneEnabled else {
                    self?.microphoneLevel = 0
                    self?.microphoneMeter = .zero
                    return
                }

                self.microphoneLevel = meter.level
                self.microphoneMeter = meter
            }
        }

        audioLevelOutput.onSampleBuffer = { [weak self] sampleBuffer in
            guard let self, self.isMicrophoneEnabled else {
                return
            }

            self.sceneRecorder.appendAudio(sampleBuffer)
        }
    }

    deinit {
        screenCaptureTimer?.cancel()
    }

    func start() {
        requestCaptureAccess { [weak self] videoGranted, audioGranted in
            guard let self else {
                return
            }

            guard videoGranted else {
                self.statusText = "카메라 권한이 필요합니다."
                self.recordingStatusText = "카메라 권한이 없어 녹화할 수 없습니다."
                self.isRunning = false
                return
            }

            self.applyAudioPermission(audioGranted)
            self.startAuthorized()
        }
    }

    private func startAuthorized() {
        sessionQueue.async {
            self.configureIfNeeded()

            guard self.isConfigured else {
                DispatchQueue.main.async {
                    self.statusText = "카메라 또는 마이크를 사용할 수 없습니다."
                    self.isRunning = false
                }
                return
            }

            guard !self.session.isRunning else {
                DispatchQueue.main.async {
                    self.statusText = "카메라 실행 중"
                    self.isRunning = true
                }
                return
            }

            self.session.startRunning()

            DispatchQueue.main.async {
                self.statusText = "카메라 실행 중"
                self.isRunning = true
            }
        }
    }

    func stop() {
        sessionQueue.async {
            if self.isRecording {
                self.finishRecordingOnSessionQueue()
            }

            guard self.session.isRunning else {
                DispatchQueue.main.async {
                    self.statusText = "카메라 중지됨"
                    self.isRunning = false
                    self.microphoneLevel = 0
                    self.microphoneMeter = .zero
                }
                return
            }

            self.session.stopRunning()

            DispatchQueue.main.async {
                self.statusText = "카메라 중지됨"
                self.isRunning = false
                self.microphoneLevel = 0
                self.microphoneMeter = .zero
            }
        }
    }

    func toggle() {
        if isRunning {
            stop()
        } else {
            start()
        }
    }

    func setMicrophoneEnabled(_ isEnabled: Bool) {
        DispatchQueue.main.async {
            self.isMicrophoneEnabled = isEnabled
            if !isEnabled {
                self.microphoneLevel = 0
                self.microphoneMeter = .zero
            }
        }

        sessionQueue.async {
            self.setAudioConnectionsEnabled(isEnabled)
        }
    }

    func refreshCameraDevices() {
        let defaultID = AVCaptureDevice.default(for: .video)?.uniqueID
        let devices = availableVideoDevices()
            .map { device in
                CaptureDeviceOption(
                    id: device.uniqueID,
                    name: device.localizedName,
                    isDefault: device.uniqueID == defaultID
                )
            }
            .sorted { lhs, rhs in
                if lhs.isDefault != rhs.isDefault {
                    return lhs.isDefault
                }

                return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
            }

        DispatchQueue.main.async {
            self.cameraDevices = devices
            if self.selectedCameraID == nil
                || !devices.contains(where: { $0.id == self.selectedCameraID }) {
                self.selectedCameraID = defaultID ?? devices.first?.id
            }
        }
    }

    func selectCamera(_ deviceID: String) {
        guard !isSwitchingCamera,
              let requestedDevice = cameraDevices.first(where: { $0.id == deviceID }) else {
            return
        }

        isSwitchingCamera = true
        statusText = "\(requestedDevice.name) 전환 중..."

        sessionQueue.async {
            self.configureIfNeeded()

            guard self.isConfigured else {
                DispatchQueue.main.async {
                    self.isSwitchingCamera = false
                    self.statusText = "카메라 세션을 구성할 수 없습니다."
                }
                return
            }

            if self.videoInput?.device.uniqueID == deviceID {
                DispatchQueue.main.async {
                    self.isSwitchingCamera = false
                    self.selectedCameraID = deviceID
                    self.statusText = "\(requestedDevice.name)이 이미 사용 중입니다."
                }
                return
            }

            let wasRunning = self.session.isRunning
            var replacementResult: VideoInputReplacementResult?
            let transactionResult = CameraSwitchTransaction.execute(
                wasRunning: wasRunning,
                stop: {
                    self.session.stopRunning()
                },
                resetBufferedFrames: {
                    self.videoOutputQueue.sync {
                        self.delayedFrameOutput.reset()
                    }
                },
                replaceInput: {
                    self.session.beginConfiguration()
                    replacementResult = self.replaceVideoInput(deviceID: deviceID)
                    self.session.commitConfiguration()
                },
                start: {
                    self.session.startRunning()
                },
                activeCameraID: {
                    self.videoInput?.device.uniqueID
                },
                isRunning: {
                    self.session.isRunning
                }
            )

            DispatchQueue.main.async {
                self.isSwitchingCamera = false
                self.isRunning = transactionResult.isRunning
                self.delayedFrame = nil

                switch replacementResult {
                case let .success(selectedDevice):
                    guard transactionResult.activeCameraID == selectedDevice.uniqueID else {
                        self.statusText = "카메라 입력 적용을 확인할 수 없습니다."
                        return
                    }

                    self.selectedCameraID = selectedDevice.uniqueID
                    guard !wasRunning || transactionResult.isRunning else {
                        self.statusText = "카메라 입력은 적용했지만 세션을 다시 시작할 수 없습니다."
                        return
                    }

                    self.statusText = "\(selectedDevice.localizedName)을 카메라로 적용했습니다."
                case let .failure(message):
                    self.statusText = message
                case nil:
                    self.statusText = "카메라 입력 교체 결과를 확인할 수 없습니다."
                }
            }
        }
    }

    func refreshMicrophoneDevices() {
        let defaultID = AVCaptureDevice.default(for: .audio)?.uniqueID
        let devices = availableAudioDevices()
            .map { device in
                CaptureDeviceOption(
                    id: device.uniqueID,
                    name: device.localizedName,
                    isDefault: device.uniqueID == defaultID
                )
            }
            .sorted { lhs, rhs in
                if lhs.isDefault != rhs.isDefault {
                    return lhs.isDefault
                }

                return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
            }

        DispatchQueue.main.async {
            self.microphoneDevices = devices
            if self.selectedMicrophoneID == nil {
                self.selectedMicrophoneID = defaultID ?? devices.first?.id
            }
        }
    }

    func selectMicrophone(_ deviceID: String) {
        DispatchQueue.main.async {
            self.selectedMicrophoneID = deviceID
            let name = self.microphoneDevices.first { $0.id == deviceID }?.name ?? "선택한 마이크"
            self.recordingStatusText = "\(name)을 녹화 마이크로 선택했습니다."
        }

        sessionQueue.async {
            guard self.isConfigured else {
                return
            }

            self.session.beginConfiguration()
            self.replaceAudioInput(deviceID: deviceID)
            self.session.commitConfiguration()
            self.setAudioConnectionsEnabled(self.isMicrophoneEnabled)
        }
    }

    func updateRecordingSettings(_ settings: RecordingSettings) {
        DispatchQueue.main.async {
            self.recordingSettings = settings
            self.recordingStatusText = "\(settings.resolution.title) · \(settings.videoBitrateKbps) kbps"
        }

        sessionQueue.async {
            guard self.isConfigured else {
                return
            }

            self.session.beginConfiguration()
            self.session.sessionPreset = self.sessionPreset(for: settings.resolution)
            self.session.commitConfiguration()
        }
    }

    func startRecording(configuration: SceneRecordingConfiguration? = nil) {
        requestCaptureAccess { [weak self] videoGranted, audioGranted in
            guard let self else {
                return
            }

            guard videoGranted else {
                self.recordingStatusText = "카메라 권한이 없어 녹화할 수 없습니다."
                self.statusText = "카메라 권한이 필요합니다."
                self.isRecording = false
                return
            }

            self.applyAudioPermission(audioGranted)
            let recordingConfiguration = configuration ?? self.defaultRecordingConfiguration()
            self.updateRecordingSettings(recordingConfiguration.settings)
            self.startRecordingAuthorized(configuration: recordingConfiguration)
        }
    }

    private func startRecordingAuthorized(configuration: SceneRecordingConfiguration) {
        sessionQueue.async {
            self.configureIfNeeded()

            if !self.session.isRunning {
                self.session.startRunning()
                DispatchQueue.main.async {
                    self.isRunning = true
                    self.statusText = "카메라 실행 중"
                }
            }

            guard !self.isRecording else {
                return
            }

            self.setAudioConnectionsEnabled(self.isMicrophoneEnabled)

            do {
                let url = try self.makeRecordingURL()
                var recordingConfiguration = configuration
                recordingConfiguration.includeMicrophoneAudio = recordingConfiguration.includeMicrophoneAudio && self.isMicrophoneEnabled
                try self.sceneRecorder.start(outputURL: url, configuration: recordingConfiguration)
                self.activeRecordingURL = url

                DispatchQueue.main.async {
                    self.isRecording = true
                    self.recordingStatusText = "장면 녹화 중"
                }
            } catch {
                DispatchQueue.main.async {
                    self.recordingStatusText = "녹화 파일 생성 실패: \(error.localizedDescription)"
                }
            }
        }
    }

    func stopRecording() {
        sessionQueue.async {
            self.finishRecordingOnSessionQueue()
        }
    }

    private func finishRecordingOnSessionQueue() {
        guard isRecording else {
            DispatchQueue.main.async {
                self.isRecording = false
                self.recordingStatusText = "녹화 대기"
            }
            return
        }

        let outputURL = activeRecordingURL
        DispatchQueue.main.async {
            self.recordingStatusText = "녹화 저장 중"
        }

        sceneRecorder.stop { [weak self] error in
            DispatchQueue.main.async {
                guard let self else {
                    return
                }

                self.isRecording = false
                self.activeRecordingURL = nil

                if let error {
                    self.recordingStatusText = "녹화 실패: \(error.localizedDescription)"
                } else if let outputURL {
                    self.lastRecordingURL = outputURL
                    self.recordingStatusText = "녹화 저장됨: \(outputURL.lastPathComponent)"
                } else {
                    self.recordingStatusText = "녹화 대기"
                }
            }
        }
    }

    func toggleRecording(configuration: SceneRecordingConfiguration? = nil) {
        if isRecording {
            stopRecording()
        } else {
            startRecording(configuration: configuration)
        }
    }

    func revealLastRecording() {
        guard let lastRecordingURL else {
            return
        }

        NSWorkspace.shared.activateFileViewerSelecting([lastRecordingURL])
    }

    var defaultScreenCaptureDisplayID: UInt32? {
        screenCaptureDisplays.first(where: \.isMain)?.id ?? screenCaptureDisplays.first?.id
    }

    var selectedCameraName: String? {
        cameraDevices.first { $0.id == selectedCameraID }?.name
    }

    func screenFrame(for source: StudioSource) -> CGImage? {
        screenCaptureFrames[source.id]
    }

    func currentLiveFrame() -> CGImage? {
        latestLiveFrameLock.lock()
        defer {
            latestLiveFrameLock.unlock()
        }

        return latestLiveFrame
    }

    func refreshScreenCaptureDisplays() {
        let displays = availableScreenCaptureDisplays()
        DispatchQueue.main.async {
            self.screenCaptureDisplays = displays
            if displays.isEmpty {
                self.screenCaptureStatusText = "사용 가능한 모니터가 없습니다."
            }
        }
    }

    func configureScreenCaptureSources(_ sources: [StudioSource]) {
        let requestedSources = sources
            .filter { $0.kind == .screen && $0.isVisible }
            .reduce(into: [StudioSource.ID: ScreenCaptureSettings]()) { result, source in
                result[source.id] = source.screenCapture
            }

        screenCaptureQueue.async {
            self.activeScreenCaptureSources = requestedSources

            guard !requestedSources.isEmpty else {
                self.stopScreenCaptureTimer()
                return
            }

            guard self.ensureScreenCaptureAccess() else {
                DispatchQueue.main.async {
                    self.screenCaptureFrames = [:]
                    self.screenCaptureStatusText = "화면 기록 권한이 필요합니다."
                }
                self.sceneRecorder.updateScreenFrames([:])
                return
            }

            self.startScreenCaptureTimerIfNeeded()
        }
    }

    private func configureIfNeeded() {
        guard !isConfigured else {
            return
        }

        session.beginConfiguration()
        session.sessionPreset = sessionPreset(for: recordingSettings.resolution)

        defer {
            session.commitConfiguration()
        }

        configureVideoInput()
        configureAudioInput()
        configureDelayedVideoOutput()
        configureAudioLevelOutput()

        isConfigured = session.inputs.contains { input in
            input.ports.contains { $0.mediaType == .video }
        }
    }

    private func defaultRecordingConfiguration() -> SceneRecordingConfiguration {
        SceneRecordingConfiguration(
            settings: recordingSettings,
            sources: [
                StudioSource(name: "내 화면", kind: .camera, layout: SourceLayout(zIndex: 0))
            ],
            includeMicrophoneAudio: isMicrophoneEnabled
        )
    }

    private func requestCaptureAccess(completion: @escaping (Bool, Bool) -> Void) {
        requestAccess(for: .video) { [weak self] videoGranted in
            guard let self else {
                return
            }

            self.requestAccess(for: .audio) { audioGranted in
                completion(videoGranted, audioGranted)
            }
        }
    }

    private func requestAccess(for mediaType: AVMediaType, completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: mediaType) {
        case .authorized:
            completion(true)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: mediaType) { granted in
                DispatchQueue.main.async {
                    completion(granted)
                }
            }
        case .denied, .restricted:
            completion(false)
        @unknown default:
            completion(false)
        }
    }

    private func applyAudioPermission(_ audioGranted: Bool) {
        guard !audioGranted else {
            return
        }

        isMicrophoneEnabled = false
        microphoneLevel = 0
        microphoneMeter = .zero
        recordingStatusText = "마이크 권한이 없어 영상만 녹화합니다."
    }

    private func configureVideoInput() {
        _ = replaceVideoInput(deviceID: selectedCameraID)
    }

    private enum VideoInputReplacementResult {
        case success(AVCaptureDevice)
        case failure(String)
    }

    @discardableResult
    private func replaceVideoInput(deviceID: String?) -> VideoInputReplacementResult {
        let device: AVCaptureDevice?
        if let deviceID {
            device = videoDevice(matching: deviceID)
        } else {
            device = AVCaptureDevice.default(for: .video)
        }

        guard let device else {
            return .failure("선택한 카메라가 연결되어 있지 않습니다.")
        }

        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: device)
        } catch {
            return .failure("\(device.localizedName) 입력을 열 수 없습니다: \(error.localizedDescription)")
        }

        let previousInput = videoInput
        if let previousInput {
            session.removeInput(previousInput)
            videoInput = nil
        }

        guard session.canAddInput(input) else {
            if let previousInput, session.canAddInput(previousInput) {
                session.addInput(previousInput)
                videoInput = previousInput
            } else {
                isConfigured = false
            }
            return .failure("\(device.localizedName)을 현재 카메라 세션에 추가할 수 없습니다.")
        }

        session.addInput(input)
        videoInput = input
        return .success(device)
    }

    private func videoDevice(matching deviceID: String?) -> AVCaptureDevice? {
        guard let deviceID else {
            return nil
        }

        return availableVideoDevices().first { $0.uniqueID == deviceID }
    }

    private func availableVideoDevices() -> [AVCaptureDevice] {
        AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .external, .continuityCamera, .deskViewCamera],
            mediaType: .video,
            position: .unspecified
        ).devices
    }

    private func configureAudioInput() {
        replaceAudioInput(deviceID: selectedMicrophoneID)
    }

    private func replaceAudioInput(deviceID: String?) {
        if let audioInput {
            session.removeInput(audioInput)
            self.audioInput = nil
        }

        let device = audioDevice(matching: deviceID) ?? AVCaptureDevice.default(for: .audio)

        guard let device,
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            DispatchQueue.main.async {
                self.recordingStatusText = "마이크 입력을 사용할 수 없습니다."
            }
            return
        }

        session.addInput(input)
        audioInput = input

        DispatchQueue.main.async {
            self.selectedMicrophoneID = device.uniqueID
        }
    }

    private func audioDevice(matching deviceID: String?) -> AVCaptureDevice? {
        guard let deviceID else {
            return nil
        }

        return availableAudioDevices().first { $0.uniqueID == deviceID }
    }

    private func availableAudioDevices() -> [AVCaptureDevice] {
        AVCaptureDevice.DiscoverySession(
            deviceTypes: [.microphone],
            mediaType: .audio,
            position: .unspecified
        ).devices
    }

    private func sessionPreset(for resolution: RecordingResolution) -> AVCaptureSession.Preset {
        switch resolution {
        case .hd720:
            .hd1280x720
        case .fullHD1080:
            .hd1920x1080
        case .qhd1440:
            .hd4K3840x2160
        }
    }

    private func configureDelayedVideoOutput() {
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]

        guard session.canAddOutput(output) else {
            return
        }

        session.addOutput(output)
        output.setSampleBufferDelegate(delayedFrameOutput, queue: videoOutputQueue)
        videoOutput = output
    }

    private func configureAudioLevelOutput() {
        let output = AVCaptureAudioDataOutput()
        output.audioSettings = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsNonInterleaved: false
        ]

        guard session.canAddOutput(output) else {
            return
        }

        session.addOutput(output)
        output.setSampleBufferDelegate(audioLevelOutput, queue: audioOutputQueue)
        audioOutput = output
    }

    private func setAudioConnectionsEnabled(_ isEnabled: Bool) {
        audioOutput?.connection(with: .audio)?.isEnabled = isEnabled
    }

    private func storeLatestLiveFrame(_ frame: CGImage) {
        latestLiveFrameLock.lock()
        latestLiveFrame = frame
        latestLiveFrameLock.unlock()
    }

    private func makeRecordingURL() throws -> URL {
        let supportURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let directoryURL = supportURL
            .appendingPathComponent("InnoLive", isDirectory: true)
            .appendingPathComponent("Recordings", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)

        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        let fileName = "InnoLive-\(formatter.string(from: Date())).mov"
        return directoryURL.appendingPathComponent(fileName)
    }

    private func availableScreenCaptureDisplays() -> [ScreenCaptureDisplayOption] {
        var displayCount: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &displayCount) == .success,
              displayCount > 0 else {
            return []
        }

        var displayIDs = [CGDirectDisplayID](repeating: 0, count: Int(displayCount))
        guard CGGetActiveDisplayList(displayCount, &displayIDs, &displayCount) == .success else {
            return []
        }

        let mainDisplayID = CGMainDisplayID()
        return displayIDs
            .prefix(Int(displayCount))
            .map { displayID in
                (
                    id: displayID,
                    frame: CGDisplayBounds(displayID),
                    pixelWidth: CGDisplayPixelsWide(displayID),
                    pixelHeight: CGDisplayPixelsHigh(displayID),
                    isMain: displayID == mainDisplayID
                )
            }
            .sorted { lhs, rhs in
                if lhs.isMain != rhs.isMain {
                    return lhs.isMain
                }

                if lhs.frame.minX == rhs.frame.minX {
                    return lhs.frame.minY < rhs.frame.minY
                }

                return lhs.frame.minX < rhs.frame.minX
            }
            .enumerated()
            .map { index, display in
                ScreenCaptureDisplayOption(
                    id: display.id,
                    name: display.isMain ? "주 모니터" : "모니터 \(index + 1)",
                    frame: display.frame,
                    pixelWidth: display.pixelWidth,
                    pixelHeight: display.pixelHeight,
                    isMain: display.isMain
                )
            }
    }

    private func ensureScreenCaptureAccess() -> Bool {
        if CGPreflightScreenCaptureAccess() {
            return true
        }

        return CGRequestScreenCaptureAccess()
    }

    private func startScreenCaptureTimerIfNeeded() {
        guard screenCaptureTimer == nil else {
            return
        }

        let timer = DispatchSource.makeTimerSource(queue: screenCaptureQueue)
        timer.schedule(deadline: .now(), repeating: 1.0 / 15.0, leeway: .milliseconds(8))
        timer.setEventHandler { [weak self] in
            self?.captureScreenFrames()
        }
        screenCaptureTimer = timer
        timer.resume()
    }

    private func stopScreenCaptureTimer() {
        screenCaptureTimer?.cancel()
        screenCaptureTimer = nil
        sceneRecorder.updateScreenFrames([:])

        DispatchQueue.main.async {
            self.screenCaptureFrames = [:]
            self.screenCaptureStatusText = "화면 캡처 대기"
        }
    }

    private func captureScreenFrames() {
        guard !activeScreenCaptureSources.isEmpty else {
            stopScreenCaptureTimer()
            return
        }

        guard !isCapturingScreenFrames else {
            return
        }

        isCapturingScreenFrames = true

        let displayIDs = availableScreenCaptureDisplays().map(\.id)
        let fallbackDisplayID = displayIDs.first ?? CGMainDisplayID()
        let sources = activeScreenCaptureSources
        let group = DispatchGroup()
        let framesLock = NSLock()
        var frames: [StudioSource.ID: CGImage] = [:]

        for (sourceID, settings) in sources {
            let displayID = resolvedDisplayID(
                settings.displayID,
                availableDisplayIDs: displayIDs,
                fallbackDisplayID: fallbackDisplayID
            )
            let captureRect = CGDisplayBounds(displayID)

            group.enter()
            SCScreenshotManager.captureImage(in: captureRect) { [weak self] image, _ in
                defer {
                    group.leave()
                }

                guard let self, let image else {
                    return
                }

                let downscaledImage = self.downscaledScreenImage(image, resolution: settings.resolution)
                framesLock.lock()
                frames[sourceID] = downscaledImage
                framesLock.unlock()
            }
        }

        group.notify(queue: screenCaptureQueue) {
            self.isCapturingScreenFrames = false
            self.sceneRecorder.updateScreenFrames(frames)

            DispatchQueue.main.async {
                self.screenCaptureFrames = frames
                self.screenCaptureStatusText = frames.isEmpty ? "화면 캡처 프레임 없음" : "화면 캡처 중"
            }
        }
    }

    private func resolvedDisplayID(
        _ displayID: UInt32?,
        availableDisplayIDs: [UInt32],
        fallbackDisplayID: UInt32
    ) -> CGDirectDisplayID {
        guard let displayID,
              availableDisplayIDs.contains(displayID) else {
            return fallbackDisplayID
        }

        return displayID
    }

    private func downscaledScreenImage(_ image: CGImage, resolution: ScreenCaptureResolution) -> CGImage {
        guard let maximumLongEdge = resolution.maximumLongEdge else {
            return image
        }

        let originalWidth = image.width
        let originalHeight = image.height
        let originalLongEdge = max(originalWidth, originalHeight)
        guard originalLongEdge > maximumLongEdge else {
            return image
        }

        let scale = Double(maximumLongEdge) / Double(originalLongEdge)
        let targetWidth = max(Int((Double(originalWidth) * scale).rounded()), 1)
        let targetHeight = max(Int((Double(originalHeight) * scale).rounded()), 1)
        let colorSpace = image.colorSpace ?? CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue

        guard let context = CGContext(
            data: nil,
            width: targetWidth,
            height: targetHeight,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        ) else {
            return image
        }

        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: targetWidth, height: targetHeight))
        return context.makeImage() ?? image
    }
}

private final class DelayedFrameOutput: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onFrameReady: ((CGImage) -> Void)?
    var onLiveFrame: ((CGImage, CMTime) -> Void)?

    private let delay: TimeInterval
    private let ciContext = CIContext()
    private var frames: [QueuedFrame] = []
    private var lastStoredFrameDate = Date.distantPast
    private let frameInterval: TimeInterval = 1.0 / 12.0

    init(delay: TimeInterval) {
        self.delay = delay
        super.init()
    }

    func reset() {
        frames.removeAll()
        lastStoredFrameDate = .distantPast
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        let now = Date()

        guard now.timeIntervalSince(lastStoredFrameDate) >= frameInterval,
              let cgImage = makeImage(from: sampleBuffer) else {
            return
        }

        lastStoredFrameDate = now
        onLiveFrame?(cgImage, CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
        frames.append(QueuedFrame(capturedAt: now, image: cgImage))
        publishReadyFrame(at: now)
    }

    private func makeImage(from sampleBuffer: CMSampleBuffer) -> CGImage? {
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return nil
        }

        let image = CIImage(cvImageBuffer: imageBuffer)
        return ciContext.createCGImage(image, from: image.extent)
    }

    private func publishReadyFrame(at now: Date) {
        guard let firstReadyIndex = frames.lastIndex(where: { now.timeIntervalSince($0.capturedAt) >= delay }) else {
            pruneFrames(olderThan: now.addingTimeInterval(-(delay + 2)))
            return
        }

        let frame = frames[firstReadyIndex]
        frames.removeSubrange(0...firstReadyIndex)
        onFrameReady?(frame.image)
    }

    private func pruneFrames(olderThan cutoff: Date) {
        frames.removeAll { $0.capturedAt < cutoff }
    }

    private struct QueuedFrame {
        let capturedAt: Date
        let image: CGImage
    }
}

private final class AudioLevelOutput: NSObject, AVCaptureAudioDataOutputSampleBufferDelegate {
    var onMeter: ((AudioMeterSample) -> Void)?
    var onSampleBuffer: ((CMSampleBuffer) -> Void)?

    private var lastPublishedAt = Date.distantPast
    private let publishInterval: TimeInterval = 0.01

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        onSampleBuffer?(sampleBuffer)

        let now = Date()
        guard now.timeIntervalSince(lastPublishedAt) >= publishInterval else {
            return
        }

        lastPublishedAt = now

        guard let meter = meter(from: sampleBuffer) else {
            return
        }

        onMeter?(meter)
    }

    private func meter(from sampleBuffer: CMSampleBuffer) -> AudioMeterSample? {
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer),
              let streamDescriptionPointer = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription) else {
            return nil
        }

        let streamDescription = streamDescriptionPointer.pointee
        let bufferListSize = Int(
            max(
                MemoryLayout<AudioBufferList>.size,
                MemoryLayout<AudioBufferList>.size + MemoryLayout<AudioBuffer>.size * Int(max(streamDescription.mChannelsPerFrame, 1) - 1)
            )
        )
        let bufferListStorage = UnsafeMutableRawPointer.allocate(
            byteCount: bufferListSize,
            alignment: MemoryLayout<AudioBufferList>.alignment
        )
        defer {
            bufferListStorage.deallocate()
        }

        let audioBufferListPointer = bufferListStorage.bindMemory(to: AudioBufferList.self, capacity: 1)
        var blockBuffer: CMBlockBuffer?
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: audioBufferListPointer,
            bufferListSize: bufferListSize,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &blockBuffer
        )

        guard status == noErr else {
            return nil
        }

        let buffers = UnsafeMutableAudioBufferListPointer(audioBufferListPointer)
        let flags = streamDescription.mFormatFlags
        let bitsPerChannel = streamDescription.mBitsPerChannel
        let isFloat = flags & kAudioFormatFlagIsFloat != 0
        let isSignedInteger = flags & kAudioFormatFlagIsSignedInteger != 0
        var sum = 0.0
        var peak = 0.0
        var sampleCount = 0

        for buffer in buffers {
            guard let data = buffer.mData else {
                continue
            }

            let byteCount = Int(buffer.mDataByteSize)

            if isFloat, bitsPerChannel == 32 {
                let samples = data.assumingMemoryBound(to: Float.self)
                let count = byteCount / MemoryLayout<Float>.size
                for index in 0..<count {
                    let sample = Double(samples[index])
                    peak = max(peak, abs(sample))
                    sum += sample * sample
                }
                sampleCount += count
            } else if isSignedInteger, bitsPerChannel == 16 {
                let samples = data.assumingMemoryBound(to: Int16.self)
                let count = byteCount / MemoryLayout<Int16>.size
                for index in 0..<count {
                    let sample = Double(samples[index]) / Double(Int16.max)
                    peak = max(peak, abs(sample))
                    sum += sample * sample
                }
                sampleCount += count
            } else if isSignedInteger, bitsPerChannel == 32 {
                let samples = data.assumingMemoryBound(to: Int32.self)
                let count = byteCount / MemoryLayout<Int32>.size
                for index in 0..<count {
                    let sample = Double(samples[index]) / Double(Int32.max)
                    peak = max(peak, abs(sample))
                    sum += sample * sample
                }
                sampleCount += count
            }
        }

        guard sampleCount > 0 else {
            return nil
        }

        let rms = sqrt(sum / Double(sampleCount))
        guard rms.isFinite, peak.isFinite else {
            return nil
        }

        let level = meterValue(from: rms)
        let peakLevel = max(level, meterValue(from: peak))
        return AudioMeterSample(level: level, peakLevel: peakLevel)
    }

    private func meterValue(from amplitude: Double) -> Double {
        guard amplitude > 0 else {
            return 0
        }

        let decibels = 20 * log10(amplitude)
        return min(max((decibels + 60) / 60, 0), 1)
    }
}
