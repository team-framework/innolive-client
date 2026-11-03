import XCTest

@testable import InnoLive

final class ISO8601TimestampTests: XCTestCase {
    func testParsesTimestampWithoutFractionalSeconds() {
        XCTAssertEqual(ISO8601Timestamp.date(from: "2027-01-15T08:00:00Z")?.timeIntervalSince1970, 1_800_000_000)
    }

    func testParsesTimestampWithFractionalSeconds() throws {
        let date = try XCTUnwrap(ISO8601Timestamp.date(from: "2027-01-15T08:00:00.250Z"))
        XCTAssertEqual(date.timeIntervalSince1970, 1_800_000_000.25, accuracy: 0.0001)
    }

    func testParsesTimestampWithOffset() {
        XCTAssertEqual(ISO8601Timestamp.date(from: "2027-01-15T17:00:00+09:00")?.timeIntervalSince1970, 1_800_000_000)
    }

    func testRejectsNonTimestamp() {
        XCTAssertNil(ISO8601Timestamp.date(from: ""))
        XCTAssertNil(ISO8601Timestamp.date(from: "2027-01-15"))
        XCTAssertNil(ISO8601Timestamp.date(from: "not a date"))
    }
}
