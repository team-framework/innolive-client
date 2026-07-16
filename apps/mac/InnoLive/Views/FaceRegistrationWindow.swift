//
//  FaceRegistrationWindow.swift
//  InnoLive
//
//  Created by Codex on 6/19/26.
//

import AppKit
import Foundation
import SwiftUI
import Vision

final class FaceRegistrationWindowController: NSWindowController {
    init(cameraManager: CameraManager, registrationURL: URL) {
        let rootView = FaceRegistrationView(
            cameraManager: cameraManager,
            registrationURL: registrationURL
        )
        let hostingController = NSHostingController(rootView: rootView)
        let window = NSWindow(contentViewController: hostingController)

        window.title = "얼굴 관리"
        window.setContentSize(NSSize(width: 440, height: 720))
        window.minSize = NSSize(width: 380, height: 620)
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.isReleasedWhenClosed = false
        window.center()

        super.init(window: window)
    }

    required init?(coder: NSCoder) {
        nil
    }
}

private struct FaceRegistrationView: View {
    @ObservedObject var cameraManager: CameraManager
    let registrationURL: URL

    @State private var registrationState: FaceRegistrationState = .waitingForFrame
    @State private var referenceStatus: ReferenceFaceStatusResponse?
    @State private var isStatusLoading = false
    @State private var isDeletingAll = false
    @State private var deletingFaceID: String?
    @State private var shouldAutoRegisterWhenEmpty = false
    @State private var retryID = UUID()

    var body: some View {
        ZStack {
            Color.black
                .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 24) {
                    if isCompleted {
                        VStack(spacing: 16) {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 72, weight: .semibold))
                                .foregroundStyle(.green)

                            Text("얼굴이 등록되었어요")
                                .font(.system(size: 22, weight: .semibold))
                                .foregroundStyle(.white)
                        }
                        .frame(width: 300, height: 300)
                    } else {
                        ZStack {
                            CameraPreview(session: cameraManager.session)
                                .opacity(cameraManager.isRunning ? 1 : 0.35)
                                .clipShape(Circle())
                                .overlay {
                                    Circle()
                                        .stroke(Color.white.opacity(0.18), lineWidth: 2)
                                }

                            FaceGuideOverlay()

                            if !cameraManager.isRunning {
                                Label("카메라 준비 중", systemImage: "video")
                                    .font(.caption)
                                    .foregroundStyle(.white.opacity(0.8))
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 8)
                                    .background(.black.opacity(0.55), in: Capsule())
                            }
                        }
                        .frame(width: 300, height: 300)

