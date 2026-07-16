//
//  StudioViewModel.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import Combine
import Foundation

@MainActor
final class StudioViewModel: ObservableObject {
    @Published var selectedTool: StudioTool? = .broadcast
    @Published var scenes: [StudioScene]
    @Published var selectedSceneID: StudioScene.ID?
    @Published var sources: [StudioSource]
    @Published var selectedSourceID: StudioSource.ID?
    @Published var audioChannels: [AudioChannel]
    @Published var activeSheet: StudioSheet?
    @Published var isLive = false
    @Published var isRecording = false
    @Published var isResponsePreviewEnabled = true
    @Published var statusMessage = "준비됨"
    @Published var now = Date()
    @Published var isColorCorrectionEnabled = true
    @Published var highlightLiveStatus = true
    @Published var filterStrength = 0.25
    @Published var useAutomaticResponseReconnect = true
    @Published var recordingSettings = RecordingSettings()
    @Published var isBeforeFilterWindowOpen = false
    @Published var isAfterFilterWindowOpen = false

    private var liveStartedAt: Date?
    private var recordingStartedAt: Date?
    private var lastAnnouncedRecordingURL: URL?
    private var lastAudioMeterUpdatedAt: Date?

    init() {
        let initialScene = StudioScene(name: "장면 1")
        let cameraSource = StudioSource(
            name: "내 화면",
            kind: .camera,
            layout: SourceLayout(zIndex: 0)
        )

        scenes = [initialScene]
        selectedSceneID = initialScene.id
        sources = [cameraSource]
        selectedSourceID = cameraSource.id
        audioChannels = [
            AudioChannel(kind: .microphone, name: "마이크/보조", isEnabled: true, volume: 0.82)
        ]
    }

    var liveDurationText: String {
        durationText(since: liveStartedAt)
    }

    var recordingDurationText: String {
        durationText(since: recordingStartedAt)
    }

    var selectedSceneName: String {
        scenes.first { $0.id == selectedSceneID }?.name ?? "장면 없음"
    }

    var selectedSourceName: String {
        sources.first { $0.id == selectedSourceID }?.name ?? "소스 없음"
    }

    var selectedSource: StudioSource? {
        sources.first { $0.id == selectedSourceID }
    }

    var orderedVisibleSources: [StudioSource] {
        sources
            .filter(\.isVisible)
            .sorted { lhs, rhs in
                if lhs.layout.zIndex == rhs.layout.zIndex {
                    return lhs.name < rhs.name
                }

                return lhs.layout.zIndex < rhs.layout.zIndex
            }
    }

    var sceneRecordingConfiguration: SceneRecordingConfiguration {
        let includeMicrophoneAudio = audioChannels.first { $0.kind == .microphone }?.isEnabled ?? true
        return SceneRecordingConfiguration(
            settings: recordingSettings,
            sources: sources,
            includeMicrophoneAudio: includeMicrophoneAudio
        )
    }

    func updateClock() {
        now = Date()
    }

    func updateAudioMeter(_ microphoneMeter: AudioMeterSample, timestamp: Date = Date()) {
        let elapsed = lastAudioMeterUpdatedAt.map { timestamp.timeIntervalSince($0) } ?? 0
        lastAudioMeterUpdatedAt = timestamp
        let peakDecay = min(max(elapsed, 0), 0.25) * 0.25

        var nextChannels = audioChannels

        for index in nextChannels.indices {
            guard nextChannels[index].isEnabled else {
                nextChannels[index].level = 0
                nextChannels[index].peakLevel = 0
                continue
            }

            let volume = nextChannels[index].volume
            let level = clampedAudioLevel(microphoneMeter.level * volume)
            let instantPeak = clampedAudioLevel(microphoneMeter.peakLevel * volume)
            nextChannels[index].level = level
            nextChannels[index].peakLevel = max(
                instantPeak,
                nextChannels[index].peakLevel - peakDecay
            )
        }

        if nextChannels != audioChannels {
            audioChannels = nextChannels
        }
    }

    func toggleLive() {
        isLive.toggle()
        liveStartedAt = isLive ? Date() : nil
        statusMessage = isLive ? "방송을 시작했습니다." : "방송을 중지했습니다."
    }

    func syncBroadcastState(_ state: BroadcastState, startedAt: Date?) {
        isLive = state == .live
        liveStartedAt = startedAt
    }

    func toggleRecording() {
        isRecording.toggle()
        recordingStartedAt = isRecording ? Date() : nil
        statusMessage = isRecording ? "녹화를 시작했습니다." : "녹화를 중지했습니다."
    }

