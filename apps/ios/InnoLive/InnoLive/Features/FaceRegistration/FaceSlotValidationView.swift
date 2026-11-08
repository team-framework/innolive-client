#if DEBUG
import SwiftUI

/// `--face-slot-preview=1` 또는 `=2`로 실행하면 Spark(face_slots=2) 요금제와 등록 얼굴 n명으로 얼굴 관리 화면을 연다.
/// 로그인하지 않으며, 이 경로의 URLSession.shared 요청은 모두 앱 안에서 응답해 서버에 닿지 않는다.
nonisolated enum FaceSlotPreviewFixture {
    static let accessToken = "face-slot-preview-access"
    private static let argumentPrefix = "--face-slot-preview="

    static var registeredFaceCount: Int? {
        ProcessInfo.processInfo.arguments.lazy
            .compactMap { $0.hasPrefix(argumentPrefix) ? Int($0.dropFirst(argumentPrefix.count)) : nil }
            .first { (1...2).contains($0) }
    }

    static func response(method: String?, path: String, registeredFaceCount: Int) -> (status: Int, json: String) {
        switch (method, path) {
        case ("GET", "/users/me/plan"):
            return (200, #"{"plan":"spark","monthly_broadcast_seconds":0,"monthly_device_seconds":18000,"max_per_broadcast_seconds":3600,"allowed_modes":[],"device_modes":["720p_single"],"face_slots":2}"#)
        case ("GET", "/users/me/usage"):
            return (200, #"{"plan":"spark","limit_seconds":null,"used_seconds":0,"remaining_seconds":null,"device_limit_seconds":18000,"device_used_seconds":3600,"device_remaining_seconds":14400,"available_by_mode":[{"mode":"720p_single","multiplier":1,"seconds":null,"allowed":false},{"mode":"fhd_single","multiplier":2,"seconds":null,"allowed":false},{"mode":"720p_multi","multiplier":2,"seconds":null,"allowed":false},{"mode":"fhd_multi","multiplier":3,"seconds":null,"allowed":false}]}"#)
        case ("GET", "/reference-face"):
            let faces = (1...registeredFaceCount).map {
                #"{"face_id":"preview-face-\#($0)","name":"슬롯 얼굴 \#($0)","registered_at":"2026-11-0\#($0)T00:00:00Z"}"#
            }
            return (200, #"{"registered":true,"source":"api","registered_at":"2026-11-01T00:00:00Z","client_id":"preview-user","count":\#(registeredFaceCount),"faces":[\#(faces.joined(separator: ","))]}"#)
        default:
            return (404, #"{"error":{"code":"face_slot_preview","message":"The face slot preview only answers plan and face list reads."}}"#)
        }
    }
}

nonisolated final class FaceSlotPreviewURLProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let count = FaceSlotPreviewFixture.registeredFaceCount else {
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
            return
        }
        let stub = FaceSlotPreviewFixture.response(method: request.httpMethod, path: url.path, registeredFaceCount: count)
        let response = HTTPURLResponse(url: url, statusCode: stub.status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(stub.json.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private struct FaceSlotPreviewTokenStore: AuthenticationTokenStoring {
    func save(_ tokens: AuthenticationTokenPair) throws {}
    func load() -> AuthenticationTokenPair? {
        AuthenticationTokenPair(accessToken: FaceSlotPreviewFixture.accessToken, refreshToken: "face-slot-preview-refresh")
    }
    func remove() {}
}

struct FaceSlotValidationView: View {
    private static let interceptNetwork: Void = { _ = URLProtocol.registerClass(FaceSlotPreviewURLProtocol.self) }()

    @StateObject private var authentication: AuthSession
    @StateObject private var youtube: YouTubeIntegration
    @State private var isPlanLoaded = false

    init() {
        _ = Self.interceptNetwork
        let defaults = UserDefaults(suiteName: "face-slot-preview")!
        let consentStore = ConsentAcknowledgementStore(userDefaults: defaults)
        _authentication = StateObject(wrappedValue: AuthSession(
            api: AuthenticationAPI(serverURLProvider: { _ in nil }),
            tokenStore: FaceSlotPreviewTokenStore(),
            consentStore: consentStore
        ))
        _youtube = StateObject(wrappedValue: YouTubeIntegration(
            preferencesStore: YouTubePreferencesStore(userDefaults: defaults),
            api: YouTubeAPI(serverURLProvider: { URL(string: "https://face-slots.invalid\($0)") }),
            consentStore: consentStore,
            persistAIProcessingMode: { _ in },
            planStore: PlanStore(cacheURL: nil)
        ))
    }

    var body: some View {
        NavigationStack {
            // 슬롯 안내는 요금제를 읽어 계산하므로 요금제를 받은 뒤에 화면을 만든다.
            if isPlanLoaded {
                FaceManagementView(authentication: authentication, youtube: youtube, mode: .server)
            } else {
                ProgressView().task {
                    await youtube.refreshPlan(accessToken: authentication.currentAccessToken())
                    isPlanLoaded = youtube.planStore.snapshot != nil
                }
            }
        }
    }
}
#endif
