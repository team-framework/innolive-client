import XCTest
import CoreImage
@testable import InnoLive

final class PrivacyUplinkTests: XCTestCase {
    func testPendingFramesAreDroppedAndOnlyOneResultCanFinish() throws {
        let gate = PrivacyFrameGate()
        let first = try XCTUnwrap(gate.begin())
        XCTAssertTrue(first.enabled)
        XCTAssertNil(gate.begin())
        var delivered = 0
        gate.finish(first) { delivered += 1 }
        XCTAssertEqual(delivered, 1)
        let second = try XCTUnwrap(gate.begin())
        gate.finish(first) { XCTFail("A completed ticket delivered twice") }
        XCTAssertNil(gate.begin())
        gate.finish(second) {}
    }

    func testReenablingAnonymizationDropsPendingRawFrame() throws {
        let gate = PrivacyFrameGate()
        gate.invalidate(enabled: false)
        let raw = try XCTUnwrap(gate.begin())
        XCTAssertFalse(raw.enabled)
        gate.invalidate(enabled: true)
        gate.finish(raw) { XCTFail("Raw frame survived enabling AI") }
        XCTAssertTrue(try XCTUnwrap(gate.begin()).enabled)
    }

    func testCameraChangeAndStopRevokeQueuedResults() throws {
        let gate = PrivacyFrameGate()
        let previousCamera = try XCTUnwrap(gate.begin())
        gate.invalidate()
        gate.finish(previousCamera) { XCTFail("Old camera result survived reset") }
        let last = try XCTUnwrap(gate.begin())
        gate.invalidate(stop: true)
        gate.finish(last) { XCTFail("Stopped sender delivered a frame") }
        XCTAssertNil(gate.begin())
        gate.invalidate()
        gate.invalidate(enabled: false)
        XCTAssertNil(gate.begin())
    }

    func testRotatingForInferenceRestoresSensorGeometry() {
        let source = CIImage(color: .red).cropped(to: CGRect(x: 0, y: 0, width: 128, height: 72))
        for rotation in [0, 90, 180, 270] {
            let orientation = PrivacyUplinkProcessor.orientation(rotation: rotation)
            let upright = source.oriented(orientation.forward)
            let restored = upright.oriented(orientation.reverse)
            XCTAssertEqual(restored.extent.size, source.extent.size)
            XCTAssertEqual(upright.extent.width, rotation % 180 == 0 ? 128 : 72)
        }
    }

    func testRoutingSwitchDiscardsFramesFromPreviousPath() throws {
        let route = PrivacyUplinkRoute(mode: .server)
        let serverFrame = try XCTUnwrap(route.ticket())
        route.change(to: .onDevice)
        route.deliver(serverFrame) { XCTFail("A raw frame survived local activation") }
        let localFrame = try XCTUnwrap(route.ticket())
        XCTAssertEqual(localFrame.mode, .onDevice)
        route.change(to: .server)
        route.deliver(localFrame) { XCTFail("An old AI result survived switching to server") }
        let latest = try XCTUnwrap(route.ticket())
        var delivered = false
        route.deliver(latest) { delivered = true }
        XCTAssertTrue(delivered)
        route.stop()
        route.change(to: .onDevice)
        XCTAssertNil(route.ticket())
        route.deliver(latest) { XCTFail("Stopped route delivered a frame") }
    }

    func testRegistrationNameUsesServerCompatibleLimits() {
        XCTAssertTrue(ReferenceFaceName.isValid("  게스트 1  "))
        XCTAssertTrue(ReferenceFaceName.isValid(String(repeating: "가", count: 40)))
        XCTAssertFalse(ReferenceFaceName.isValid(String(repeating: "가", count: 41)))
        for name in ["", "  ", "가\n나", "가\r나", "가\0나"] {
            XCTAssertFalse(ReferenceFaceName.isValid(name))
        }
    }

    func testLegacyAndNamedServerFacesDecodeWithoutLocalEmbeddingFields() throws {
        let old = try JSONDecoder().decode(ReferenceFace.self, from: Data(#"{"face_id":"server-id","registered_at":"2026-09-21"}"#.utf8))
        XCTAssertNil(old.name)
        let named = try JSONDecoder().decode(ReferenceFace.self, from: Data(#"{"face_id":"server-id","name":"게스트","registered_at":"2026-09-21"}"#.utf8))
        XCTAssertEqual(named.name, "게스트")
    }
}
