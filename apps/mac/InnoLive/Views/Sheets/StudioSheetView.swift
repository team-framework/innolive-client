//
//  StudioSheetView.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import AppKit
import SwiftUI

struct StudioSheetView: View {
    let sheet: StudioSheet
    @ObservedObject var studio: StudioViewModel
    @ObservedObject var cameraManager: CameraManager
    @ObservedObject var broadcastManager: BroadcastManager

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(sheet.title)
                .font(.title3.weight(.semibold))

            switch sheet {
            case .destinations:
                destinations
            case .serverDiagnostics:
                serverDiagnostics
            case .cameraProperties:
                cameraProperties
            case .filters:
                filters
            case .responseSettings:
                responseSettings
            case .appSettings:
                appSettings
            }

            HStack {
                Spacer()
                Button("닫기") {
                    studio.activeSheet = nil
                }
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(22)
        .frame(width: sheetWidth)
    }

    private var sheetWidth: CGFloat {
        switch sheet {
        case .destinations:
            620
        case .serverDiagnostics:
            560
        default:
            500
        }
    }

    private var destinations: some View {
        Form {
            ForEach($broadcastManager.destinations) { $destination in
                Section {
                    Toggle("사용", isOn: $destination.isEnabled)

                    TextField("송출 URL", text: $destination.ingestURL)
                        .disabled(!destination.isEnabled)

                    SecureField("스트림 키", text: $destination.streamKey)
                        .disabled(!destination.isEnabled)
                } header: {
                    Label(destination.platform.title, systemImage: destination.platform.systemImage)
                } footer: {
                    Text("앱은 목적지 정보를 서버에 전달하고, 실제 RTMP/YouTube 송출은 AI 처리 서버가 수행합니다.")
                }
            }
        }
    }

