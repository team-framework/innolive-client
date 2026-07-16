//
//  MainPreviewArea.swift
//  InnoLive
//
//  Created by chaeyn on 5/28/26.
//

import AppKit
import SwiftUI

private let previewCropScale = 1.1

struct MainPreviewArea: View {
    @ObservedObject var studio: StudioViewModel
    @ObservedObject var cameraManager: CameraManager
    @ObservedObject var broadcastManager: BroadcastManager
    @State private var responsePreviewPortal = WebRTCResponsePreviewPortal()

    private var isCameraVisible: Bool {
        studio.sources.first { $0.kind == .camera }?.isVisible ?? true
    }

    private var isResponseVisible: Bool {
        studio.sources.first { $0.kind == .delayedResponse }?.isVisible ?? true
    }

    var body: some View {
        HSplitView {
            VideoPreviewCard(
                title: "편집 화면",
                badge: cameraManager.isRunning ? "Live" : "Stopped"
            ) {
                if studio.isBeforeFilterWindowOpen {
                    ContentUnavailableView(
                        "전체화면 창에서 재생 중",
                        systemImage: "rectangle.on.rectangle",
                        description: Text("작은 미리보기를 다시 보려면 전체화면 창을 닫으세요.")
                    )
                    .foregroundStyle(.secondary)
                } else {
                    SceneCanvasView(
                        studio: studio,
                        cameraManager: cameraManager,
                        broadcastManager: broadcastManager
                    )
                }
            } actions: {
                Button(cameraManager.isRunning ? "중지" : "시작") {
                    let shouldStart = !cameraManager.isRunning
                    cameraManager.toggle()
                    studio.statusMessage = shouldStart ? "카메라 시작을 요청했습니다." : "카메라 중지를 요청했습니다."
                }

                Button("입력 속성") {
                    studio.openCameraProperties()
                }

                Button("전체화면") {
                    ExpandedPreviewWindowController.present(
                        kind: .beforeFilter,
                        studio: studio,
                        cameraManager: cameraManager,
                        broadcastManager: broadcastManager
                    )
                }
//                Button("필터") {
//                    studio.openFilters()
//                }
            }
            .frame(minWidth: 430)

            VideoPreviewCard(
                title: "송출 화면",
                badge: studio.isResponsePreviewEnabled ? broadcastManager.responseBadge : "Off"
            ) {
                ServerResponsePreview(
                    isEnabled: studio.isResponsePreviewEnabled && isResponseVisible,
                    broadcastManager: broadcastManager,
                    allowsWebRTCPreview: true,
                    previewPortal: responsePreviewPortal
                )
            } actions: {
                Button(studio.isResponsePreviewEnabled ? "응답 끄기" : "응답 켜기") {
                    studio.toggleResponsePreview()
                }

//                Button("응답 설정") {
//                    studio.openResponseSettings()
//                }

                Button("전체화면") {
                    ExpandedPreviewWindowController.present(
                        kind: .afterFilter,
                        studio: studio,
                        cameraManager: cameraManager,
                        broadcastManager: broadcastManager,
                        responsePreviewPortal: responsePreviewPortal
                    )
                }
//                Button("진단") {
//                    studio.openServerDiagnostics()
//                }
            }
            .frame(minWidth: 430)
        }
        .padding(12)
    }
}

struct SceneCanvasView: View {
    @ObservedObject var studio: StudioViewModel
    @ObservedObject var cameraManager: CameraManager
    @ObservedObject var broadcastManager: BroadcastManager

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black

                ForEach(studio.orderedVisibleSources) { source in
                    SceneSourceLayer(
                        source: source,
                        isSelected: studio.selectedSourceID == source.id,
                        canvasSize: proxy.size,
                        studio: studio,
                        cameraManager: cameraManager,
                        broadcastManager: broadcastManager
                    )
                }

                if !cameraManager.isRunning {
                    ContentUnavailableView(
                        "카메라 중지됨",
                        systemImage: "video.slash"
                    )
                    .foregroundStyle(.secondary)
                }
            }
        }
    }
}

