#if DEBUG
import XCTest
import CoreGraphics
@testable import InnoLive

final class PrivacyMaskStabilizerTests: XCTestCase {
    private let box = CGRect(x: 160, y: 160, width: 160, height: 160)
    private func instance(_ pixels: [(Int, Int)]) -> PrivacySegmentation.InstanceMask {
        var bytes = [UInt8](repeating: 0, count: 160 * 160)
        for (x, y) in pixels { bytes[y * 160 + x] = 255 }
        return .init(detection: .init(box: box, score: 0.9, classID: 0, coefficients: []), bytes: bytes)
    }
    private func current(id: Int = 1, index: Int = 0) -> PrivacyBoTSORT.Frame {
        .init(observations: [.init(trackID: id, detectionIndex: index, box: box, confirmed: true)], lost: [])
    }

    func testCurrentDetectionReplacesOldBoundaryWithoutGhostFade() throws {
        let stabilizer = PrivacyMaskStabilizer()
        _ = try stabilizer.apply([0: instance([(50, 50)])], frame: current())
        let result = try stabilizer.apply([0: instance([(60, 60)])], frame: current())
        XCTAssertEqual(result[60 * 160 + 60], 255)
        XCTAssertEqual(result[50 * 160 + 50], 0)
    }

    func testLostMaskMovesToPredictionAndExpiresAfterOneFrame() throws {
        let stabilizer = PrivacyMaskStabilizer()
        _ = try stabilizer.apply([0: instance([(50, 50)])], frame: current())
        let moved = box.offsetBy(dx: 16, dy: 0)
        let held = try stabilizer.apply([:], frame: .init(observations: [], lost: [.init(trackID: 1, box: moved, gap: 1)]))
        XCTAssertEqual(held[50 * 160 + 54], 255)
        XCTAssertEqual(held[50 * 160 + 50], 0)
        XCTAssertEqual(try stabilizer.apply([:], frame: .init(observations: [], lost: [.init(trackID: 1, box: moved, gap: 2)])).max(), 0)
    }

    func testWhitelistedMaskIsCachedButLossRevokesException() throws {
        let stabilizer = PrivacyMaskStabilizer()
        XCTAssertEqual(try stabilizer.apply([0: instance([(50, 50)])], frame: current(), allowed: [0]).max(), 0)
        let held = try stabilizer.apply([:], frame: .init(observations: [], lost: [.init(trackID: 1, box: box, gap: 1)]))
        XCTAssertEqual(held.max(), 255)
    }

    func testReorderedIndexAndNewTrackDoNotReuseOldMask() throws {
        let stabilizer = PrivacyMaskStabilizer()
        _ = try stabilizer.apply([0: instance([(50, 50)])], frame: current())
        _ = try stabilizer.apply([3: instance([(60, 60)])], frame: current(index: 3))
        let held = try stabilizer.apply([:], frame: .init(observations: [], lost: [.init(trackID: 1, box: box, gap: 1)]))
        XCTAssertEqual(held[60 * 160 + 60], 255)
        XCTAssertEqual(held[50 * 160 + 50], 0)
        XCTAssertEqual(try stabilizer.apply([:], frame: .init(observations: [], lost: [.init(trackID: 2, box: box, gap: 1)])).max(), 0)
    }

    func testOverlappingCurrentTrackSuppressesHeldMask() throws {
        let stabilizer = PrivacyMaskStabilizer()
        _ = try stabilizer.apply([0: instance([(50, 50)])], frame: current())
        let frame = PrivacyBoTSORT.Frame(observations: current(id: 2).observations, lost: [.init(trackID: 1, box: box, gap: 1)])
        XCTAssertEqual(try stabilizer.apply([0: instance([])], frame: frame).max(), 0)
    }

    func testResetClearsHeldMaskAndInvalidBytesFail() throws {
        let stabilizer = PrivacyMaskStabilizer()
        _ = try stabilizer.apply([0: instance([(50, 50)])], frame: current())
        stabilizer.reset()
        XCTAssertEqual(try stabilizer.apply([:], frame: .init(observations: [], lost: [.init(trackID: 1, box: box, gap: 1)])).max(), 0)
        let invalid = PrivacySegmentation.InstanceMask(detection: instance([]).detection, bytes: [])
        XCTAssertThrowsError(try stabilizer.apply([0: invalid], frame: current()))
    }
}
#endif
