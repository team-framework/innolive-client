//
//  ExpandedPreviewWindow.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import AppKit
import SwiftUI

private let expandedPreviewCropScale = 1.1

@MainActor
final class ExpandedPreviewWindowController: NSWindowController, NSWindowDelegate {
    private static var activeControllers: [ExpandedPreviewKind: ExpandedPreviewWindowController] = [:]

    private let kind: ExpandedPreviewKind
    private let studio: StudioViewModel

    static func present(
        kind: ExpandedPreviewKind,
        studio: StudioViewModel,
        cameraManager: CameraManager,
        broadcastManager: BroadcastManager,
        responsePreviewPortal: WebRTCResponsePreviewPortal? = nil
    ) {
        if let existingController = activeControllers[kind],
           let existingWindow = existingController.window,
           existingWindow.isVisible {
            existingWindow.makeKeyAndOrderFront(nil)
            NSApp.activate()
            return
        }

        let controller = ExpandedPreviewWindowController(
            kind: kind,
            studio: studio,
            cameraManager: cameraManager,
            broadcastManager: broadcastManager,
            responsePreviewPortal: responsePreviewPortal
        )
        activeControllers[kind] = controller
        controller.showWindow(nil)
        controller.window?.makeKeyAndOrderFront(nil)
        NSApp.activate()
    }

    init(
        kind: ExpandedPreviewKind,
        studio: StudioViewModel,
        cameraManager: CameraManager,
        broadcastManager: BroadcastManager,
        responsePreviewPortal: WebRTCResponsePreviewPortal?
    ) {
        self.kind = kind
        self.studio = studio

        var closeWindow: () -> Void = {}
        let rootView = ExpandedPreviewWindow(
            kind: kind,
            studio: studio,
            cameraManager: cameraManager,
            broadcastManager: broadcastManager,
            responsePreviewPortal: responsePreviewPortal,
            onClose: {
                closeWindow()
            }
        )
        let hostingController = NSHostingController(rootView: rootView)
        let window = ExpandedPreviewFullscreenWindow(contentViewController: hostingController)

        let targetScreen = NSApp.keyWindow?.screen ?? NSScreen.main ?? NSScreen.screens.first
        let targetFrame = targetScreen?.frame ?? NSRect(x: 0, y: 0, width: 1280, height: 720)

        window.title = kind.title
        window.setFrame(targetFrame, display: true)
        window.styleMask = [.borderless]
        window.level = .mainMenu + 1
        window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        window.backgroundColor = .black
        window.isOpaque = true
        window.hasShadow = false
        window.isReleasedWhenClosed = false

        closeWindow = { [weak window] in
            window?.close()
        }

        super.init(window: window)

        window.delegate = self
        setWindowOpenFlag(true)
    }

    required init?(coder: NSCoder) {
        nil
    }

    func windowWillClose(_ notification: Notification) {
        setWindowOpenFlag(false)
        Self.activeControllers[kind] = nil
    }

    private func setWindowOpenFlag(_ isOpen: Bool) {
        switch kind {
        case .beforeFilter:
            studio.isBeforeFilterWindowOpen = isOpen
        case .afterFilter:
            studio.isAfterFilterWindowOpen = isOpen
        }
    }
}

private final class ExpandedPreviewFullscreenWindow: NSWindow {
    override var canBecomeKey: Bool {
        true
    }

    override var canBecomeMain: Bool {
        true
    }

    override func keyDown(with event: NSEvent) {
        if event.keyCode == 53 {
            close()
            return
        }

        super.keyDown(with: event)
    }
}

struct ExpandedPreviewWindow: View {
    let kind: ExpandedPreviewKind
    @ObservedObject var studio: StudioViewModel
    @ObservedObject var cameraManager: CameraManager
    @ObservedObject var broadcastManager: BroadcastManager
    let responsePreviewPortal: WebRTCResponsePreviewPortal?
    let onClose: () -> Void

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            previewContent

            Button {
                onClose()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(.black.opacity(0.55), in: Circle())
            }
            .buttonStyle(.plain)
            .padding(24)
            .accessibilityLabel("전체화면 닫기")
        }
        .onExitCommand {
            onClose()
        }
    }

    @ViewBuilder
    private var previewContent: some View {
        switch kind {
        case .beforeFilter:
            content
                .aspectRatio(16 / 9, contentMode: .fit)
                .scaleEffect(expandedPreviewCropScale)
                .clipped()
        case .afterFilter:
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch kind {
        case .beforeFilter:
            SceneCanvasView(
                studio: studio,
                cameraManager: cameraManager,
                broadcastManager: broadcastManager
            )
        case .afterFilter:
            ServerResponsePreview(
                isEnabled: studio.isResponsePreviewEnabled,
                broadcastManager: broadcastManager,
                allowsWebRTCPreview: true,
                previewPortal: responsePreviewPortal,
                placement: .fullscreen
            )
        }
    }
}

private extension ExpandedPreviewKind {
    var title: String {
        switch self {
        case .beforeFilter:
            "내 화면"
        case .afterFilter:
            "서버 처리 영상"
        }
    }
}
