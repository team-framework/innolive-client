import Foundation
import XCTest
@testable import InnoLive

@MainActor
final class LocalTakeUsageTests: XCTestCase {
    private var directory: URL!
    private let start = Date(timeIntervalSince1970: 1_793_500_000)

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("local-take-usage-\(UUID().uuidString)")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: directory)
        super.tearDown()
    }

    private var fileURL: URL { directory.appendingPathComponent("LocalTakeUsage.json") }

    private func recording(_ id: UUID = UUID(), session: String = "local-\(UUID().uuidString)",
                           at createdAt: Date? = nil, seconds: TimeInterval = 600.4) -> SavedBroadcastRecording {
        SavedBroadcastRecording(id: id, sessionID: session, createdAt: createdAt ?? start, duration: seconds,
                                fileName: "\(id.uuidString).mp4", bytes: 1, videoFrames: nil, audioFrames: nil, droppedVideoFrames: nil)
    }

    private func ledger() -> LocalTakeUsageLedger { LocalTakeUsageLedger(fileURL: fileURL, deviceID: "device-1") }

    func testOnlyLocalTakesWithTimeBecomeUsage() throws {
        let id = UUID()
        let take = try XCTUnwrap(LocalTakeUsage(recording: recording(id), deviceID: "device-1"))
        XCTAssertEqual(take.takeID, id)
        XCTAssertEqual(take.durationSeconds, 600)
        XCTAssertEqual(take.endedAt, start.addingTimeInterval(600.4))
        XCTAssertNil(LocalTakeUsage(recording: recording(session: "server-session"), deviceID: "device-1"))
        XCTAssertNil(LocalTakeUsage(recording: recording(seconds: 0.4), deviceID: "device-1"))
    }

    func testLedgerPersistsOnceAcrossRelaunchUntilAcknowledged() {
        let saved = recording()
        let first = ledger()
        first.record(saved)
        first.record(saved)
        first.record(recording(session: "server-session"))
        XCTAssertEqual(first.pending.map(\.takeID), [saved.id])

        let relaunched = ledger()
        XCTAssertEqual(relaunched.pending.map(\.takeID), [saved.id], "pending take survives relaunch")
        XCTAssertEqual(relaunched.pendingSeconds, 600)
        relaunched.acknowledge([saved.id])
        XCTAssertTrue(ledger().pending.isEmpty, "acknowledged take is gone after relaunch")

        relaunched.record(recording())
        relaunched.eraseAll()
        XCTAssertTrue(ledger().pending.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path))
    }

    func testWireFormatMatchesServerContract() throws {
        let take = try XCTUnwrap(LocalTakeUsage(recording: recording(), deviceID: "device-1"))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: LocalTakeUsageLedger.encoder.encode(take)) as? [String: Any])
        XCTAssertEqual(Set(object.keys), ["take_id", "device_id", "started_at", "ended_at", "duration_seconds"])
        XCTAssertEqual(object["duration_seconds"] as? Int, 600)
        XCTAssertNotNil(ISO8601DateFormatter().date(from: try XCTUnwrap(object["started_at"] as? String)))
    }

    /// The app is killed after the server stored the takes but before the acknowledgement reached the ledger.
    func testReplayAfterKillBetweenSendAndAcknowledgementCountsEachTakeOnce() async {
        var server: [UUID: Int] = [:]
        let first = ledger()
        let takes = (0..<3).map { _ in recording() }
        takes.forEach(first.record)

        let killed = LocalTakeUsageUploader(ledger: first, upload: { batch in
            batch.forEach { server[$0.takeID] = $0.durationSeconds }
            throw URLError(.networkConnectionLost)
        }, sleep: { _ in throw CancellationError() })
        await killed.run()
        XCTAssertEqual(first.pending.count, 3, "unacknowledged takes stay pending")

        let relaunched = ledger()
        var uploads = 0
        let uploader = LocalTakeUsageUploader(ledger: relaunched, upload: { batch in
            uploads += 1
            batch.forEach { server[$0.takeID] = $0.durationSeconds }
            return batch.map(\.takeID)
        })
        await uploader.run()
        XCTAssertEqual(uploads, 1)
        XCTAssertTrue(relaunched.pending.isEmpty)
        XCTAssertEqual(Set(server.keys), Set(takes.map(\.id)))
        XCTAssertEqual(server.values.reduce(0, +), 1_800)
    }

    func testMissingEndpointKeepsTakesAndRetriesWithBackoff() async {
        let pending = ledger()
        pending.record(recording())
        var attempts = 0
        var delays: [Duration] = []
        var refreshed = 0
        let uploader = LocalTakeUsageUploader(ledger: pending, upload: { batch in
            attempts += 1
            if attempts < 3 { throw YouTubeAPIError.api(code: "not_found", fallback: "", helpURL: nil) }
            return batch.map(\.takeID)
        }, sleep: { delays.append($0) }, onUploaded: { refreshed += 1 })
        await uploader.run()
        XCTAssertEqual(attempts, 3)
        XCTAssertEqual(delays, [.seconds(5), .seconds(10)])
        XCTAssertTrue(pending.pending.isEmpty)
        XCTAssertEqual(refreshed, 1)
        XCTAssertEqual(LocalTakeUsageUploader.retryDelay(afterFailures: 20), .seconds(600))
    }

    func testOfflineSendsNothingAndUploadsOnceAfterReconnect() async {
        let pending = ledger()
        let saved = recording()
        pending.record(saved)
        var online = false
        var uploads: [[UUID]] = []
        let uploader = LocalTakeUsageUploader(ledger: pending, upload: { batch in
            uploads.append(batch.map(\.takeID))
            return batch.map(\.takeID)
        }, canUpload: { online })
        await uploader.run()
        XCTAssertTrue(uploads.isEmpty, "no request while offline")
        XCTAssertEqual(ledger().pending.map(\.takeID), [saved.id], "take stays pending across relaunch")

        online = true
        await uploader.run()
        await uploader.run()
        XCTAssertEqual(uploads, [[saved.id]], "reconnect uploads the take once")
        XCTAssertTrue(pending.pending.isEmpty)
    }

    func testGoingOfflineDuringBackoffStopsRetrying() async {
        let pending = ledger()
        pending.record(recording())
        var online = true
        var attempts = 0
        let uploader = LocalTakeUsageUploader(ledger: pending, upload: { _ in
            attempts += 1
            throw URLError(.timedOut)
        }, sleep: { _ in online = false }, canUpload: { online })
        await uploader.run()
        XCTAssertEqual(attempts, 1, "no retry once the path is gone")
        XCTAssertEqual(pending.pending.count, 1)
    }

    func testDrainOnReconnectSkipsPendingBackoff() async throws {
        let pending = ledger()
        pending.record(recording())
        var attempts = 0
        let uploader = LocalTakeUsageUploader(ledger: pending, upload: { batch in
            attempts += 1
            if attempts == 1 { throw URLError(.notConnectedToInternet) }
            return batch.map(\.takeID)
        }, sleep: { _ in try await Task.sleep(for: .seconds(3_600)) })
        uploader.drain()
        for _ in 0..<1_000 where attempts == 0 { try await Task.sleep(for: .milliseconds(5)) }
        try await Task.sleep(for: .milliseconds(20))
        uploader.drain()
        for _ in 0..<1_000 where !pending.pending.isEmpty { try await Task.sleep(for: .milliseconds(5)) }
        XCTAssertEqual(attempts, 2)
        XCTAssertTrue(pending.pending.isEmpty, "uploaded without waiting out the hour-long backoff")
    }

    func testUploaderSendsBatchesOfFifty() async {
        let pending = ledger()
        for _ in 0..<120 { pending.record(recording()) }
        var sizes: [Int] = []
        let uploader = LocalTakeUsageUploader(ledger: pending, upload: { batch in
            sizes.append(batch.count)
            return batch.map(\.takeID)
        })
        await uploader.run()
        XCTAssertEqual(sizes, [50, 50, 20])
    }

    func testPlanUsageIncludesPendingTakesFromTheUsageMonth() throws {
        let plan = try JSONDecoder().decode(UserPlan.self, from: Data("""
            {"plan":"beam","allowed_modes":["720p_single"],"monthly_broadcast_seconds":144000,"max_per_broadcast_seconds":43200,"monthly_device_seconds":432000,"device_modes":["720p_single"]}
            """.utf8))
        let usage = try JSONDecoder().decode(UserUsage.self, from: Data("""
            {"plan":"beam","month":"2026-11","used_seconds":0,"remaining_seconds":144000,"available_by_mode":[],"device_limit_seconds":432000,"device_used_seconds":100,"device_remaining_seconds":1000,"device_local_seconds":40}
            """.utf8))
        XCTAssertEqual(usage.deviceLocalSeconds, 40)
        // 2026-11-01 00:10 KST is November. 2026-10-31 23:50 KST is October.
        let november = Date(timeIntervalSince1970: 1_793_459_400)
        let october = Date(timeIntervalSince1970: 1_793_458_200)
        let takes = [recording(at: november, seconds: 60), recording(at: october, seconds: 30)]
            .compactMap { LocalTakeUsage(recording: $0, deviceID: "device-1") }

        let snapshot = PlanSnapshot(plan: plan, usage: usage).addingPendingLocalTakes(takes)
        XCTAssertEqual(snapshot.pendingLocalSeconds, 60)
        XCTAssertEqual(snapshot.usage.deviceUsedSeconds, 160)
        XCTAssertEqual(snapshot.usage.deviceRemainingSeconds, 940)

        let exhausted = PlanSnapshot(plan: plan, usage: usage).addingPendingLocalTakes(
            [recording(at: november, seconds: 5_000)].compactMap { LocalTakeUsage(recording: $0, deviceID: "device-1") })
        XCTAssertEqual(exhausted.usage.deviceRemainingSeconds, 0)
        XCTAssertNotNil(exhausted.preparationBlockMessage(.hdSingle, processing: .onDevice))
    }
}
