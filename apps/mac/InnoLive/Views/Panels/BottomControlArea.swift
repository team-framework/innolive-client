//
//  BottomControlArea.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import SwiftUI

struct BottomControlArea: View {
    @ObservedObject var studio: StudioViewModel
    @ObservedObject var cameraManager: CameraManager
    @ObservedObject var broadcastManager: BroadcastManager

    var body: some View {
        HStack(spacing: 12) {
            SceneListPanel(studio: studio)
                .frame(minWidth: 220, idealWidth: 280)
                .layoutPriority(0.8)

            SourceListPanel(studio: studio)
                .frame(minWidth: 280, idealWidth: 360)
                .layoutPriority(1)

            AudioMixerPanel(studio: studio, cameraManager: cameraManager)
                .frame(minWidth: 300)
                .layoutPriority(1)

            BroadcastMonitorPanel(
                studio: studio,
                cameraManager: cameraManager,
                broadcastManager: broadcastManager
            )
            .frame(minWidth: 280)
            .layoutPriority(0.9)
        }
        .padding(12)
    }
}

private struct SceneListPanel: View {
    @ObservedObject var studio: StudioViewModel

    var body: some View {
        GroupBox("장면") {
            VStack(spacing: 8) {
                List(selection: $studio.selectedSceneID) {
                    ForEach(studio.scenes) { scene in
                        Text(scene.name)
                            .tag(Optional(scene.id))
                    }
                }

                HStack {
                    Button {
                        studio.addScene()
                    } label: {
                        Label("추가", systemImage: "plus")
                    }

                    Button {
                        studio.duplicateSelectedScene()
                    } label: {
                        Label("복제", systemImage: "plus.square.on.square")
                    }
                    .disabled(studio.selectedSceneID == nil)

                    Button {
                        studio.removeSelectedScene()
                    } label: {
                        Label("삭제", systemImage: "trash")
                    }
                    .disabled(studio.scenes.count <= 1)
                }
                .labelStyle(.iconOnly)
            }
            .padding(4)
        }
    }
}

private struct SourceListPanel: View {
    @ObservedObject var studio: StudioViewModel

    var body: some View {
        GroupBox("소스") {
            VStack(spacing: 8) {
                List(selection: $studio.selectedSourceID) {
                    ForEach(studio.sources) { source in
                        HStack(spacing: 8) {
                            Image(systemName: source.kind.systemImage)
                                .frame(width: 18)

                            Text(source.name)
                                .lineLimit(1)

                            Spacer()

                            Toggle("", isOn: visibilityBinding(for: source))
                                .labelsHidden()
                                .toggleStyle(.switch)
                                .controlSize(.mini)

                        }
                        .tag(Optional(source.id))
                        .contentShape(Rectangle())
                        .onTapGesture {
                            studio.selectSource(source.id)
                        }
                    }
                }

                HStack {
                    Menu {
                        ForEach(SourceKind.allCases) { kind in
                            Button {
                                studio.addSource(kind: kind)
                            } label: {
                                Label(kind.title, systemImage: kind.systemImage)
                            }
                        }
                    } label: {
                        Label("추가", systemImage: "plus")
                    }

                    Button {
                        studio.removeSelectedSource()
                    } label: {
                        Label("삭제", systemImage: "trash")
                    }
                    .disabled(studio.selectedSourceID == nil)
                }
                .labelStyle(.iconOnly)
            }
            .padding(4)
        }
    }

    private func visibilityBinding(for source: StudioSource) -> Binding<Bool> {
        Binding {
            studio.sources.first(where: { $0.id == source.id })?.isVisible ?? false
        } set: { isVisible in
            studio.setSourceVisible(source.id, isVisible: isVisible)
        }
    }
}

private struct AudioMixerPanel: View {
    @ObservedObject var studio: StudioViewModel
    @ObservedObject var cameraManager: CameraManager

    var body: some View {
        GroupBox("오디오 믹서") {
            VStack(spacing: 12) {
                HStack {
                    Picker("입력", selection: selectedMicrophoneBinding) {
                        ForEach(cameraManager.microphoneDevices) { device in
                            Text(device.isDefault ? "\(device.name) (기본)" : device.name)
                                .tag(device.id)
                        }
                    }
                    .disabled(cameraManager.microphoneDevices.isEmpty)

                    Button {
                        cameraManager.refreshMicrophoneDevices()
                    } label: {
                        Label("새로고침", systemImage: "arrow.clockwise")
                    }
                    .labelStyle(.iconOnly)
                }

                ForEach(studio.audioChannels) { channel in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Toggle(channel.name, isOn: enabledBinding(for: channel))
                                .toggleStyle(.switch)

                            Spacer()

                            Text("Peak \(Int(channel.peakLevel * 100))%")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .monospacedDigit()

                            Text("Vol \(Int(channel.volume * 100))%")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .monospacedDigit()
                        }

                        AudioMeterView(
                            level: channel.level,
                            peakLevel: channel.peakLevel,
                            isEnabled: channel.isEnabled
                        )

                        Slider(value: volumeBinding(for: channel), in: 0...1) {
                            Text("\(channel.name) 볼륨")
                        }

                    }
                }

                Spacer()
            }
            .padding(4)
        }
    }

    private var selectedMicrophoneBinding: Binding<String> {
        Binding {
            cameraManager.selectedMicrophoneID ?? ""
        } set: { deviceID in
            guard !deviceID.isEmpty else {
                return
            }

            cameraManager.selectMicrophone(deviceID)
        }
    }

    private func enabledBinding(for channel: AudioChannel) -> Binding<Bool> {
        Binding {
            studio.audioChannels.first(where: { $0.id == channel.id })?.isEnabled ?? false
        } set: { isEnabled in
            if channel.kind == .microphone {
                cameraManager.setMicrophoneEnabled(isEnabled)
            }
            studio.setAudioEnabled(isEnabled, for: channel.kind)
        }
    }

    private func volumeBinding(for channel: AudioChannel) -> Binding<Double> {
        Binding {
            studio.audioChannels.first(where: { $0.id == channel.id })?.volume ?? 0
        } set: { volume in
            studio.setAudioVolume(volume, for: channel.kind)
        }
    }
}