    func syncRecordingState(_ isRecording: Bool, lastRecordingURL: URL?) {
        guard self.isRecording != isRecording else {
            if isRecording, recordingStartedAt == nil {
                recordingStartedAt = Date()
                statusMessage = "녹화를 시작했습니다."
            }

            if !isRecording, let lastRecordingURL, lastRecordingURL != lastAnnouncedRecordingURL {
                lastAnnouncedRecordingURL = lastRecordingURL
                statusMessage = "녹화를 저장했습니다: \(lastRecordingURL.lastPathComponent)"
            }
            return
        }

        self.isRecording = isRecording

        if isRecording {
            recordingStartedAt = Date()
            statusMessage = "녹화를 시작했습니다."
        } else {
            recordingStartedAt = nil
            if let lastRecordingURL, lastRecordingURL != lastAnnouncedRecordingURL {
                lastAnnouncedRecordingURL = lastRecordingURL
                statusMessage = "녹화를 저장했습니다: \(lastRecordingURL.lastPathComponent)"
            } else {
                statusMessage = "녹화를 중지했습니다."
            }
        }
    }

    func setMicrophoneEnabled(_ isEnabled: Bool) {
        setAudioEnabled(isEnabled, for: .microphone)
    }

    func setAudioEnabled(_ isEnabled: Bool, for kind: AudioChannelKind) {
        guard let index = audioChannels.firstIndex(where: { $0.kind == kind }) else {
            return
        }

        audioChannels[index].isEnabled = isEnabled
        if !isEnabled {
            audioChannels[index].level = 0
            audioChannels[index].peakLevel = 0
            lastAudioMeterUpdatedAt = nil
        }
        statusMessage = isEnabled ? "마이크를 켰습니다." : "마이크를 껐습니다."
    }

    func setAudioVolume(_ volume: Double, for kind: AudioChannelKind) {
        guard let index = audioChannels.firstIndex(where: { $0.kind == kind }) else {
            return
        }

        audioChannels[index].volume = min(max(volume, 0), 1)
    }

    func toggleResponsePreview() {
        isResponsePreviewEnabled.toggle()
        statusMessage = isResponsePreviewEnabled ? "응답 영상 미리보기를 켰습니다." : "응답 영상 미리보기를 껐습니다."
    }

    func addScene() {
        let scene = StudioScene(name: "장면 \(scenes.count + 1)")
        scenes.append(scene)
        selectedSceneID = scene.id
        statusMessage = "\(scene.name)을 추가했습니다."
    }

    func duplicateSelectedScene() {
        guard let selectedScene = scenes.first(where: { $0.id == selectedSceneID }) else {
            return
        }

        let scene = StudioScene(name: "\(selectedScene.name) 복사본")
        scenes.append(scene)
        selectedSceneID = scene.id
        statusMessage = "\(selectedScene.name)을 복제했습니다."
    }

    func removeSelectedScene() {
        guard scenes.count > 1,
              let selectedSceneID,
              let index = scenes.firstIndex(where: { $0.id == selectedSceneID }) else {
            statusMessage = "최소 1개의 장면은 필요합니다."
            return
        }

        let removed = scenes.remove(at: index)
        self.selectedSceneID = scenes[min(index, scenes.count - 1)].id
        statusMessage = "\(removed.name)을 삭제했습니다."
    }

    func addSource(kind: SourceKind) {
        let source = StudioSource(
            name: defaultSourceName(for: kind),
            kind: kind,
            layout: defaultLayout(for: kind),
            text: kind == .text ? "라이브 텍스트" : "",
            colorHex: "#3478F6"
        )
        sources.append(source)
        selectedSourceID = source.id
        statusMessage = "\(source.name)을 추가했습니다."
    }

    func addMediaSource() {
        addSource(kind: .media)
    }

    func removeSelectedSource() {
        guard let selectedSourceID,
              let index = sources.firstIndex(where: { $0.id == selectedSourceID }) else {
            return
        }

        guard !sources[index].isLocked else {
            statusMessage = "잠긴 소스는 삭제할 수 없습니다."
            return
        }

        let removed = sources.remove(at: index)
        self.selectedSourceID = sources.isEmpty ? nil : sources[min(index, sources.count - 1)].id
        statusMessage = "\(removed.name)을 삭제했습니다."
    }

    func selectSource(_ sourceID: StudioSource.ID) {
        selectedSourceID = sourceID
        if let source = sources.first(where: { $0.id == sourceID }) {
            statusMessage = "\(source.name)을 선택했습니다."
        }
    }

