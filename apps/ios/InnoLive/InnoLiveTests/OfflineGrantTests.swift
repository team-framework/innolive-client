import XCTest
@testable import InnoLive

final class OfflineGrantTests: XCTestCase {
    private let issuedAt = Date(timeIntervalSince1970: 1_800_000_000)
    private let account = "550e8400-e29b-41d4-a716-446655440000"
    private let day: TimeInterval = 24 * 60 * 60

    private func grant(serverDate: Date? = nil, receivedAt: Date? = nil, isAdministrator: Bool = true) -> OfflineGrant {
        OfflineGrant(accountID: account, isAdministrator: isAdministrator,
                     serverDate: serverDate ?? issuedAt, receivedAt: receivedAt ?? issuedAt)
    }

    func testValidWithinFourteenDays() {
        let grant = grant()
        XCTAssertEqual(grant.validUntil, issuedAt.addingTimeInterval(14 * day))
        XCTAssertEqual(grant.status(at: issuedAt, accountID: account), .valid)
        XCTAssertEqual(grant.status(at: issuedAt.addingTimeInterval(13 * day), accountID: account), .valid)
    }

    func testExpiredAfterFifteenDays() {
        XCTAssertEqual(grant().status(at: issuedAt.addingTimeInterval(15 * day), accountID: account), .expired)
    }

    func testClockMovedBeforeLastSeenIsBlocked() {
        let seen = grant().observing(issuedAt.addingTimeInterval(3_600))
        XCTAssertEqual(seen.status(at: issuedAt.addingTimeInterval(60), accountID: account), .clockRolledBack)
        XCTAssertEqual(seen.status(at: issuedAt.addingTimeInterval(7_200), accountID: account), .valid)
    }

    func testObservingNeverMovesBackward() {
        let later = issuedAt.addingTimeInterval(3_600)
        let grant = grant(isAdministrator: false).observing(later).observing(issuedAt)
        XCTAssertEqual(grant.lastSeenServerTime, later)
        XCTAssertFalse(grant.isAdministrator)
    }

    func testCodableRoundTripKeepsLastSeenAndAccount() throws {
        let grant = grant(receivedAt: issuedAt.addingTimeInterval(-90)).observing(issuedAt.addingTimeInterval(10))
        let decoded = try JSONDecoder().decode(OfflineGrant.self, from: JSONEncoder().encode(grant))
        XCTAssertEqual(decoded, grant)
        XCTAssertEqual(decoded.accountID, account)
    }

    func testGrantFromAnotherAccountIsRejected() {
        XCTAssertEqual(grant().status(at: issuedAt, accountID: "6ba7b810-9dad-11d1-80b4-00c04fd430c8"), .otherAccount)
        XCTAssertEqual(grant().status(at: issuedAt, accountID: nil), .otherAccount)
    }

    func testStoredGrantWithoutAccountDoesNotDecode() throws {
        let legacy = Data(#"{"isAdministrator":true,"issuedAt":0,"validUntil":1209600,"lastSeenWallClock":0}"#.utf8)
        XCTAssertNil(try? JSONDecoder().decode(OfflineGrant.self, from: legacy))
    }

    func testDeviceClockAheadOfServerDoesNotExtendGrant() {
        let deviceAhead = issuedAt.addingTimeInterval(30 * day)
        let grant = grant(serverDate: issuedAt, receivedAt: deviceAhead)
        XCTAssertEqual(grant.validUntil, issuedAt.addingTimeInterval(14 * day))
        XCTAssertEqual(grant.status(at: deviceAhead.addingTimeInterval(13 * day), accountID: account), .valid)
        XCTAssertEqual(grant.status(at: deviceAhead.addingTimeInterval(15 * day), accountID: account), .expired)
    }

    func testDeviceClockBehindServerIsNotTreatedAsRollback() {
        let deviceBehind = issuedAt.addingTimeInterval(-2 * day)
        let grant = grant(serverDate: issuedAt, receivedAt: deviceBehind)
        XCTAssertEqual(grant.status(at: deviceBehind.addingTimeInterval(1), accountID: account), .valid)
        XCTAssertEqual(grant.status(at: deviceBehind.addingTimeInterval(15 * day), accountID: account), .expired)
    }

    func testMissingServerDateFallsBackToDeviceClock() {
        let grant = OfflineGrant(accountID: account, isAdministrator: true, serverDate: nil, receivedAt: issuedAt)
        XCTAssertEqual(grant.issuedAt, issuedAt)
        XCTAssertEqual(grant.serverClockOffset, 0)
    }

    func testParsesHTTPDateHeader() {
        XCTAssertEqual(OfflineGrant.serverDate(fromHTTPDate: "Fri, 15 Jan 2027 08:00:00 GMT"), issuedAt)
        XCTAssertNil(OfflineGrant.serverDate(fromHTTPDate: "not a date"))
        XCTAssertNil(OfflineGrant.serverDate(fromHTTPDate: nil))
    }

    func testReadsAccountFromAccessTokenSubject() {
        let payload = Data(#"{"sub":"550e8400-e29b-41d4-a716-446655440000","exp":1800000000}"#.utf8)
            .base64EncodedString().replacingOccurrences(of: "=", with: "")
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
        XCTAssertEqual(OfflineGrant.accountID(fromAccessToken: "eyJhbGciOiJIUzI1NiJ9.\(payload).sig"), account)
        XCTAssertNil(OfflineGrant.accountID(fromAccessToken: "opaque-token"))
        XCTAssertNil(OfflineGrant.accountID(fromAccessToken: nil))
    }

    func testRejectsMalformedOrMissingSubject() {
        for token in ["", "a.bad.c", "a.e30.c", "a.eyJzdWIiOiIifQ.c", "a.eyJzdWIiOjF9.c"] {
            XCTAssertNil(OfflineGrant.accountID(fromAccessToken: token), token)
        }
    }

    func testExpiredGrantStillAllowsLocalTake() {
        let afterExpiry = issuedAt.addingTimeInterval(30 * day)
        XCTAssertEqual(grant().status(at: afterExpiry, accountID: account), .expired)
        XCTAssertTrue(grant().allowsLocalTake(at: afterExpiry, accountID: account))
    }

    func testClockRollbackStillAllowsLocalTake() {
        let seen = grant().observing(issuedAt.addingTimeInterval(3_600))
        XCTAssertTrue(seen.allowsLocalTake(at: issuedAt.addingTimeInterval(60), accountID: account))
    }

    func testLocalTakeNeedsAdministratorOnSameAccount() {
        XCTAssertTrue(grant().allowsLocalTake(at: issuedAt, accountID: account))
        XCTAssertFalse(grant(isAdministrator: false).allowsLocalTake(at: issuedAt, accountID: account))
        XCTAssertFalse(grant().allowsLocalTake(at: issuedAt.addingTimeInterval(30 * day),
                                               accountID: "6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
        XCTAssertFalse(grant().allowsLocalTake(at: issuedAt, accountID: nil))
    }
}
