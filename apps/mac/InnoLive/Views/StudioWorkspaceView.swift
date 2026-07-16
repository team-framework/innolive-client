//
//  StudioWorkspaceView.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import AppKit
import Combine
import SwiftUI

struct StudioWorkspaceView: View {
    @ObservedObject var studio: StudioViewModel
    @ObservedObject var cameraManager: CameraManager
    @ObservedObject var broadcastManager: BroadcastManager

    @State private var bottomControlPanelHeight: CGFloat = 250
    @State private var bottomPanelDragStartHeight: CGFloat?
    @State private var bottomPanelDragStartY: CGFloat?
    @State private var faceRegistrationWindowController: FaceRegistrationWindowController?

    private let clockTimer = Timer.publish(every: 0.3, on: .main, in: .common).autoconnect()
    private let minimumBottomControlPanelHeight: CGFloat = 170
    private let defaultBottomControlPanelHeight: CGFloat = 250
    private let minimumPreviewHeight: CGFloat = 280

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                MainPreviewArea(
                    studio: studio,
                    cameraManager: cameraManager,
                    broadcastManager: broadcastManager
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                BottomPanelResizeHandle(isDragging: bottomPanelDragStartHeight != nil)
                    .gesture(bottomPanelResizeGesture(availableHeight: geometry.size.height))
                    .onTapGesture(count: 2) {
                        bottomControlPanelHeight = clampedBottomPanelHeight(
                            defaultBottomControlPanelHeight,
                            availableHeight: geometry.size.height
                        )
                    }

                BottomControlArea(
                    studio: studio,
                    cameraManager: cameraManager,
                    broadcastManager: broadcastManager
                )
                .frame(
                    height: clampedBottomPanelHeight(
                        bottomControlPanelHeight,
                        availableHeight: geometry.size.height
                    )
                )

                Divider()

                StudioStatusBar(
                    studio: studio,
                    cameraManager: cameraManager,
                    broadcastManager: broadcastManager
                )
            }
            .onChange(of: geometry.size.height) { _, newHeight in
                bottomControlPanelHeight = clampedBottomPanelHeight(
                    bottomControlPanelHeight,
                    availableHeight: newHeight
                )
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
        .navigationTitle("InnoLive")
        .toolbar {
            ToolbarItemGroup {
                Button {
                    let shouldStart = !cameraManager.isRunning
                    cameraManager.toggle()
                    studio.statusMessage = shouldStart ? "카메라 시작을 요청했습니다." : "카메라 중지를 요청했습니다."
                } label: {
                    Label(cameraManager.isRunning ? "카메라 중지" : "카메라 시작", systemImage: cameraManager.isRunning ? "video.slash" : "video")
                }

                Button {
                    studio.toggleResponsePreview()
                } label: {
                    Label(studio.isResponsePreviewEnabled ? "응답 끄기" : "응답 켜기", systemImage: studio.isResponsePreviewEnabled ? "pause.circle" : "play.circle")
                }

                Button {
                    studio.openAppSettings()
                } label: {
                    Label("설정", systemImage: "gearshape")
                }

                Button {
                    openFaceRegistrationWindow()
                } label: {
                    Label("얼굴 관리", systemImage: "person.crop.circle")
                }
            }

            ToolbarItemGroup(placement: .primaryAction) {
                Button {
                    broadcastManager.toggle(cameraManager: cameraManager)
                } label: {
                    Label(
                        broadcastManager.isActive ? "방송 중지" : "방송 시작",
                        systemImage: broadcastManager.isActive ? "stop.circle" : "dot.radiowaves.left.and.right"
                    )
                }
                .buttonStyle(.borderedProminent)
                .tint(broadcastManager.isActive ? .red : .accentColor)
                .disabled(broadcastManager.state == .stopping)

                Button {
                    cameraManager.toggleRecording(configuration: studio.sceneRecordingConfiguration)
                } label: {
                    Label(cameraManager.isRecording ? "녹화 중지" : "녹화 시작", systemImage: cameraManager.isRecording ? "stop.fill" : "record.circle")
                }
                .buttonStyle(.borderedProminent)
                .tint(cameraManager.isRecording ? .red : .gray)

                Button {
                    cameraManager.revealLastRecording()
                } label: {
                    Label("녹화 파일 보기", systemImage: "folder")
                }
                .disabled(cameraManager.lastRecordingURL == nil)
            }
        }
        .sheet(item: $studio.activeSheet) { sheet in
            StudioSheetView(
                sheet: sheet,
                studio: studio,
                cameraManager: cameraManager,
                broadcastManager: broadcastManager
            )
        }
        .onReceive(clockTimer) { _ in
            studio.updateClock()
            studio.syncBroadcastState(
                broadcastManager.state,
                startedAt: broadcastManager.startedAt
            )
        }
        .onReceive(cameraManager.$microphoneMeter.receive(on: DispatchQueue.main)) { meter in
            studio.updateAudioMeter(meter)
        }
        .onAppear {
            cameraManager.refreshScreenCaptureDisplays()
            cameraManager.configureScreenCaptureSources(studio.sources)
        }
        .onChange(of: studio.sources) { _, sources in
            cameraManager.configureScreenCaptureSources(sources)
        }
        .onChange(of: cameraManager.isRecording) { _, isRecording in
            studio.syncRecordingState(isRecording, lastRecordingURL: cameraManager.lastRecordingURL)
        }
        .onChange(of: cameraManager.lastRecordingURL) { _, url in
            studio.syncRecordingState(cameraManager.isRecording, lastRecordingURL: url)
        }
        .onChange(of: cameraManager.selectedCameraID) { _, _ in
            broadcastManager.updateMediaUplinkCamera(name: cameraManager.selectedCameraName)
        }
        .onChange(of: broadcastManager.state) { _, state in
            studio.syncBroadcastState(
                state,
                startedAt: broadcastManager.startedAt
            )
        }
    }