                        VStack(spacing: 12) {
                            if registrationState.isInProgress {
                                ProgressView()
                                    .controlSize(.small)
                                    .tint(.white)
                            } else if registrationState.isFailed {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .font(.system(size: 24, weight: .semibold))
                                    .foregroundStyle(.yellow)
                            }

                            Text(registrationState.title)
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundStyle(.white)

                            Text(registrationState.message)
                                .font(.callout)
                                .foregroundStyle(.white.opacity(0.68))
                                .multilineTextAlignment(.center)

                            if registrationState.isFailed {
                                Button("다시 시도") {
                                    shouldAutoRegisterWhenEmpty = true
                                    retryID = UUID()
                                }
                                .buttonStyle(.borderedProminent)
                            } else if registrationState == .readyToRegister {
                                Button("등록 시작") {
                                    shouldAutoRegisterWhenEmpty = true
                                    retryID = UUID()
                                }
                                .buttonStyle(.borderedProminent)
                            }
                        }
                    }

                    if let referenceStatus {
                        ReferenceFaceStatusPanel(
                            status: referenceStatus,
                            isRefreshing: isStatusLoading,
                            isDeletingAll: isDeletingAll,
                            deletingFaceID: deletingFaceID,
                            onRefresh: {
                                Task {
                                    await refreshStatus()
                                }
                            },
                            onDeleteAll: {
                                Task {
                                    await deleteAllReferenceFaces()
                                }
                            },
                            onDeleteFace: { faceID in
                                Task {
                                    await deleteReferenceFace(faceID: faceID)
                                }
                            }
                        )
                        .frame(maxWidth: 320)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(32)
            }
        }
        .task(id: retryID) {
            await loadStatusAndRegisterIfNeeded()
        }
    }

    private var isCompleted: Bool {
        registrationState == .completed
    }

    private func loadStatusAndRegisterIfNeeded() async {
        registrationState = .loadingStatus

        do {
            let status = try await ReferenceFaceClient.status(from: registrationURL)
            guard !Task.isCancelled else {
                return
            }

            referenceStatus = status
            if status.registered {
                registrationState = .completed
                return
            }

            guard shouldAutoRegisterWhenEmpty else {
                registrationState = .readyToRegister
                return
            }

            await registerDetectedFace()
        } catch is CancellationError {
            return
        } catch {
            registrationState = .failed(error.localizedDescription)
        }
    }

    private func registerDetectedFace() async {
        registrationState = .waitingForFrame

        while !Task.isCancelled {
            guard let frame = cameraManager.currentLiveFrame() else {
                registrationState = .waitingForFrame
                await sleep(interval: 0.2)
                continue
            }

            do {
                let registrationImage = try FaceRegistrationImageProcessor.registrationImage(from: frame)
                registrationState = .scanning
                let hasFace = try await FaceRegistrationImageProcessor.containsFace(in: registrationImage)
                guard !Task.isCancelled else {
                    return
                }

                guard hasFace else {
                    await sleep(interval: 0.35)
                    continue
                }

                registrationState = .uploading
                let status = try await ReferenceFaceClient.register(image: registrationImage, to: registrationURL)
                guard !Task.isCancelled else {
                    return
                }

                referenceStatus = status
                registrationState = .completed
                return
            } catch is CancellationError {
                return
            } catch {
                registrationState = .failed(error.localizedDescription)
                return
            }
        }
    }

    private func sleep(interval: TimeInterval) async {
        do {
            try await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
        } catch {
            return
        }
    }

    private func refreshStatus() async {
        isStatusLoading = true
        defer {
            isStatusLoading = false
        }

        do {
            let status = try await ReferenceFaceClient.status(from: registrationURL)
            guard !Task.isCancelled else {
                return
            }

            referenceStatus = status
            registrationState = status.registered ? .completed : .readyToRegister
            shouldAutoRegisterWhenEmpty = false
        } catch is CancellationError {
            return
        } catch {
            registrationState = .failed(error.localizedDescription)
        }
    }

    private func deleteAllReferenceFaces() async {
        isDeletingAll = true
        defer {
            isDeletingAll = false
        }

        do {
            try await ReferenceFaceClient.deleteAll(from: registrationURL)
            guard !Task.isCancelled else {
                return
            }

            shouldAutoRegisterWhenEmpty = false
            await refreshStatus()
        } catch is CancellationError {
            return
        } catch {
            registrationState = .failed(error.localizedDescription)
        }
    }

    private func deleteReferenceFace(faceID: String) async {
        deletingFaceID = faceID
        defer {
            deletingFaceID = nil
        }

        do {
            try await ReferenceFaceClient.delete(faceID: faceID, from: registrationURL)
            guard !Task.isCancelled else {
                return
            }

            shouldAutoRegisterWhenEmpty = false
            await refreshStatus()
        } catch is CancellationError {
            return
        } catch {
            registrationState = .failed(error.localizedDescription)
        }
    }
}

private enum FaceRegistrationState: Equatable {
    case loadingStatus
    case waitingForFrame
    case scanning
    case uploading
    case completed
    case readyToRegister
    case failed(String)

    var title: String {
        switch self {
        case .loadingStatus:
            "등록 상태 조회 중"
        case .waitingForFrame:
            "카메라 프레임 대기 중"
        case .scanning:
            "얼굴 인식 중"
        case .uploading:
            "얼굴 등록 중"
        case .completed:
            "얼굴 등록 완료"
        case .readyToRegister:
            "등록된 얼굴 없음"
        case .failed:
            "얼굴 등록 실패"
        }
    }

    var message: String {
        switch self {
        case .loadingStatus:
            "서버에 등록된 기준 얼굴 상태를 확인하고 있습니다"
        case .waitingForFrame:
            "카메라가 실제 영상을 전달할 때까지 기다려주세요"
        case .scanning:
            "얼굴을 원 안에 맞춰주세요"
        case .uploading:
            "감지된 얼굴 이미지를 서버에 등록하고 있습니다"
        case .completed:
            "등록된 얼굴은 블러 제외 대상으로 사용됩니다"
        case .readyToRegister:
            "등록 시작을 누르면 카메라에서 얼굴을 감지해 등록합니다"
        case .failed(let message):
            message
        }
    }

    var isInProgress: Bool {
        switch self {
        case .loadingStatus, .waitingForFrame, .scanning, .uploading:
            true
        case .completed, .readyToRegister, .failed:
            false
        }
    }

    var isFailed: Bool {
        if case .failed = self {
            return true
        }

        return false
    }
}

private enum FaceRegistrationImageProcessor {
    private static let registrationImageSideLength = 500

    static func registrationImage(from image: CGImage) throws -> CGImage {
        guard image.width >= registrationImageSideLength,
              image.height >= registrationImageSideLength else {
            throw FaceRegistrationImageProcessingError.imageTooSmall
        }

        let cropRect = CGRect(
            x: (image.width - registrationImageSideLength) / 2,
            y: (image.height - registrationImageSideLength) / 2,
            width: registrationImageSideLength,
            height: registrationImageSideLength
        )

        guard let croppedImage = image.cropping(to: cropRect) else {
            throw FaceRegistrationImageProcessingError.imageCroppingFailed
        }

        return croppedImage
    }