private struct SceneSourceLayer: View {
    let source: StudioSource
    let isSelected: Bool
    let canvasSize: CGSize
    @ObservedObject var studio: StudioViewModel
    @ObservedObject var cameraManager: CameraManager
    @ObservedObject var broadcastManager: BroadcastManager
    @State private var dragStartLayout: SourceLayout?
    @State private var resizeStartLayout: SourceLayout?
    @State private var isResizing = false

    var body: some View {
        sourceContent
            .frame(width: sourceRect.width, height: sourceRect.height)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .overlay(alignment: .bottomTrailing) {
                if isSelected && !source.isLocked {
                    resizeHandle
                        .offset(x: 8, y: 8)
                }
            }
            .overlay {
                if isSelected {
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(.yellow, lineWidth: 2)
                }
            }
            .position(x: sourceRect.midX, y: sourceRect.midY)
            .opacity(source.layout.opacity)
            .contentShape(Rectangle())
            .gesture(moveGesture, including: isResizing ? .subviews : .all)
            .onTapGesture {
                studio.selectSource(source.id)
            }
    }

    @ViewBuilder
    private var sourceContent: some View {
        switch source.kind {
        case .camera:
            CameraPreview(session: cameraManager.session)
                .opacity(cameraManager.isRunning ? 1 : 0.25)
        case .delayedResponse:
            ServerResponsePreview(
                isEnabled: studio.isResponsePreviewEnabled,
                broadcastManager: broadcastManager,
                allowsWebRTCPreview: false
            )
        case .text:
            Text(source.text.isEmpty ? source.name : source.text)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.white)
                .lineLimit(2)
                .minimumScaleFactor(0.55)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(.black.opacity(0.35))
        case .color:
            Color(hex: source.colorHex)
        case .image:
            if let assetURL = source.assetURL,
               let image = NSImage(contentsOf: assetURL) {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                SourcePlaceholder(title: "이미지 소스", systemImage: source.kind.systemImage)
            }
        case .screen:
            if let screenFrame = cameraManager.screenFrame(for: source) {
                Image(nsImage: NSImage(cgImage: screenFrame, size: .zero))
                    .resizable()
                    .scaledToFill()
            } else {
                SourcePlaceholder(title: "화면 소스", systemImage: source.kind.systemImage)
            }
        case .media:
            if let assetURL = source.assetURL,
               let image = NSImage(contentsOf: assetURL) {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                SourcePlaceholder(title: "미디어 소스", systemImage: source.kind.systemImage)
            }
        case .music:
            SourcePlaceholder(title: "뮤직 플레이리스트", systemImage: source.kind.systemImage)
        }
    }

    private var sourceRect: CGRect {
        CGRect(
            x: source.layout.x * canvasSize.width,
            y: source.layout.y * canvasSize.height,
            width: source.layout.width * canvasSize.width,
            height: source.layout.height * canvasSize.height
        )
    }

    private var moveGesture: some Gesture {
        DragGesture(minimumDistance: 1)
            .onChanged { value in
                guard !source.isLocked, !isResizing else {
                    return
                }

                if dragStartLayout == nil {
                    dragStartLayout = source.layout
                }

                var layout = dragStartLayout ?? source.layout
                layout.x += value.translation.width / max(canvasSize.width, 1)
                layout.y += value.translation.height / max(canvasSize.height, 1)
                studio.updateSourceLayout(source.id, layout: layout)
            }
            .onEnded { _ in
                dragStartLayout = nil
            }
    }

    private var resizeHandle: some View {
        Circle()
            .fill(.yellow)
            .frame(width: 14, height: 14)
            .overlay(Circle().stroke(.black.opacity(0.65), lineWidth: 1))
            .gesture(
                DragGesture(minimumDistance: 1)
                    .onChanged { value in
                        isResizing = true
                        if resizeStartLayout == nil {
                            resizeStartLayout = source.layout
                        }

                        var layout = resizeStartLayout ?? source.layout
                        layout.width += value.translation.width / max(canvasSize.width, 1)
                        layout.height += value.translation.height / max(canvasSize.height, 1)
                        studio.updateSourceLayout(source.id, layout: layout)
                    }
                    .onEnded { _ in
                        resizeStartLayout = nil
                        isResizing = false
                    }
            )
    }
}

