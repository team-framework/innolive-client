#if DEBUG
import XCTest
import CoreGraphics
@testable import InnoLive

final class PrivacyFaceTests: XCTestCase {
    private func vector(_ index: Int) -> [Float] {
        var value = [Float](repeating: 0, count: 512)
        value[index] = 1
        return value
    }

    func testMultipleRegisteredPeopleAndUnknownFace() {
        let a = PrivacyRegisteredFace(id: UUID(), name: "A", embedding: vector(0))
        let b = PrivacyRegisteredFace(id: UUID(), name: "B", embedding: vector(1))
        XCTAssertEqual(PrivacyFaceMath.match(vector(0), entries: [a, b]), a.id)
        XCTAssertEqual(PrivacyFaceMath.match(vector(1), entries: [a, b]), b.id)
        XCTAssertNil(PrivacyFaceMath.match(vector(2), entries: [a, b]))
    }

    func testAmbiguousAndInvalidEmbeddingsNeverMatch() {
        let a = PrivacyRegisteredFace(id: UUID(), name: "A", embedding: vector(0))
        let b = PrivacyRegisteredFace(id: UUID(), name: "B", embedding: vector(0))
        XCTAssertNil(PrivacyFaceMath.match(vector(0), entries: [a, b]))
        XCTAssertNil(PrivacyFaceMath.match([Float](repeating: 0, count: 512), entries: [a]))
        XCTAssertNil(PrivacyFaceMath.normalized([Float](repeating: .nan, count: 512)))
        XCTAssertNil(PrivacyFaceMath.normalized([1, 2]))
    }

    func testRegistrationPersistsAndIndividualDeletionPreservesOtherPeople() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("faces.json")
        let library = try PrivacyFaceLibrary(url: url)
        try library.add(name: " A ", embedding: vector(0))
        try library.add(name: "B", embedding: vector(1))
        let reloaded = try PrivacyFaceLibrary(url: url)
        XCTAssertEqual(reloaded.entries.map(\.name), ["A", "B"])
        try reloaded.delete(id: reloaded.entries[0].id)
        XCTAssertEqual(try PrivacyFaceLibrary(url: url).entries.map(\.name), ["B"])
        XCTAssertEqual(try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    }

    func testInvalidRegistrationDoesNotReplaceSavedData() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let library = try PrivacyFaceLibrary(url: url)
        try library.add(name: "A", embedding: vector(0))
        XCTAssertThrowsError(try library.add(name: " ", embedding: vector(1)))
        XCTAssertThrowsError(try library.add(name: "B", embedding: [Float](repeating: 0, count: 512)))
        XCTAssertEqual(try PrivacyFaceLibrary(url: url).entries.count, 1)
    }

    func testRegistrationLimitAndDifferentModelContract() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let library = try PrivacyFaceLibrary(url: url)
        for i in 0..<20 { try library.add(name: "Person \(i)", embedding: vector(i)) }
        XCTAssertThrowsError(try library.add(name: "Overflow", embedding: vector(20)))
        var data = String(decoding: try Data(contentsOf: url), as: UTF8.self)
        data = data.replacingOccurrences(of: PrivacyFaceLibrary.contract, with: "other-model")
        try Data(data.utf8).write(to: url)
        XCTAssertThrowsError(try PrivacyFaceLibrary(url: url))
    }

    private let box = CGRect(x: 10, y: 10, width: 100, height: 100)

    func testTwoConfirmationsRequiredAndExceptionExpires() throws {
        let tracker = PrivacyFaceTracking()
        let person = UUID()
        tracker.update([0: box], at: 1)
        let track = try XCTUnwrap(tracker.next(at: 1))
        tracker.accept(trackID: track.id, match: person, capturedAt: 1, now: 1.01)
        XCTAssertTrue(tracker.allowed(at: 1.01).isEmpty)
        tracker.accept(trackID: track.id, match: person, capturedAt: 1.3, now: 1.31)
        XCTAssertEqual(tracker.allowed(at: 1.31), [0])
        XCTAssertTrue(tracker.allowed(at: 2.06).isEmpty)
    }

    func testUnknownResultImmediatelyRevokesException() throws {
        let (tracker, track, _) = try allowedTracker()
        tracker.accept(trackID: track, match: nil, capturedAt: 1.1, now: 1.11)
        XCTAssertTrue(tracker.allowed(at: 1.11).isEmpty)
    }

    func testOverlapDisappearanceAndRestartDiscardIdentity() throws {
        let (tracker, _, _) = try allowedTracker()
        tracker.update([0: box, 1: box.offsetBy(dx: 30, dy: 0)], at: 1.05)
        XCTAssertTrue(tracker.allowed(at: 1.05).isEmpty)
        tracker.update([0: box], at: 1.1)
        XCTAssertTrue(tracker.allowed(at: 1.1).isEmpty)
        tracker.update([:], at: 1.15)
        tracker.update([0: box], at: 1.19)
        XCTAssertTrue(tracker.allowed(at: 1.19).isEmpty)
        tracker.reset()
        XCTAssertTrue(tracker.tracks.isEmpty)
    }

    func testLongFrameGapAndOldAsyncResultCannotRestoreIdentity() throws {
        let (tracker, track, person) = try allowedTracker()
        tracker.update([0: box], at: 1.4)
        tracker.accept(trackID: track, match: person, capturedAt: 1.02, now: 1.41)
        XCTAssertTrue(tracker.allowed(at: 1.41).isEmpty)
    }

    func testSlowRecognitionAndConflictingIdentityRevokeException() throws {
        let (tracker, track, person) = try allowedTracker()
        tracker.accept(trackID: track, match: person, capturedAt: 1.02, now: 1.8)
        XCTAssertTrue(tracker.allowed(at: 1.8).isEmpty)
        tracker.accept(trackID: track, match: UUID(), capturedAt: 1.82, now: 1.83)
        XCTAssertTrue(tracker.allowed(at: 1.83).isEmpty)
    }

    func testReorderedDetectionIndicesFollowGeometry() throws {
        let (tracker, _, _) = try allowedTracker()
        tracker.update([3: box.offsetBy(dx: 5, dy: 0)], at: 1.05)
        XCTAssertEqual(tracker.allowed(at: 1.05), [3])
    }

    func testSameIdentityOnTwoTracksIsAmbiguous() throws {
        let tracker = PrivacyFaceTracking()
        tracker.update([0: box, 1: box.offsetBy(dx: 250, dy: 0)], at: 1)
        let person = UUID()
        for track in tracker.tracks {
            tracker.accept(trackID: track.id, match: person, capturedAt: 1, now: 1.01)
            tracker.accept(trackID: track.id, match: person, capturedAt: 1.02, now: 1.03)
        }
        XCTAssertTrue(tracker.allowed(at: 1.03).isEmpty)
    }

    private func allowedTracker() throws -> (PrivacyFaceTracking, UUID, UUID) {
        let tracker = PrivacyFaceTracking()
        let person = UUID()
        tracker.update([0: box], at: 1)
        let track = try XCTUnwrap(tracker.next(at: 1))
        tracker.accept(trackID: track.id, match: person, capturedAt: 1, now: 1.01)
        tracker.accept(trackID: track.id, match: person, capturedAt: 1.02, now: 1.03)
        XCTAssertEqual(tracker.allowed(at: 1.03), [0])
        return (tracker, track.id, person)
    }
}
#endif