    static func containsFace(in image: CGImage) async throws -> Bool {
        try await Task.detached(priority: .userInitiated) {
            let request = VNDetectFaceRectanglesRequest()
            let handler = VNImageRequestHandler(cgImage: image, orientation: .up)
            try handler.perform([request])
            return !(request.results?.isEmpty ?? true)
        }.value
    }
}

private enum FaceRegistrationImageProcessingError: LocalizedError {
    case imageTooSmall
    case imageCroppingFailed

    var errorDescription: String? {
        switch self {
        case .imageTooSmall:
            "얼굴 등록에는 최소 500 x 500 크기의 카메라 프레임이 필요합니다."
        case .imageCroppingFailed:
            "얼굴 등록용 500 x 500 이미지를 생성하지 못했습니다."
        }
    }
}

private enum ReferenceFaceClient {
    static func status(from url: URL) async throws -> ReferenceFaceStatusResponse {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data, action: .status)
        return try JSONDecoder().decode(ReferenceFaceStatusResponse.self, from: data)
    }

    static func register(image: CGImage, to url: URL) async throws -> ReferenceFaceStatusResponse {
        guard let imageData = jpegData(from: image) else {
            throw ReferenceFaceRequestError.imageEncodingFailed
        }

        let boundary = "Boundary-\(UUID().uuidString)"
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        let body = multipartBody(
            imageData: imageData,
            boundary: boundary,
            fieldName: "image",
            fileName: "reference-face.jpg"
        )
        let (data, response) = try await URLSession.shared.upload(for: request, from: body)

        try validate(response: response, data: data, action: .register)
        let status = try JSONDecoder().decode(ReferenceFaceStatusResponse.self, from: data)
        guard status.registered else {
            throw ReferenceFaceRequestError.notRegistered
        }

        return status
    }

    static func deleteAll(from url: URL) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data, action: .deleteAll)
    }

    static func delete(faceID: String, from url: URL) async throws {
        var request = URLRequest(url: url.appendingPathComponent(faceID))
        request.httpMethod = "DELETE"

        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response: response, data: data, action: .deleteOne)
    }

    private static func jpegData(from image: CGImage) -> Data? {
        NSBitmapImageRep(cgImage: image).representation(
            using: .jpeg,
            properties: [.compressionFactor: 0.9]
        )
    }

    private static func multipartBody(
        imageData: Data,
        boundary: String,
        fieldName: String,
        fileName: String
    ) -> Data {
        var body = Data()
        body.appendUTF8("--\(boundary)\r\n")
        body.appendUTF8("Content-Disposition: form-data; name=\"\(fieldName)\"; filename=\"\(fileName)\"\r\n")
        body.appendUTF8("Content-Type: image/jpeg\r\n\r\n")
        body.append(imageData)
        body.appendUTF8("\r\n")
        body.appendUTF8("--\(boundary)--\r\n")
        return body
    }

    private static func validate(
        response: URLResponse,
        data: Data,
        action: ReferenceFaceRequestAction
    ) throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw ReferenceFaceRequestError.invalidResponse
        }

        guard (200..<300).contains(httpResponse.statusCode) else {
            throw ReferenceFaceRequestError.serverRejected(
                action: action,
                statusCode: httpResponse.statusCode,
                data: data
            )
        }
    }
}

private struct ReferenceFaceStatusResponse: Decodable, Equatable {
    let registered: Bool
    let source: String?
    let registeredAt: String?
    let clientID: String?
    let count: Int?
    let faces: [ReferenceFaceRecord]

    var displayCount: Int {
        count ?? faces.count
    }

    private enum CodingKeys: String, CodingKey {
        case registered
        case source
        case registeredAt = "registered_at"
        case clientID = "client_id"
        case count
        case faces
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        registered = try container.decode(Bool.self, forKey: .registered)
        source = try container.decodeIfPresent(String.self, forKey: .source)
        registeredAt = try container.decodeIfPresent(String.self, forKey: .registeredAt)
        clientID = try container.decodeIfPresent(String.self, forKey: .clientID)
        count = try container.decodeIfPresent(Int.self, forKey: .count)
        faces = try container.decodeIfPresent([ReferenceFaceRecord].self, forKey: .faces) ?? []
    }
}

private struct ReferenceFaceRecord: Decodable, Equatable, Identifiable {
    let faceID: String
    let registeredAt: String?

    var id: String {
        faceID
    }

    private enum CodingKeys: String, CodingKey {
        case faceID = "face_id"
        case registeredAt = "registered_at"
    }
}

private enum ReferenceFaceRequestAction {
    case status
    case register
    case deleteAll
    case deleteOne

