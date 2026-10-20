#if DEBUG
import XCTest
import CoreGraphics
@testable import InnoLive

final class PrivacyBoTSORTTests: XCTestCase {
    private func detection(_ x: CGFloat = 100, score: Float = 0.9, classID: Int = 0) -> PrivacySegmentation.Detection {
        .init(box: CGRect(x: x, y: 100, width: 80, height: 80), score: score, classID: classID, coefficients: [])
    }

    func testServerPinnedUltralyticsParity() throws {
        struct Fixture: Decodable {
            struct Frame: Decodable {
                let detections: [[Double]]
                let motion: [Double]
                let expected: [[Double]]
            }
            let ultralytics: String
            let frames: [Frame]
        }
        let bundle = Bundle(for: Self.self)
        let url = try XCTUnwrap(bundle.url(forResource: "privacy-botsort-reference", withExtension: "json"))
        let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
        XCTAssertEqual(fixture.ultralytics, "8.4.104")
        let tracker = PrivacyBoTSORT()
        for (number, frame) in fixture.frames.enumerated() {
            let detections = frame.detections.map { row in
                PrivacySegmentation.Detection(box: CGRect(x: row[0], y: row[1], width: row[2] - row[0], height: row[3] - row[1]),
                                               score: Float(row[4]), classID: Int(row[5]), coefficients: [])
            }
            let m = frame.motion
            let actual = tracker.update(detections, motion: CGAffineTransform(a: m[0], b: m[3], c: m[1], d: m[4], tx: m[2], ty: m[5]))
                .observations.filter(\.confirmed).sorted { $0.trackID < $1.trackID }
            let expected = frame.expected.sorted { $0[4] < $1[4] }
            XCTAssertEqual(actual.count, expected.count, "frame \(number)")
            for (track, row) in zip(actual, expected) {
                XCTAssertEqual(track.trackID, Int(row[4]), "frame \(number)")
                XCTAssertEqual(track.detectionIndex, Int(row[7]), "frame \(number)")
                for (value, reference) in zip([track.box.minX, track.box.minY, track.box.maxX, track.box.maxY], row.prefix(4)) {
                    XCTAssertEqual(value, reference, accuracy: 0.001, "frame \(number)")
                }
            }
        }
    }

    func testLowConfidenceContinuesButCannotCreateOrRecoverLostTrack() throws {
        let tracker = PrivacyBoTSORT()
        XCTAssertTrue(tracker.update([detection(score: 0.1)]).observations.isEmpty)
        let initial = try XCTUnwrap(tracker.update([detection()]).observations.first)
        XCTAssertFalse(initial.confirmed)
        let confirmed = try XCTUnwrap(tracker.update([detection()]).observations.first)
        XCTAssertTrue(confirmed.confirmed)
        let low = try XCTUnwrap(tracker.update([detection(102, score: 0.1)]).observations.first)
        XCTAssertEqual(low.trackID, initial.trackID)
        XCTAssertTrue(low.confirmed)
        XCTAssertEqual(tracker.update([]).lost.first?.gap, 1)
        XCTAssertTrue(tracker.update([detection(score: 0.1)]).observations.isEmpty)
    }

    func testThresholdBoundaryMatchesServerStrictLowComparison() {
        let tracker = PrivacyBoTSORT()
        XCTAssertEqual(tracker.update([detection(score: 0.25)]).observations.count, 1)
        XCTAssertTrue(tracker.update([detection(score: 0.05)]).observations.isEmpty)
    }

    func testDetectionOrderDoesNotChangeIDs() {
        let tracker = PrivacyBoTSORT()
        let first = tracker.update([detection(50), detection(350)])
        let second = tracker.update([detection(352), detection(52)])
        XCTAssertEqual(first.observations[0].trackID, second.observations[1].trackID)
        XCTAssertEqual(first.observations[1].trackID, second.observations[0].trackID)
    }

    func testCameraMotionKeepsIDForNonoverlappingCameraPan() {
        let tracker = PrivacyBoTSORT()
        let first = tracker.update([detection(50)])
        let next = tracker.update([detection(250)], motion: CGAffineTransform(translationX: 200, y: 0))
        XCTAssertEqual(first.observations.first?.trackID, next.observations.first?.trackID)
        XCTAssertTrue(next.observations.first?.confirmed == true)
    }

    func testLostHighConfidenceTrackReactivatesWithSameID() {
        let tracker = PrivacyBoTSORT()
        let first = tracker.update([detection()])
        _ = tracker.update([])
        let recovered = tracker.update([detection()])
        XCTAssertEqual(first.observations.first?.trackID, recovered.observations.first?.trackID)
        XCTAssertTrue(recovered.lost.isEmpty)
    }

