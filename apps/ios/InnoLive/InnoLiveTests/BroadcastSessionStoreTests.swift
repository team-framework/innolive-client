import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class BroadcastSessionStoreTests: XCTestCase {
    func testKeychainSurvivesStoreRecreationAndSeparatesUsersAndServers() throws {
        let service = "com.framework.innolive.test.broadcast.\(UUID().uuidString)"
        let first = BroadcastSessionStore(service: service)
        let scopes = try [scope("a", "one.invalid"), scope("b", "one.invalid"), scope("a", "two.invalid")]
        defer { for scope in scopes { try? first.remove(scope: scope) } }
        for (index, scope) in scopes.enumerated() {
            try first.save(.init(sessionID: "session-\(index)", ownerToken: "owner-\(index)"), scope: scope)
        }
        let restored = BroadcastSessionStore(service: service)
        for (index, scope) in scopes.enumerated() {
            XCTAssertEqual(try restored.load(scope: scope), .init(sessionID: "session-\(index)", ownerToken: "owner-\(index)"))
        }
        try restored.save(.init(sessionID: "replacement", ownerToken: "replacement-owner"), scope: scopes[0])
        XCTAssertEqual(try first.load(scope: scopes[0])?.sessionID, "replacement")
        try restored.remove(scope: scopes[0])
        try restored.remove(scope: scopes[0])
        XCTAssertNil(try first.load(scope: scopes[0]))
        XCTAssertNotNil(try first.load(scope: scopes[1]))
    }

    func testMalformedOrMissingSubjectIsRejected() throws {
        let server = try XCTUnwrap(URL(string: "https://example.invalid"))
        for token in ["", "opaque-token", "a.bad.c", "a.e30.c", "a.eyJzdWIiOiIifQ.c"] {
            XCTAssertThrowsError(try BroadcastSessionScope(server: server, accessToken: token))
        }
    }

    private func scope(_ user: String, _ host: String) throws -> BroadcastSessionScope {
        let payload = Data("{\"sub\":\"\(user)\"}".utf8).base64EncodedString()
        return try BroadcastSessionScope(server: XCTUnwrap(URL(string: "https://\(host)/")), accessToken: "header.\(payload).signature")
    }
}