    var failureMessage: String {
        switch self {
        case .status:
            "서버 조회 요청이 실패했습니다."
        case .register:
            "서버 등록 요청이 실패했습니다."
        case .deleteAll, .deleteOne:
            "서버 삭제 요청이 실패했습니다."
        }
    }
}

private enum ReferenceFaceRequestError: LocalizedError {
    case imageEncodingFailed
    case invalidResponse
    case serverRejected(action: ReferenceFaceRequestAction, statusCode: Int, data: Data)
    case notRegistered

    var errorDescription: String? {
        switch self {
        case .imageEncodingFailed:
            "캡처한 얼굴 이미지를 JPEG로 변환하지 못했습니다."
        case .invalidResponse:
            "서버 응답을 확인할 수 없습니다."
        case .serverRejected(let action, let statusCode, let data):
            serverMessage(from: data) ?? "\(action.failureMessage) HTTP \(statusCode)"
        case .notRegistered:
            "서버가 얼굴 등록 완료 상태를 반환하지 않았습니다."
        }
    }

    private func serverMessage(from data: Data) -> String? {
        guard let payload = try? JSONDecoder().decode(ServerErrorPayload.self, from: data) else {
            return nil
        }

        return payload.detail
    }

    private struct ServerErrorPayload: Decodable {
        let detail: String?
    }
}

private struct ReferenceFaceStatusPanel: View {
    let status: ReferenceFaceStatusResponse
    let isRefreshing: Bool
    let isDeletingAll: Bool
    let deletingFaceID: String?
    let onRefresh: () -> Void
    let onDeleteAll: () -> Void
    let onDeleteFace: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(
                    status.registered ? "기준 얼굴 \(status.displayCount)장 등록됨" : "기준 얼굴 없음",
                    systemImage: status.registered ? "checkmark.shield.fill" : "person.crop.circle.badge.questionmark"
                )
                .font(.headline)
                .foregroundStyle(.white)

                Spacer()

                Button {
                    onRefresh()
                } label: {
                    Label("새로고침", systemImage: "arrow.clockwise")
                }
                .labelStyle(.iconOnly)
                .disabled(isRefreshing || isDeletingAll || deletingFaceID != nil)
            }

            if status.faces.isEmpty {
                Text(status.registered ? "서버가 개별 얼굴 목록을 반환하지 않았습니다." : "등록 시작 후 블러 제외 기준 얼굴로 사용됩니다.")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.68))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(status.faces) { face in
                            HStack(spacing: 10) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(face.faceID)
                                        .font(.caption)
                                        .foregroundStyle(.white)
                                        .lineLimit(1)
                                        .truncationMode(.middle)

                                    if let registeredAt = face.registeredAt {
                                        Text(registeredAt)
                                            .font(.caption2)
                                            .foregroundStyle(.white.opacity(0.58))
                                            .lineLimit(1)
                                    }
                                }

                                Spacer()

                                Button(role: .destructive) {
                                    onDeleteFace(face.faceID)
                                } label: {
                                    Label("삭제", systemImage: "trash")
                                }
                                .labelStyle(.iconOnly)
                                .disabled(isRefreshing || isDeletingAll || deletingFaceID != nil)
                            }
                            .padding(.vertical, 6)
                            .padding(.horizontal, 8)
                            .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                        }
                    }
                }
                .frame(maxHeight: 118)
            }

            if status.registered {
                Button(role: .destructive) {
                    onDeleteAll()
                } label: {
                    Label(isDeletingAll ? "삭제 중" : "전체 삭제", systemImage: "trash")
                }
                .buttonStyle(.bordered)
                .disabled(isRefreshing || isDeletingAll || deletingFaceID != nil)
            }
        }
        .padding(14)
        .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
    }
}

private extension Data {
    mutating func appendUTF8(_ string: String) {
        guard let data = string.data(using: .utf8) else {
            return
        }

        append(data)
    }
}

private struct FaceGuideOverlay: View {
    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.green, style: StrokeStyle(lineWidth: 3, lineCap: .round, dash: [2, 8]))

            Circle()
                .stroke(Color.green.opacity(0.35), lineWidth: 18)
                .blur(radius: 12)

            GuideCrosshair()
                .stroke(Color.cyan.opacity(0.65), lineWidth: 1.5)
        }
        .padding(4)
    }
}

private struct GuideCrosshair: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let center = CGPoint(x: rect.midX, y: rect.midY)

        path.move(to: CGPoint(x: rect.minX + 24, y: center.y))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX - 24, y: center.y),
            control: CGPoint(x: center.x, y: center.y + rect.height * 0.12)
        )

        path.move(to: CGPoint(x: center.x, y: rect.minY + 18))
        path.addLine(to: CGPoint(x: center.x, y: rect.maxY - 18))

        return path
    }
}