    func testExpiredTrackAndResetCannotReuseState() {
        let tracker = PrivacyBoTSORT()
        let first = tracker.update([detection()])
        for _ in 0..<31 { _ = tracker.update([]) }
        let new = tracker.update([detection()])
        XCTAssertNotEqual(first.observations.first?.trackID, new.observations.first?.trackID)
        tracker.reset()
        let reset = tracker.update([detection(500)])
        XCTAssertEqual(reset.observations.first?.trackID, 1)
        XCTAssertTrue(reset.observations.first?.confirmed == true)
        XCTAssertTrue(reset.lost.isEmpty)
    }

    func testStreamsHaveIndependentIDsAndReset() {
        let a = PrivacyBoTSORT(), b = PrivacyBoTSORT()
        _ = a.update([detection()])
        _ = a.update([detection(), detection(350)])
        XCTAssertEqual(b.update([detection()]).observations.first?.trackID, 1)
        b.reset()
        XCTAssertEqual(a.update([detection(), detection(350)]).observations.map(\.trackID), [1, 2])
    }

    func testGlobalAssignmentAvoidsGreedyIdentitySwap() {
        let matched = PrivacyTrackAssignment.solve([[0.1, 0.2], [0.15, 0.9]], limit: 0.8)
        XCTAssertEqual(matched.map { "\($0.0):\($0.1)" }, ["0:1", "1:0"])
    }

    func testAssignmentHandlesRectangularMatricesAndUnmatchedCosts() {
        let matched = PrivacyTrackAssignment.solve([[0.1], [0.2], [0.9]], limit: 0.8)
        XCTAssertEqual(matched.map { "\($0.0):\($0.1)" }, ["0:0"])
        XCTAssertTrue(PrivacyTrackAssignment.solve([[0.9]], limit: 0.8).isEmpty)
        XCTAssertTrue(PrivacyTrackAssignment.solve([[]], limit: 0.8).isEmpty)
        XCTAssertTrue(PrivacyTrackAssignment.solve([], limit: 0.8).isEmpty)
    }

    func testKalmanLearnsVelocityAndAppliesCameraWarp() {
        var filter = PrivacyTrackKalman(box: detection().box)
        for step in 1...10 {
            filter.predict(lost: false)
            filter.correct(box: detection(100 + CGFloat(step * 4)).box)
        }
        let current = filter.box
        filter.predict(lost: true)
        XCTAssertGreaterThan(filter.box.midX, current.midX + 2)
        let predicted = filter.box
        filter.compensate(CGAffineTransform(translationX: 80, y: -10))
        XCTAssertEqual(filter.box.midX, predicted.midX + 80, accuracy: 0.0001)
        XCTAssertEqual(filter.box.midY, predicted.midY - 10, accuracy: 0.0001)
        XCTAssertTrue(filter.covariance.allSatisfy(\.isFinite))
    }

    func testBoTSORTIdentityCannotInheritAnotherFacesWhitelist() throws {
        let tracker = PrivacyFaceTracking()
        let box = detection().box
        tracker.update([0: box], at: 1, trackIDs: [0: 10])
        let original = try XCTUnwrap(tracker.next(at: 1))
        let person = UUID()
        tracker.accept(trackID: original.id, match: person, capturedAt: 1, now: 1)
        tracker.accept(trackID: original.id, match: person, capturedAt: 1.02, now: 1.02)
        XCTAssertEqual(tracker.allowed(at: 1.03), [0])
        tracker.update([1: box], at: 1.04, trackIDs: [1: 11])
        XCTAssertTrue(tracker.allowed(at: 1.04).isEmpty)
        tracker.accept(trackID: original.id, match: person, capturedAt: 1.04, now: 1.05)
        XCTAssertTrue(tracker.allowed(at: 1.05).isEmpty)
    }

    func testOverlappingTentativeFaceRevokesConfirmedFaceException() throws {
        let tracker = PrivacyFaceTracking()
        tracker.update([0: detection().box], at: 1, trackIDs: [0: 1])
        let original = try XCTUnwrap(tracker.next(at: 1))
        let person = UUID()
        tracker.accept(trackID: original.id, match: person, capturedAt: 1, now: 1)
        tracker.accept(trackID: original.id, match: person, capturedAt: 1.02, now: 1.02)
        tracker.update([0: detection().box, 1: detection(120).box], at: 1.03, trackIDs: [0: 1])
        XCTAssertTrue(tracker.allowed(at: 1.03).isEmpty)
    }

    func testTentativeAndLostFacesCannotKeepWhitelistLease() throws {
        let tracker = PrivacyFaceTracking()
        tracker.update([0: detection().box], at: 1, trackIDs: [:])
        XCTAssertTrue(tracker.tracks.isEmpty)
        tracker.update([0: detection().box], at: 1.03, trackIDs: [0: 1])
        let old = try XCTUnwrap(tracker.next(at: 1.03))
        tracker.update([:], at: 1.06, trackIDs: [:])
        tracker.update([0: detection().box], at: 1.09, trackIDs: [0: 1])
        XCTAssertNotEqual(tracker.tracks.first?.id, old.id)
    }
}
#endif