    func setSourceVisible(_ sourceID: StudioSource.ID, isVisible: Bool) {
        guard let index = sources.firstIndex(where: { $0.id == sourceID }) else {
            return
        }

        sources[index].isVisible = isVisible
        statusMessage = "\(sources[index].name)을 \(isVisible ? "표시" : "숨김") 처리했습니다."
    }

    func toggleSelectedSourceLock() {
        guard let selectedSourceID,
              let index = sources.firstIndex(where: { $0.id == selectedSourceID }) else {
            return
        }

        sources[index].isLocked.toggle()
        statusMessage = "\(sources[index].name)을 \(sources[index].isLocked ? "잠금" : "잠금 해제")했습니다."
    }

    func updateSelectedSourceLayout(_ layout: SourceLayout) {
        guard let selectedSourceID else {
            return
        }

        updateSourceLayout(selectedSourceID, layout: layout)
    }

    func updateSourceLayout(_ sourceID: StudioSource.ID, layout: SourceLayout) {
        guard let index = sources.firstIndex(where: { $0.id == sourceID }),
              !sources[index].isLocked else {
            return
        }

        let nextLayout = clampedLayout(layout)
        guard !layoutsAreEquivalent(sources[index].layout, nextLayout) else {
            return
        }

        sources[index].layout = nextLayout
    }

    func moveSource(_ sourceID: StudioSource.ID, deltaX: Double, deltaY: Double) {
        guard let index = sources.firstIndex(where: { $0.id == sourceID }),
              !sources[index].isLocked else {
            return
        }

        var layout = sources[index].layout
        layout.x += deltaX
        layout.y += deltaY
        let nextLayout = clampedLayout(layout)
        guard !layoutsAreEquivalent(sources[index].layout, nextLayout) else {
            return
        }

        sources[index].layout = nextLayout
    }

    func resizeSource(_ sourceID: StudioSource.ID, deltaWidth: Double, deltaHeight: Double) {
        guard let index = sources.firstIndex(where: { $0.id == sourceID }),
              !sources[index].isLocked else {
            return
        }

        var layout = sources[index].layout
        layout.width += deltaWidth
        layout.height += deltaHeight
        let nextLayout = clampedLayout(layout)
        guard !layoutsAreEquivalent(sources[index].layout, nextLayout) else {
            return
        }

        sources[index].layout = nextLayout
    }

    func updateSelectedSourceText(_ text: String) {
        guard let selectedSourceID,
              let index = sources.firstIndex(where: { $0.id == selectedSourceID }) else {
            return
        }

        sources[index].text = text
        if sources[index].kind == .text {
            sources[index].name = text.isEmpty ? "텍스트" : text
        }
    }

    func updateSelectedSourceColor(_ colorHex: String) {
        guard let selectedSourceID,
              let index = sources.firstIndex(where: { $0.id == selectedSourceID }) else {
            return
        }

        sources[index].colorHex = colorHex
    }

    func updateSelectedSourceAssetURL(_ assetURL: URL?) {
        guard let selectedSourceID,
              let index = sources.firstIndex(where: { $0.id == selectedSourceID }) else {
            return
        }

        sources[index].assetURL = assetURL
        if let assetURL {
            sources[index].name = assetURL.deletingPathExtension().lastPathComponent
            statusMessage = "\(sources[index].name)을 소스로 연결했습니다."
        }
    }

    func updateSelectedScreenDisplayID(_ displayID: UInt32) {
        guard let selectedSourceID,
              let index = sources.firstIndex(where: { $0.id == selectedSourceID }),
              sources[index].kind == .screen else {
            return
        }

        sources[index].screenCapture.displayID = displayID
        statusMessage = "\(sources[index].name)의 모니터를 변경했습니다."
    }

    func updateSelectedScreenResolution(_ resolution: ScreenCaptureResolution) {
        guard let selectedSourceID,
              let index = sources.firstIndex(where: { $0.id == selectedSourceID }),
              sources[index].kind == .screen else {
            return
        }

        sources[index].screenCapture.resolution = resolution
        statusMessage = "\(sources[index].name)의 캡처 해상도를 \(resolution.title)로 변경했습니다."
    }

    func bringSelectedSourceForward() {
        guard let selectedSourceID,
              let index = sources.firstIndex(where: { $0.id == selectedSourceID }) else {
            return
        }

        sources[index].layout.zIndex += 1
        statusMessage = "\(sources[index].name)을 앞으로 보냈습니다."
    }

    func sendSelectedSourceBackward() {
        guard let selectedSourceID,
              let index = sources.firstIndex(where: { $0.id == selectedSourceID }) else {
            return
        }

        sources[index].layout.zIndex -= 1
        statusMessage = "\(sources[index].name)을 뒤로 보냈습니다."
    }