    private func bottomPanelResizeGesture(availableHeight: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 1, coordinateSpace: .global)
            .onChanged { value in
                if bottomPanelDragStartHeight == nil {
                    bottomPanelDragStartHeight = bottomControlPanelHeight
                    bottomPanelDragStartY = value.startLocation.y
                }

                let startHeight = bottomPanelDragStartHeight ?? bottomControlPanelHeight
                let startY = bottomPanelDragStartY ?? value.startLocation.y
                let nextHeight = clampedBottomPanelHeight(
                    startHeight - (value.location.y - startY),
                    availableHeight: availableHeight
                )
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    bottomControlPanelHeight = nextHeight
                }
            }
            .onEnded { _ in
                bottomPanelDragStartHeight = nil
                bottomPanelDragStartY = nil
            }
    }

    private func clampedBottomPanelHeight(_ height: CGFloat, availableHeight: CGFloat) -> CGFloat {
        let maximumHeight = max(
            minimumBottomControlPanelHeight,
            availableHeight - minimumPreviewHeight
        )
        return min(max(height, minimumBottomControlPanelHeight), maximumHeight)
    }

    private func openFaceRegistrationWindow() {
        guard let registrationURL = referenceFaceRegistrationURL() else {
            studio.statusMessage = "얼굴 등록 서버 주소를 확인해 주세요."
            return
        }

        if !cameraManager.isRunning {
            cameraManager.start()
        }

        if let faceRegistrationWindowController,
           faceRegistrationWindowController.window?.isVisible == true {
            faceRegistrationWindowController.window?.makeKeyAndOrderFront(nil)
            NSApp.activate()
            return
        }

        let windowController = FaceRegistrationWindowController(
            cameraManager: cameraManager,
            registrationURL: registrationURL
        )
        faceRegistrationWindowController = windowController
        windowController.showWindow(nil)
        NSApp.activate()
        studio.statusMessage = "얼굴 관리 창을 열었습니다."
    }

    private func referenceFaceRegistrationURL() -> URL? {
        broadcastManager.serverEndpointURL(path: "/reference-face")
    }
}

private struct BottomPanelResizeHandle: View {
    let isDragging: Bool
    @State private var isHovering = false

    var body: some View {
        ZStack {
            Rectangle()
                .fill(Color(nsColor: .separatorColor))
                .frame(height: 1)

            Capsule()
                .fill(isDragging || isHovering ? Color.accentColor : Color(nsColor: .tertiaryLabelColor))
                .frame(width: 44, height: 4)
        }
        .frame(height: 12)
        .contentShape(Rectangle())
        .onHover { hovering in
            isHovering = hovering
            if hovering {
                NSCursor.resizeUpDown.push()
            } else {
                NSCursor.pop()
            }
        }
        .accessibilityLabel("하단 컨트롤 패널 크기 조절")
    }
}