private struct SourcePlaceholder: View {
    let title: String
    let systemImage: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: systemImage)
                .font(.title2)
            Text(title)
                .font(.caption)
                .lineLimit(1)
        }
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(nsColor: .controlBackgroundColor))
        .overlay {
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color(nsColor: .separatorColor), lineWidth: 1)
        }
    }
}

struct ServerResponsePreview: View {
    let isEnabled: Bool
    @ObservedObject var broadcastManager: BroadcastManager
    let allowsWebRTCPreview: Bool
    let previewPortal: WebRTCResponsePreviewPortal?
    let placement: WebRTCResponsePreviewPlacement

    init(
        isEnabled: Bool,
        broadcastManager: BroadcastManager,
        allowsWebRTCPreview: Bool,
        previewPortal: WebRTCResponsePreviewPortal? = nil,
        placement: WebRTCResponsePreviewPlacement = .inline
    ) {
        self.isEnabled = isEnabled
        self.broadcastManager = broadcastManager
        self.allowsWebRTCPreview = allowsWebRTCPreview
        self.previewPortal = previewPortal
        self.placement = placement
    }

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            if isEnabled,
               allowsWebRTCPreview,
               broadcastManager.hasWebRTCResponsePreview,
               let previewPortal {
                WebRTCResponsePlayer(
                    broadcastManager: broadcastManager,
                    previewPortal: previewPortal,
                    placement: placement
                )
                    .background(Color.black)
            } else {
                ServerResponsePlaceholder(
                    isEnabled: isEnabled,
                    statusText: broadcastManager.mediaUplinkStatusText
                )
            }

        }
    }
}

enum WebRTCResponsePreviewPlacement {
    case inline
    case fullscreen
}

@MainActor
final class WebRTCResponsePreviewPortal {
    private let contentView: NSView
    private weak var inlineContainerView: NSView?
    private weak var fullscreenContainerView: NSView?
    private weak var broadcastManager: BroadcastManager?

    init() {
        let contentView = NSView()
        contentView.wantsLayer = true
        contentView.layer?.backgroundColor = NSColor.black.cgColor
        self.contentView = contentView
    }

    func attachInline(to containerView: NSView, broadcastManager: BroadcastManager) {
        inlineContainerView = containerView
        bind(to: broadcastManager)

        if fullscreenContainerView == nil {
            moveContent(to: containerView)
        }
    }

    func detachInline(from containerView: NSView) {
        guard inlineContainerView === containerView else {
            return
        }

        inlineContainerView = nil
        guard fullscreenContainerView == nil else {
            return
        }

        if contentView.superview === containerView {
            contentView.removeFromSuperview()
        }
        detachPreviewIfUnhosted()
    }

    func attachFullscreen(to containerView: NSView, broadcastManager: BroadcastManager) {
        fullscreenContainerView = containerView
        bind(to: broadcastManager)
        moveContent(to: containerView)
    }

    func detachFullscreen(from containerView: NSView) {
        guard fullscreenContainerView === containerView else {
            return
        }

        fullscreenContainerView = nil
        if let inlineContainerView {
            moveContent(to: inlineContainerView)
        } else {
            contentView.removeFromSuperview()
            detachPreviewIfUnhosted()
        }
    }

    private func bind(to broadcastManager: BroadcastManager) {
        if self.broadcastManager !== broadcastManager,
           let previousBroadcastManager = self.broadcastManager {
            previousBroadcastManager.detachWebRTCResponsePreview(from: contentView)
        }

        self.broadcastManager = broadcastManager
    }