    private var serverDiagnostics: some View {
        Form {
            LabeledContent("세션 ID", value: broadcastManager.sessionID)
            LabeledContent("제어 채널", value: broadcastManager.signalingURLString)
            LabeledContent("처리 영상", value: broadcastManager.processedVideoURLString.isEmpty ? "로컬 지연 미리보기" : broadcastManager.processedVideoURLString)
            LabeledContent("비트레이트", value: "\(broadcastManager.metrics.bitrateKbps) kbps")
            LabeledContent("지연", value: "\(broadcastManager.metrics.latencyMilliseconds) ms")
            LabeledContent("드롭 프레임", value: "\(broadcastManager.metrics.droppedFrames)")

            Text("서버 제어 프로토콜")
                .font(.headline)

            Text(controlProtocolSummary)
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack {
                Button("프로토콜 복사") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(controlProtocolSummary, forType: .string)
                    studio.statusMessage = "서버 제어 프로토콜을 복사했습니다."
                }

                Button("메트릭 초기화") {
                    broadcastManager.resetMetrics()
                    studio.statusMessage = "방송 메트릭을 초기화했습니다."
                }
            }
        }
    }

    private var cameraProperties: some View {
        Form {
            LabeledContent("프리셋", value: studio.recordingSettings.resolution.title)
            LabeledContent("프레임", value: "\(studio.recordingSettings.resolution.title) · \(studio.recordingSettings.frameRate)fps")

            HStack {
                Picker("카메라 입력", selection: selectedCameraBinding) {
                    ForEach(cameraManager.cameraDevices) { device in
                        Text(device.isDefault ? "\(device.name) (기본)" : device.name)
                            .tag(device.id)
                    }
                }
                .disabled(cameraManager.cameraDevices.isEmpty || cameraManager.isSwitchingCamera)

                Button {
                    cameraManager.refreshCameraDevices()
                } label: {
                    Label("카메라 목록 새로고침", systemImage: "arrow.clockwise")
                }
                .labelStyle(.iconOnly)
                .disabled(cameraManager.isSwitchingCamera)
            }

            Picker("마이크 입력", selection: selectedMicrophoneBinding) {
                ForEach(cameraManager.microphoneDevices) { device in
                    Text(device.isDefault ? "\(device.name) (기본)" : device.name)
                        .tag(device.id)
                }
            }
            .disabled(cameraManager.microphoneDevices.isEmpty)

            HStack {
                Button("마이크 목록 새로고침") {
                    cameraManager.refreshMicrophoneDevices()
                }

                Toggle("마이크 사용", isOn: Binding {
                    cameraManager.isMicrophoneEnabled
                } set: { isEnabled in
                    cameraManager.setMicrophoneEnabled(isEnabled)
                    studio.setMicrophoneEnabled(isEnabled)
                })
            }

            Toggle("카메라 실행", isOn: Binding {
                cameraManager.isRunning
            } set: { shouldRun in
                if shouldRun {
                    cameraManager.start()
                    studio.statusMessage = "카메라 시작을 요청했습니다."
                } else {
                    cameraManager.stop()
                    studio.statusMessage = "카메라 중지를 요청했습니다."
                }
            })
        }
    }

    private var filters: some View {
        Form {
            Toggle("색상 보정 필터 사용", isOn: $studio.isColorCorrectionEnabled)
            Slider(value: $studio.filterStrength, in: 0...1) {
                Text("필터 강도")
            } minimumValueLabel: {
                Text("낮음")
            } maximumValueLabel: {
                Text("높음")
            }

            LabeledContent("현재 강도", value: "\(Int(studio.filterStrength * 100))%")
        }
    }

    private var responseSettings: some View {
        Form {
            Toggle("응답 영상 미리보기", isOn: $studio.isResponsePreviewEnabled)
            Toggle("서버 재연결 자동 시도", isOn: $studio.useAutomaticResponseReconnect)

            TextField("처리 영상 URL", text: $broadcastManager.processedVideoURLString)
            LabeledContent("응답 지연", value: broadcastManager.processedVideoURL == nil ? "3초 로컬 테스트" : "서버 영상")
            LabeledContent("현재 소스", value: broadcastManager.processedVideoURL == nil ? "서버 응답 대신 카메라 지연 프레임 표시" : "서버 처리 영상 재생")

            HStack {
                Button("진단 열기") {
                    studio.openServerDiagnostics()
                }

                Button(studio.isResponsePreviewEnabled ? "응답 끄기" : "응답 켜기") {
                    studio.toggleResponsePreview()
                }
            }
        }
    }

    private var appSettings: some View {
        Form {
            Picker("기본 도구", selection: Binding {
                studio.selectedTool ?? .broadcast
            } set: { tool in
                studio.selectedTool = tool
                studio.statusMessage = "\(tool.title) 도구를 선택했습니다."
            }) {
                ForEach(StudioTool.allCases) { tool in
                    Label(tool.title, systemImage: tool.systemImage)
                        .tag(tool)
                }
            }

            Picker("녹화 해상도", selection: recordingResolutionBinding) {
                ForEach(RecordingResolution.allCases) { resolution in
                    Text(resolution.title).tag(resolution)
                }
            }

            Stepper(value: recordingBitrateBinding, in: 1_000...30_000, step: 500) {
                LabeledContent("비디오 비트레이트", value: "\(studio.recordingSettings.videoBitrateKbps) kbps")
            }

            Picker("기본 마이크", selection: selectedMicrophoneBinding) {
                ForEach(cameraManager.microphoneDevices) { device in
                    Text(device.isDefault ? "\(device.name) (기본)" : device.name)
                        .tag(device.id)
                }
            }
            .disabled(cameraManager.microphoneDevices.isEmpty)

            Toggle("방송 중 상태 막대 강조", isOn: $studio.highlightLiveStatus)
            Toggle("응답 영상 자동 시작", isOn: $studio.isResponsePreviewEnabled)

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

    private var selectedCameraBinding: Binding<String> {
        Binding {
            cameraManager.selectedCameraID ?? ""
        } set: { deviceID in
            guard !deviceID.isEmpty else {
                return
            }

            cameraManager.selectCamera(deviceID)
        }
    }

    private var recordingResolutionBinding: Binding<RecordingResolution> {
        Binding {
            studio.recordingSettings.resolution
        } set: { resolution in
            studio.updateRecordingResolution(resolution)
            cameraManager.updateRecordingSettings(studio.recordingSettings)
        }
    }

    private var recordingBitrateBinding: Binding<Int> {
        Binding {
            studio.recordingSettings.videoBitrateKbps
        } set: { bitrate in
            studio.updateRecordingBitrate(bitrate)
            cameraManager.updateRecordingSettings(studio.recordingSettings)
        }
    }

    private var controlProtocolSummary: String {
        """
        Client -> Server JSON:
        {
          "type": "startBroadcast | stopBroadcast | heartbeat | ping",
          "sessionID": "\(broadcastManager.sessionID)",
          "title": "\(broadcastManager.streamTitle)",
          "broadcasterID": "\(broadcastManager.broadcasterID)",
          "mosaicPolicy": "mosaic_all_faces_except_broadcaster",
          "destinations": [{"platform": "youtube", "ingestURL": "...", "streamKey": "..."}],
          "mediaUplink": {"type": "offer | iceCandidate", "sdp": "...", "candidate": "..."}
        }

        Server -> Client JSON:
        {
          "type": "status | metrics | processedVideo",
          "status": "live",
          "processedVideoURL": "https://server/output.m3u8",
          "bitrateKbps": 4500,
          "latencyMilliseconds": 3000,
          "framesPerSecond": 30,
          "droppedFrames": 0,
          "viewerCount": 0,
          "message": "AI mosaic stream is live",
          "mediaUplink": {"type": "answer | iceCandidate | status | error", "sdp": "..."}
        }
        """
    }
}