    func updateRecordingResolution(_ resolution: RecordingResolution) {
        recordingSettings.resolution = resolution
        statusMessage = "녹화 해상도를 \(resolution.title)로 변경했습니다."
    }

    func updateRecordingBitrate(_ bitrateKbps: Int) {
        recordingSettings.videoBitrateKbps = min(max(bitrateKbps, 1_000), 30_000)
        statusMessage = "비디오 비트레이트를 \(recordingSettings.videoBitrateKbps) kbps로 변경했습니다."
    }

    func openCameraProperties() {
        activeSheet = .cameraProperties
    }

    func openDestinations() {
        activeSheet = .destinations
    }

    func openServerDiagnostics() {
        activeSheet = .serverDiagnostics
    }

    func openFilters() {
        activeSheet = .filters
    }

    func openResponseSettings() {
        activeSheet = .responseSettings
    }

    func openAppSettings() {
        activeSheet = .appSettings
    }

    private func durationText(since startDate: Date?) -> String {
        guard let startDate else {
            return "00:00:00"
        }

        let seconds = max(Int(now.timeIntervalSince(startDate)), 0)
        let hours = seconds / 3_600
        let minutes = (seconds % 3_600) / 60
        let remainingSeconds = seconds % 60
        return String(format: "%02d:%02d:%02d", hours, minutes, remainingSeconds)
    }

    private func defaultSourceName(for kind: SourceKind) -> String {
        switch kind {
        case .camera:
            "카메라 \(sources.filter { $0.kind == .camera }.count + 1)"
        case .delayedResponse:
            "응답 영상 \(sources.filter { $0.kind == .delayedResponse }.count + 1)"
        case .music:
            "뮤직 플레이리스트 \(sources.filter { $0.kind == .music }.count + 1)"
        case .media:
            "미디어 소스 \(sources.filter { $0.kind == .media }.count + 1)"
        case .text:
            "라이브 텍스트"
        case .color:
            "색상 소스 \(sources.filter { $0.kind == .color }.count + 1)"
        case .image:
            "이미지 소스 \(sources.filter { $0.kind == .image }.count + 1)"
        case .screen:
            "화면 캡처 \(sources.filter { $0.kind == .screen }.count + 1)"
        }
    }

    private func defaultLayout(for kind: SourceKind) -> SourceLayout {
        let nextZIndex = (sources.map { $0.layout.zIndex }.max() ?? 0) + 1

        switch kind {
        case .camera:
            return SourceLayout(zIndex: nextZIndex)
        case .delayedResponse:
            return SourceLayout(x: 0.66, y: 0.62, width: 0.3, height: 0.3, zIndex: nextZIndex)
        case .text:
            return SourceLayout(x: 0.08, y: 0.08, width: 0.42, height: 0.12, zIndex: nextZIndex)
        case .color:
            return SourceLayout(x: 0.1, y: 0.1, width: 0.32, height: 0.22, zIndex: nextZIndex)
        case .music, .media, .image, .screen:
            return SourceLayout(x: 0.12, y: 0.12, width: 0.42, height: 0.28, zIndex: nextZIndex)
        }
    }

    private func clampedLayout(_ layout: SourceLayout) -> SourceLayout {
        var clamped = layout
        clamped.width = min(max(clamped.width, 0.05), 1)
        clamped.height = min(max(clamped.height, 0.05), 1)
        clamped.x = min(max(clamped.x, 0), 1 - clamped.width)
        clamped.y = min(max(clamped.y, 0), 1 - clamped.height)
        clamped.opacity = min(max(clamped.opacity, 0.1), 1)
        clamped.x = roundedLayoutValue(clamped.x)
        clamped.y = roundedLayoutValue(clamped.y)
        clamped.width = roundedLayoutValue(clamped.width)
        clamped.height = roundedLayoutValue(clamped.height)
        clamped.opacity = roundedLayoutValue(clamped.opacity)
        return clamped
    }

    private func roundedLayoutValue(_ value: Double) -> Double {
        (value * 10_000).rounded() / 10_000
    }

    private func clampedAudioLevel(_ level: Double) -> Double {
        min(max(level, 0), 1)
    }

    private func layoutsAreEquivalent(_ lhs: SourceLayout, _ rhs: SourceLayout) -> Bool {
        lhs.zIndex == rhs.zIndex
            && abs(lhs.x - rhs.x) < 0.0001
            && abs(lhs.y - rhs.y) < 0.0001
            && abs(lhs.width - rhs.width) < 0.0001
            && abs(lhs.height - rhs.height) < 0.0001
            && abs(lhs.opacity - rhs.opacity) < 0.0001
    }
}
