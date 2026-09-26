import AuthenticationServices
import Combine
import SwiftUI

/// Uses Apple's native button without starting authorization before consent.
struct ConsentAppleSignInButton: UIViewRepresentable {
    let style: ASAuthorizationAppleIDButton.Style
    let action: () -> Void

    func makeUIView(context: Context) -> ASAuthorizationAppleIDButton {
        let button = ASAuthorizationAppleIDButton(type: .signIn, style: style)
        button.addTarget(context.coordinator, action: #selector(Coordinator.tap), for: .touchUpInside)
        return button
    }

    func updateUIView(_ uiView: ASAuthorizationAppleIDButton, context: Context) {
        context.coordinator.action = action
        uiView.isEnabled = context.environment.isEnabled
    }

    func makeCoordinator() -> Coordinator { Coordinator(action: action) }

    final class Coordinator: NSObject {
        // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
        nonisolated deinit {}

        var action: () -> Void
        init(action: @escaping () -> Void) { self.action = action }
        @objc func tap() { action() }
    }
}

@MainActor
final class AppleSignInAuthorization: NSObject, ObservableObject,
    ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private var controller: ASAuthorizationController?
    private var anchor: ASPresentationAnchor?
    private var completion: ((Result<ASAuthorizationAppleIDCredential, Error>) -> Void)?

    func start(nonce: String, window: UIWindow, completion: @escaping (Result<ASAuthorizationAppleIDCredential, Error>) -> Void) {
        guard controller == nil else { return }
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.nonce = nonce
        request.requestedScopes = [.fullName, .email]
        let controller = ASAuthorizationController(authorizationRequests: [request])
        self.controller = controller
        self.anchor = window
        self.completion = completion
        controller.delegate = self
        controller.presentationContextProvider = self
        controller.performRequests()
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        // The window is retained for the lifetime of this authorization request.
        guard let anchor else {
            preconditionFailure("Apple authorization requires a presentation window")
        }
        return anchor
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
            finish(.failure(ASAuthorizationError(.failed)))
            return
        }
        finish(.success(credential))
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        finish(.failure(error))
    }

    private func finish(_ result: Result<ASAuthorizationAppleIDCredential, Error>) {
        let callback = completion
        completion = nil
        controller = nil
        anchor = nil
        callback?(result)
    }
}
