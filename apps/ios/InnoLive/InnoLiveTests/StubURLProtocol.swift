import Foundation

// 테스트 URLSession의 요청을 기록하고 준비한 응답을 돌려준다.
// static 상태는 하위 클래스마다 따로 보관한다. 스위트마다 빈 하위 클래스를 선언하면
// 늦게 도착한 다른 스위트의 요청이 기록이나 응답 큐를 건드리지 않는다.
nonisolated class StubURLProtocol: URLProtocol {
    // iOS 18의 isolated deinit 런타임 오류를 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    nonisolated struct Response: Sendable {
        var status: Int
        var body: Data
        /// 하위 클래스의 `defaultHeaders`에 덧붙일 헤더.
        var headers: [String: String]
        var delay: Duration
        /// true면 `resumeHold()`를 호출할 때까지 응답을 보내지 않는다.
        var hold: Bool
        /// 값이 있으면 HTTP 응답 대신 이 전송 오류로 요청을 끝낸다.
        var failure: URLError.Code?

        init(
            status: Int,
            body: Data = Data(),
            headers: [String: String] = [:],
            delay: Duration = .zero,
            hold: Bool = false
        ) {
            self.status = status
            self.body = body
            self.headers = headers
            self.delay = delay
            self.hold = hold
            failure = nil
        }

        init(
            status: Int,
            json: String,
            headers: [String: String] = [:],
            delay: Duration = .zero,
            hold: Bool = false
        ) {
            self.init(status: status, body: Data(json.utf8), headers: headers, delay: delay, hold: hold)
        }

        static func failure(_ code: URLError.Code) -> Response {
            var response = Response(status: 0)
            response.failure = code
            return response
        }
    }

    // 훅은 원래 stub처럼 Sendable이 아닌 클로저로 받는다. 테스트(MainActor)에서 만든 클로저가
    // 지역 변수를 캡처한 뒤 바꿔도 경고가 나지 않게 하고, 호출은 항상 MainActor 작업에서 한다.
    private final class State {
        var requests: [URLRequest] = []
        var responses: [Response] = []
        var handler: ((URLRequest) -> Response)?
        var onNextRequest: ((URLRequest) -> Void)?
        var beforeResponse: ((URLRequest) async -> Void)?
        var isHolding = false
        var holdContinuation: CheckedContinuation<Void, Never>?
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var states: [ObjectIdentifier: State] = [:]

    private static func withState<T>(_ body: (State) -> T) -> T {
        lock.withLock {
            let key = ObjectIdentifier(self)
            if let state = states[key] { return body(state) }
            let state = State()
            states[key] = state
            return body(state)
        }
    }

    /// 요청 경로만으로 응답이 정해지는 stub은 하위 클래스에서 재정의한다. nil이면 응답 큐를 쓴다.
    class func response(for request: URLRequest) -> Response? { nil }

    /// 응답 큐가 비었고 `handler`도 없을 때 돌려줄 응답. 스위트별 기본값이 다르면 하위 클래스에서 바꾼다.
    class var fallbackResponse: Response { Response(status: 500) }

    /// 모든 HTTP 응답에 넣을 헤더. 앱이 읽는 헤더(예: `Date`)가 필요하면 하위 클래스에서 바꾼다.
    class var defaultHeaders: [String: String] { ["Content-Type": "application/json"] }

    static var requests: [URLRequest] {
        get { withState { $0.requests } }
        set { withState { $0.requests = newValue } }
    }

    /// 요청 순서대로 하나씩 꺼내 쓰는 응답 큐.
    static var responses: [Response] {
        get { withState { $0.responses } }
        set { withState { $0.responses = newValue } }
    }

    /// 설정하면 응답 큐 대신 요청마다 MainActor에서 응답을 만든다.
    static var handler: ((URLRequest) -> Response)? {
        get { withState { $0.handler } }
        set { withState { $0.handler = newValue } }
    }

    /// 다음 요청 1회에만 MainActor에서 호출한다. 지연·보류 응답이 도착하기 전 시점을 잡을 때 쓴다.
    static var onNextRequest: ((URLRequest) -> Void)? {
        get { withState { $0.onNextRequest } }
        set { withState { $0.onNextRequest = newValue } }
    }

    /// 요청마다 응답을 보내기 직전에 MainActor에서 기다린다.
    static var beforeResponse: ((URLRequest) async -> Void)? {
        get { withState { $0.beforeResponse } }
        set { withState { $0.beforeResponse = newValue } }
    }

    static var isHolding: Bool { withState { $0.isHolding } }

    static func resumeHold() {
        let continuation = withState { state -> CheckedContinuation<Void, Never>? in
            let continuation = state.holdContinuation
            state.holdContinuation = nil
            state.isHolding = false
            return continuation
        }
        continuation?.resume()
    }

    /// 요청 기록·응답 큐·훅을 비우고 보류 중인 응답을 풀어 준다.
    class func reset() {
        resumeHold()
        withState { state in
            state.requests = []
            state.responses = []
            state.handler = nil
            state.onNextRequest = nil
            state.beforeResponse = nil
        }
    }

    private var loading: Task<Void, Never>?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let request = request
        let routed = Self.response(for: request)
        let (queued, usesMainActor) = Self.withState { state -> (Response?, Bool) in
            state.requests.append(request)
            let usesMainActor = state.handler != nil || state.onNextRequest != nil || state.beforeResponse != nil
            guard routed == nil, state.handler == nil, !state.responses.isEmpty else { return (nil, usesMainActor) }
            return (state.responses.removeFirst(), usesMainActor)
        }
        let prepared = routed ?? queued ?? Self.fallbackResponse
        // 훅·지연·보류가 없으면 기존 stub처럼 startLoading 안에서 바로 응답한다.
        if !usesMainActor, prepared.delay == .zero, !prepared.hold {
            deliver(prepared)
            return
        }
        let loader = Loader(stub: self)
        loading = Task { @MainActor in
            let response = Self.handler?(request) ?? prepared
            if let onNextRequest = Self.takeOnNextRequest() { onNextRequest(request) }
            await Self.beforeResponse?(request)
            if response.hold { await Self.waitForHold() }
            if response.delay > .zero { try? await Task.sleep(for: response.delay) }
            guard !Task.isCancelled else { return }
            loader.stub.deliver(response)
        }
    }

    // URLProtocol은 Sendable이 아니다. URLSession이 로딩을 끝내기 전까지 이 객체를 붙잡고 있으므로
    // MainActor 작업에서 client 콜백을 호출하는 용도로만 넘긴다.
    private struct Loader: @unchecked Sendable {
        let stub: StubURLProtocol
    }

    override func stopLoading() {
        loading?.cancel()
    }

    private static func takeOnNextRequest() -> ((URLRequest) -> Void)? {
        withState { state in
            let hook = state.onNextRequest
            state.onNextRequest = nil
            return hook
        }
    }

    @MainActor private static func waitForHold() async {
        await withCheckedContinuation { continuation in
            withState { state in
                state.isHolding = true
                state.holdContinuation = continuation
            }
        }
    }

    private func deliver(_ response: Response) {
        if let failure = response.failure {
            client?.urlProtocol(self, didFailWithError: URLError(failure))
            return
        }
        let headers = Self.defaultHeaders.merging(response.headers) { $1 }
        guard let url = request.url,
              let http = HTTPURLResponse(url: url, statusCode: response.status, httpVersion: nil, headerFields: headers) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
        if !response.body.isEmpty {
            client?.urlProtocol(self, didLoad: response.body)
        }
        client?.urlProtocolDidFinishLoading(self)
    }
}
