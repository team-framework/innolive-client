import XCTest
@testable import InnoLive

final class OfflineGrantTests: XCTestCase {
    private let issuedAt = Date(timeIntervalSince1970: 1_800_000_000)

    func testValidWithinFourteenDays() {
        let grant = OfflineGrant(isAdministrator: true, issuedAt: issuedAt)
        XCTAssertEqual(grant.validUntil, issuedAt.addingTimeInterval(14 * 24 * 60 * 60))
        XCTAssertEqual(grant.status(at: issuedAt), .valid)
        XCTAssertEqual(grant.status(at: issuedAt.addingTimeInterval(13 * 24 * 60 * 60)), .valid)
    }

    func testExpiredAfterFifteenDays() {
        let grant = OfflineGrant(isAdministrator: true, issuedAt: issuedAt)
        XCTAssertEqual(grant.status(at: issuedAt.addingTimeInterval(15 * 24 * 60 * 60)), .expired)
    }

    func testClockMovedBeforeLastSeenIsBlocked() {
        let seen = OfflineGrant(isAdministrator: true, issuedAt: issuedAt).observing(issuedAt.addingTimeInterval(3_600))
        XCTAssertEqual(seen.status(at: issuedAt.addingTimeInterval(60)), .clockRolledBack)
        XCTAssertEqual(seen.status(at: issuedAt.addingTimeInterval(7_200)), .valid)
    }

    func testObservingNeverMovesBackward() {
        let later = issuedAt.addingTimeInterval(3_600)
        let grant = OfflineGrant(isAdministrator: false, issuedAt: issuedAt).observing(later).observing(issuedAt)
        XCTAssertEqual(grant.lastSeenWallClock, later)
        XCTAssertFalse(grant.isAdministrator)
    }

    func testCodableRoundTripKeepsLastSeen() throws {
        let grant = OfflineGrant(isAdministrator: true, issuedAt: issuedAt).observing(issuedAt.addingTimeInterval(10))
        let decoded = try JSONDecoder().decode(OfflineGrant.self, from: JSONEncoder().encode(grant))
        XCTAssertEqual(decoded, grant)
    }
}
