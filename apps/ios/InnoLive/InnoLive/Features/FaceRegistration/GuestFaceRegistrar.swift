import Combine
import Foundation

/// Registers a tapped guest in the background so the home screen can return to the broadcast at once.
@MainActor
final class GuestFaceRegistrar: ObservableObject {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    enum Status: Equatable {
        case registering
        case registered
        /// `canRetry` is false when the face itself could not be prepared; pick it again instead.
        case failed(message: String, canRetry: Bool)
    }

    @Published private(set) var status: Status?

    private struct Request {
        let name: String
        let mode: AIProcessingMode
        let authentication: AuthSession
    }

    private let detector = FaceDetectionService()
    private var request: Request?
    private var jpegData: Data?
    private var task: Task<Void, Never>?

    func register(
        frame: FaceSelectionFrame,
        faceIndex: Int,
        name: String,
        mode: AIProcessingMode,
        authentication: AuthSession
    ) {
        task?.cancel()
        request = Request(name: name, mode: mode, authentication: authentication)
        jpegData = nil
        status = .registering
        let detector = detector
        task = Task { [weak self] in
            // Build the JPEG once and drop the frame, so no camera buffer stays retained.
            let data = await Task.detached(priority: .userInitiated) {
                detector.registrationJPEG(from: frame, faceIndex: faceIndex)
            }.value
            guard let self, !Task.isCancelled else { return }
            guard let data else {
                self.status = .failed(
                    message: String(localized: "선택한 얼굴을 확인하지 못했습니다. 다시 선택해 주세요."),
                    canRetry: false
                )
                return
            }
            self.jpegData = data
            await self.upload()
        }
    }

    func retry() {
        guard case .failed(_, true) = status, jpegData != nil else { return }
        task?.cancel()
        status = .registering
        task = Task { [weak self] in await self?.upload() }
    }

    func dismiss() {
        task?.cancel()
        task = nil
        request = nil
        jpegData = nil
        status = nil
    }

    private func upload() async {
        guard let request, let jpegData else { return }
        do {
            if request.mode == .server {
                _ = try await ReferenceFaceAPI(authentication: request.authentication)
                    .register(jpegData: jpegData, name: request.name)
            } else {
                _ = try await LocalFaceRegistrationStore.shared.register(jpegData: jpegData, name: request.name)
            }
            guard !Task.isCancelled else { return }
            self.jpegData = nil
            self.request = nil
            status = .registered
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled, status == .registered else { return }
            status = nil
        } catch {
            guard !Task.isCancelled else { return }
            status = .failed(
                message: (error as? ReferenceFaceAPIError)?.userMessage
                    ?? (error as? LocalizedError)?.errorDescription
                    ?? String(localized: "얼굴 관리 요청을 처리하지 못했습니다. 다시 시도해 주세요."),
                canRetry: true
            )
        }
    }
}
