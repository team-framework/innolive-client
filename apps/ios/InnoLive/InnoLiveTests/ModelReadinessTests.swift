import XCTest
@testable import InnoLive

@MainActor final class ModelReadinessTests: XCTestCase {
    private func defaults() -> UserDefaults {
        let name = "ModelReadinessTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        addTeardownBlock { defaults.removePersistentDomain(forName: name) }
        return defaults
    }

    func testMissingModelsStayMissing() async {
        let store = ModelReadinessStore(available: false, loaders: [], defaults: defaults())
        await store.warm()
        XCTAssertEqual(store.state, .missing)
        XCTAssertFalse(store.isReady)
    }

    func testLoadersRunInOrderThenReady() async {
        let order = LoadOrder()
        let store = ModelReadinessStore(available: true, loaders: [
            .init(name: "a") { order.append("a") }, .init(name: "b") { order.append("b") }
        ], defaults: defaults())
        XCTAssertEqual(store.state, .compiling(progress: 0))
        XCTAssertFalse(store.wasWarmedForThisBuild)
        await store.warm()
        XCTAssertEqual(store.state, .ready)
        XCTAssertEqual(order.names, ["a", "b"])
        XCTAssertNotNil(store.lastWarmSeconds)
        XCTAssertTrue(store.wasWarmedForThisBuild)
        await store.warm()
        XCTAssertEqual(order.names, ["a", "b"], "ready is final; no second warm-up")
    }

    func testFailureReportsAndCanRetry() async {
        let attempts = LoadOrder()
        let store = ModelReadinessStore(available: true, loaders: [
            .init(name: "flaky") {
                attempts.append("x")
                if attempts.names.count == 1 { throw PrivacyModelError.missingModel }
            }
        ], defaults: defaults())
        await store.warm()
        guard case .failed = store.state else { return XCTFail("expected failed, got \(store.state)") }
        await store.warm()
        XCTAssertEqual(store.state, .ready)
    }
}

nonisolated private final class LoadOrder: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String] = []
    var names: [String] { lock.withLock { values } }
    func append(_ name: String) { lock.withLock { values.append(name) } }
}
