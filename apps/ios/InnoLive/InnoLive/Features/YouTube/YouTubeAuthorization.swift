import Foundation
import GoogleSignIn
import UIKit

final class YouTubeAuthorization {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    func authorize(configuration: YouTubeConfiguration, presenting viewController: UIViewController) async throws -> String {
        guard let clientID = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String,
              !clientID.isEmpty else {
            throw YouTubeAPIError.configuration
        }

        GIDSignIn.sharedInstance.configuration = GIDConfiguration(
            clientID: clientID,
            serverClientID: configuration.webClientID
        )
        let result = try await GIDSignIn.sharedInstance.signIn(
            withPresenting: viewController,
            hint: nil,
            additionalScopes: [configuration.scope]
        )
        guard let code = result.serverAuthCode, !code.isEmpty else {
            throw YouTubeAPIError.authorizationCodeMissing
        }
        return code
    }
}
