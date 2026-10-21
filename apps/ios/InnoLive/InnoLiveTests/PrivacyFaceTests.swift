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

    func testOneMatchApprovesAndNeverSchedulesAgain() throws {
        let (tracker, _, _) = try allowedTracker()
        for step in 1...300 {
            let time = 1 + Double(step) / 30
            tracker.update([3: box.offsetBy(dx: CGFloat(step), dy: 0)], at: time, trackIDs: [3: 10])
            XCTAssertEqual(tracker.allowed(at: time), [3])
            XCTAssertNil(tracker.next(at: time))
        }
    }

    func testApprovalSurvivesOverlapAndLateNegativeOrMissingResult() throws {
        let (tracker, id, _) = try allowedTracker()
        tracker.update([0: box, 1: box.offsetBy(dx: 30, dy: 0)], at: 1.1, trackIDs: [0: 10, 1: 11])
        tracker.accept(trackID: id, match: nil, capturedAt: 1, now: 1.1)
        tracker.accept(trackID: id, match: nil, capturedAt: 1, now: 1.2, sampleAvailable: false)
        XCTAssertEqual(tracker.allowed(at: 20), [0])
        XCTAssertNil(tracker.next(at: 1.3)) // The unknown overlapping crop stays protected.
    }

    func testLostApprovalFollowsTrackerLifetimeAndDoesNotLeakDetectionIndex() throws {
        let (tracker, _, _) = try allowedTracker()
        for step in 1...20 {
            tracker.update([0: box], at: 1 + Double(step) / 30, trackIDs: [0: 11], liveTrackIDs: [10, 11])
            XCTAssertTrue(tracker.allowed(at: 2).isEmpty)
            XCTAssertEqual(tracker.approvedTrackIDs, [10])
        }
        tracker.update([4: box], at: 1.8, trackIDs: [4: 10], liveTrackIDs: [10])
        XCTAssertEqual(tracker.allowed(at: 1.8), [4])
        XCTAssertNil(tracker.next(at: 1.8))
        tracker.update([:], at: 1.9, trackIDs: [:], liveTrackIDs: [])
        XCTAssertTrue(tracker.tracks.isEmpty)
        tracker.update([0: box], at: 2, trackIDs: [0: 10])
        XCTAssertTrue(tracker.allowed(at: 2).isEmpty)
    }

    func testUnapprovedMissingAndNegativeSamplesRetry() throws {
        let tracker = PrivacyFaceTracking()
        tracker.update([0: box], at: 1, trackIDs: [0: 10])
        let first = try XCTUnwrap(tracker.next(at: 1))
        tracker.accept(trackID: first.id, match: nil, capturedAt: 1, now: 1.1, sampleAvailable: false)
        XCTAssertTrue(tracker.allowed(at: 1.1).isEmpty)
        XCTAssertNil(tracker.next(at: 1.2))
        let retry = try XCTUnwrap(tracker.next(at: 1.25))
        tracker.accept(trackID: retry.id, match: nil, capturedAt: 1.25, now: 1.3)
        XCTAssertNotNil(tracker.next(at: 1.5))
        XCTAssertTrue(tracker.allowed(at: 1.5).isEmpty)
    }

    func testStaleUnscheduledAndInvalidResultsNeverApprove() throws {
        let tracker = PrivacyFaceTracking()
        tracker.update([0: box], at: 1, trackIDs: [0: 10])
        let first = try XCTUnwrap(tracker.next(at: 1))
        for (captured, now) in [(1.0, 1.75), (1.0, 0.9), (1.1, 1.2), (1.0, Double.nan)] {
            tracker.accept(trackID: first.id, match: UUID(), capturedAt: captured, now: now)
            XCTAssertTrue(tracker.allowed(at: 2).isEmpty)
        }
    }

    func testWhitelistEditsPreserveOtherApprovalsAndRejectPendingResults() throws {
        let (tracker, id, person) = try allowedTracker()
        let other = UUID()
        tracker.retainEntries([person, other])
        XCTAssertEqual(tracker.allowed(at: 2), [0])
        tracker.retainEntries([person])
        XCTAssertNil(tracker.next(at: 2))
        tracker.retainEntries([other])
        XCTAssertTrue(tracker.allowed(at: 2).isEmpty)
        tracker.accept(trackID: id, match: person, capturedAt: 1, now: 1.1)
        XCTAssertTrue(tracker.allowed(at: 2).isEmpty)
        let pending = try XCTUnwrap(tracker.next(at: 2))
        tracker.retainEntries([other, person])
        tracker.accept(trackID: pending.id, match: other, capturedAt: 2, now: 2.1)
        XCTAssertTrue(tracker.allowed(at: 2.1).isEmpty)
        XCTAssertNotNil(tracker.next(at: 2.1))
    }

    func testResetGapAndNewIDRequireFreshApproval() throws {
        let (tracker, id, person) = try allowedTracker()
        tracker.update([0: box], at: 2.1, trackIDs: [0: 10])
        tracker.accept(trackID: id, match: person, capturedAt: 1, now: 2.1)
        XCTAssertTrue(tracker.allowed(at: 2.1).isEmpty)
        tracker.reset()
        XCTAssertTrue(tracker.tracks.isEmpty)
        tracker.update([0: box], at: 3, trackIDs: [0: 11])
        tracker.accept(trackID: id, match: person, capturedAt: 3, now: 3)
        XCTAssertTrue(tracker.allowed(at: 3).isEmpty)
    }

    func testSameRegisteredPersonCanApproveIndependentTracks() throws {
        let tracker = PrivacyFaceTracking()
        tracker.update([0: box, 1: box.offsetBy(dx: 250, dy: 0)], at: 1, trackIDs: [0: 10, 1: 11])
        let person = UUID()
        for time in [1.0, 1.1] {
            let track = try XCTUnwrap(tracker.next(at: time))
            tracker.accept(trackID: track.id, match: person, capturedAt: time, now: time)
        }
        XCTAssertEqual(tracker.allowed(at: 2), [0, 1])
    }

    private func allowedTracker() throws -> (PrivacyFaceTracking, UUID, UUID) {
        let tracker = PrivacyFaceTracking()
        let person = UUID()
        tracker.update([0: box], at: 1, trackIDs: [0: 10])
        let track = try XCTUnwrap(tracker.next(at: 1))
        tracker.accept(trackID: track.id, match: person, capturedAt: 1, now: 1.01)
        XCTAssertEqual(tracker.allowed(at: 1.01), [0])
        return (tracker, track.id, person)
    }
}
#endif