    private func moveContent(to containerView: NSView) {
        configure(containerView)

        if contentView.superview !== containerView {
            contentView.removeFromSuperview()
            containerView.addSubview(contentView)
        }

        contentView.translatesAutoresizingMaskIntoConstraints = true
        contentView.autoresizingMask = [.width, .height]
        contentView.frame = containerView.bounds
        broadcastManager?.attachWebRTCResponsePreview(to: contentView)
    }

    private func detachPreviewIfUnhosted() {
        guard contentView.superview == nil else {
            return
        }

        broadcastManager?.detachWebRTCResponsePreview(from: contentView)
    }

    private func configure(_ containerView: NSView) {
        containerView.wantsLayer = true
        containerView.layer?.backgroundColor = NSColor.black.cgColor
    }
}

private struct ServerResponsePlaceholder: View {
    let isEnabled: Bool
    let statusText: String

    var body: some View {
        ContentUnavailableView(
            isEnabled ? "서버 처리 영상 대기 중" : "송출 화면 꺼짐",
            systemImage: isEnabled ? "server.rack" : "video.slash",
            description: Text(isEnabled ? statusText : "응답 켜기를 누르면 서버 처리 영상을 표시합니다.")
        )
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }
}

private struct WebRTCResponsePlayer: NSViewRepresentable {
    @ObservedObject var broadcastManager: BroadcastManager
    let previewPortal: WebRTCResponsePreviewPortal
    let placement: WebRTCResponsePreviewPlacement

    func makeCoordinator() -> Coordinator {
        Coordinator(
            broadcastManager: broadcastManager,
            previewPortal: previewPortal,
            placement: placement
        )
    }

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.wantsLayer = true
        view.layer?.backgroundColor = NSColor.black.cgColor
        attach(view)
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        attach(nsView)
    }

    static func dismantleNSView(_ nsView: NSView, coordinator: Coordinator) {
        switch coordinator.placement {
        case .inline:
            coordinator.previewPortal.detachInline(from: nsView)
        case .fullscreen:
            coordinator.previewPortal.detachFullscreen(from: nsView)
        }
    }

    private func attach(_ nsView: NSView) {
        switch placement {
        case .inline:
            previewPortal.attachInline(to: nsView, broadcastManager: broadcastManager)
        case .fullscreen:
            previewPortal.attachFullscreen(to: nsView, broadcastManager: broadcastManager)
        }
    }

    final class Coordinator {
        let broadcastManager: BroadcastManager
        let previewPortal: WebRTCResponsePreviewPortal
        let placement: WebRTCResponsePreviewPlacement

        init(
            broadcastManager: BroadcastManager,
            previewPortal: WebRTCResponsePreviewPortal,
            placement: WebRTCResponsePreviewPlacement
        ) {
            self.broadcastManager = broadcastManager
            self.previewPortal = previewPortal
            self.placement = placement
        }
    }
}

private extension Color {
    init(hex: String) {
        let sanitized = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        guard sanitized.count == 6,
              let value = Int(sanitized, radix: 16) else {
            self = .accentColor
            return
        }

        let red = Double((value >> 16) & 0xff) / 255
        let green = Double((value >> 8) & 0xff) / 255
        let blue = Double(value & 0xff) / 255
        self = Color(red: red, green: green, blue: blue)
    }
}

struct VideoPreviewCard<Content: View, Actions: View>: View {
    let title: String
    let badge: String
    private let content: Content
    private let actions: Actions

    init(
        title: String,
        badge: String,
        @ViewBuilder content: () -> Content,
        @ViewBuilder actions: () -> Actions
    ) {
        self.title = title
        self.badge = badge
        self.content = content()
        self.actions = actions()
    }

    var body: some View {
        GroupBox {
            VStack(spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.headline)
                    }

                    Spacer()

                    Text(badge)
                        .font(.caption)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(.quaternary, in: Capsule())
                }

                content
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .scaleEffect(previewCropScale)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .overlay {
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color(nsColor: .separatorColor), lineWidth: 1)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                HStack {
                    actions
                    Spacer()
                }
            }
            .padding(4)
        }
    }
}