private struct AudioMeterView: View {
    let level: Double
    let peakLevel: Double
    let isEnabled: Bool

    var body: some View {
        GeometryReader { geometry in
            let width = max(geometry.size.width, 1)
            let levelWidth = width * clamped(level)
            let peakX = width * clamped(peakLevel)

            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(Color(nsColor: .quaternaryLabelColor).opacity(0.18))

                HStack(spacing: 0) {
                    Rectangle()
                        .fill(.green.opacity(0.3))
                        .frame(width: width * 0.68)
                    Rectangle()
                        .fill(.yellow.opacity(0.35))
                        .frame(width: width * 0.17)
                    Rectangle()
                        .fill(.red.opacity(0.35))
                }
                .clipShape(RoundedRectangle(cornerRadius: 3))
                .opacity(isEnabled ? 1 : 0.35)

                RoundedRectangle(cornerRadius: 3)
                    .fill(meterColor(for: level).opacity(isEnabled ? 0.8 : 0.25))
                    .frame(width: levelWidth)

                Rectangle()
                    .fill(Color(nsColor: .labelColor))
                    .frame(width: 2, height: 14)
                    .offset(x: min(max(peakX - 1, 0), max(width - 2, 0)))
                    .opacity(isEnabled && peakLevel > 0 ? 0.9 : 0)
            }
        }
        .frame(height: 14)
        .accessibilityLabel("오디오 레벨")
        .accessibilityValue("현재 \(Int(clamped(level) * 100)) 퍼센트, 피크 \(Int(clamped(peakLevel) * 100)) 퍼센트")
    }

    private func meterColor(for value: Double) -> Color {
        switch value {
        case 0.85...:
            .red
        case 0.68..<0.85:
            .yellow
        default:
            .green
        }
    }

    private func clamped(_ value: Double) -> Double {
        min(max(value, 0), 1)
    }
}

private struct BroadcastMonitorPanel: View {
    @ObservedObject var studio: StudioViewModel
    @ObservedObject var cameraManager: CameraManager
    @ObservedObject var broadcastManager: BroadcastManager

    var body: some View {
        GroupBox("방송") {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Image(systemName: broadcastManager.state.systemImage)
                        .foregroundStyle(broadcastManager.state == .live ? .red : .primary)

                    Spacer()

                    Text(broadcastManager.mode.title)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

//                Picker("모드", selection: $broadcastManager.mode) {
//                    ForEach(BroadcastMode.allCases) { mode in
//                        Text(mode.title).tag(mode)
//                    }
//                }
                .pickerStyle(.segmented)
                .disabled(broadcastManager.isActive)

                HStack {
                    Button {
                        broadcastManager.toggle(cameraManager: cameraManager)
                    } label: {
                        Label(
                            broadcastManager.isActive ? "중지" : "시작",
                            systemImage: broadcastManager.isActive ? "stop.fill" : "play.fill"
                        )
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(broadcastManager.isActive ? .red : .accentColor)
                    .disabled(broadcastManager.state == .stopping)

//                    Button {
//                        studio.openDestinations()
//                    } label: {
//                        Label("목적지", systemImage: "arrow.up.forward.app")
//                    }
                }

                Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 4) {
//                    GridRow {
//                        MetricText(label: "비트레이트", value: "\(broadcastManager.metrics.bitrateKbps) kbps")
//                        MetricText(label: "지연", value: "\(broadcastManager.metrics.latencyMilliseconds) ms")
//                    }

                    GridRow {
                        MetricText(label: "FPS", value: "\(broadcastManager.metrics.framesPerSecond)")
//                        MetricText(label: "시청자", value: "\(broadcastManager.metrics.viewerCount)")
                    }

//                    GridRow {
//                        MetricText(label: "업링크", value: broadcastManager.mediaUplinkState.title)
//                        MetricText(label: "드롭", value: "\(broadcastManager.metrics.droppedFrames)")
//                    }
                }

                Spacer()
            }
            .padding(4)
        }
    }
}

private struct MetricText: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.caption)
                .monospacedDigit()
        }
    }
}
