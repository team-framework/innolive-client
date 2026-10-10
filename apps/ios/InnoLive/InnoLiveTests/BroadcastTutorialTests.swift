import Foundation
import XCTest

@testable import InnoLive

@MainActor
final class BroadcastTutorialTests: XCTestCase {
    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "broadcast-tutorial-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    // MARK: - Policy

    func testIdleHomeAsksToOpenPreparation() {
        XCTAssertEqual(stage(.init()), .init(step: .openPreparation, host: .home))
    }

    func testOpenSheetWithoutConnectedAccountAsksToConnect() {
        XCTAssertEqual(
            stage(.init(isSettingsSheetPresented: true, selectedAccountsConnected: false)),
            .init(step: .connectAccount, host: .settingsSheet)
        )
    }

    func testOpenSheetWithConnectedAccountAsksToStartPreparation() {
        XCTAssertEqual(
            stage(.init(isSettingsSheetPresented: true, selectedAccountsConnected: true)),
            .init(step: .startPreparation, host: .settingsSheet)
        )
    }

    func testRunningPreparationWaitsInTheSheet() {
        let running = BroadcastPreparationStatus(phase: .connectingServer)

        XCTAssertEqual(
            stage(.init(isSettingsSheetPresented: true, selectedAccountsConnected: true, preparation: running)),
            .init(step: .waitForPreparation, host: .settingsSheet)
        )
    }

    func testFailedPreparationAsksToRetryWhereTheUserIs() {
        let failed = BroadcastPreparationStatus(phase: .savingSettings, failedPhase: .savingSettings)

        XCTAssertEqual(
            stage(.init(isSettingsSheetPresented: true, selectedAccountsConnected: true, preparation: failed)),
            .init(step: .retryPreparation, host: .settingsSheet)
        )
        XCTAssertEqual(
            stage(.init(selectedAccountsConnected: true, preparation: failed)),
            .init(step: .retryPreparation, host: .home)
        )
    }

    func testPreparedSessionOnHomeAsksToGoLive() {
        XCTAssertEqual(
            stage(.init(selectedAccountsConnected: true, phase: .prepared)),
            .init(step: .goLive, host: .home)
        )
    }

    func testPreparedSessionWithSheetStillOpenKeepsWaiting() {
        XCTAssertEqual(
            stage(.init(isSettingsSheetPresented: true, selectedAccountsConnected: true, phase: .prepared)),
            .init(step: .waitForPreparation, host: .settingsSheet)
        )
    }

    func testClosingTheSheetAfterCancelReturnsToTheFirstStep() {
        XCTAssertEqual(
            stage(.init(isSettingsSheetPresented: false, selectedAccountsConnected: true, preparation: nil, phase: .idle)),
            .init(step: .openPreparation, host: .home)
        )
    }

    func testGoingLiveOrLiveEndsTheGuide() {
        XCTAssertNil(stage(.init(selectedAccountsConnected: true, phase: .goingLive)))
        XCTAssertNil(stage(.init(selectedAccountsConnected: true, phase: .live, hasStartedBroadcast: true)))
    }

    func testProgressCountsTheAccountStepOnlyWhenNeeded() {
        XCTAssertEqual(BroadcastTutorialPolicy.progress(for: .openPreparation, includesAccountStep: true), .init(index: 1, total: 5))
        XCTAssertEqual(BroadcastTutorialPolicy.progress(for: .connectAccount, includesAccountStep: true), .init(index: 2, total: 5))
        XCTAssertEqual(BroadcastTutorialPolicy.progress(for: .startPreparation, includesAccountStep: true), .init(index: 3, total: 5))
        XCTAssertEqual(BroadcastTutorialPolicy.progress(for: .retryPreparation, includesAccountStep: true), .init(index: 4, total: 5))
        XCTAssertEqual(BroadcastTutorialPolicy.progress(for: .goLive, includesAccountStep: true), .init(index: 5, total: 5))

        XCTAssertEqual(BroadcastTutorialPolicy.progress(for: .startPreparation, includesAccountStep: false), .init(index: 2, total: 4))
        XCTAssertEqual(BroadcastTutorialPolicy.progress(for: .waitForPreparation, includesAccountStep: false), .init(index: 3, total: 4))
        XCTAssertEqual(BroadcastTutorialPolicy.progress(for: .goLive, includesAccountStep: false), .init(index: 4, total: 4))
    }

    // MARK: - Coordinator

    func testFirstLaunchStartsAtTheFirstStep() {
        let tutorial = makeCoordinator()

        tutorial.startIfNeeded(.init(selectedAccountsConnected: false))

        XCTAssertEqual(tutorial.stage, .init(step: .openPreparation, host: .home))
        XCTAssertEqual(tutorial.progress, .init(index: 1, total: 5))
    }

    func testGuideDoesNotStartAgainAfterSkipping() {
        let tutorial = makeCoordinator()
        tutorial.startIfNeeded(.init())
        tutorial.skip()

        let relaunched = makeCoordinator()
        relaunched.startIfNeeded(.init())

        XCTAssertNil(tutorial.stage)
        XCTAssertNil(relaunched.stage)
    }

    func testGuideDoesNotAutoStartWhileASessionIsInProgress() {
        let tutorial = makeCoordinator()

        tutorial.startIfNeeded(.init(selectedAccountsConnected: true, phase: .prepared))

        XCTAssertNil(tutorial.stage)
    }

    func testGuideFollowsTheAppStateAndRecordsCompletionWhenGoingLive() {
        let tutorial = makeCoordinator()
        tutorial.startIfNeeded(.init(selectedAccountsConnected: true))

        tutorial.update(.init(isSettingsSheetPresented: true, selectedAccountsConnected: true))
        XCTAssertEqual(tutorial.stage?.step, .startPreparation)
        XCTAssertEqual(tutorial.progress, .init(index: 2, total: 4))

        tutorial.update(.init(selectedAccountsConnected: true, phase: .prepared))
        XCTAssertEqual(tutorial.stage?.step, .goLive)

        tutorial.update(.init(selectedAccountsConnected: true, phase: .goingLive))
        XCTAssertNil(tutorial.stage)
        XCTAssertTrue(BroadcastTutorialStore(userDefaults: defaults).hasFinishedPreparationGuide)
    }

    func testAccountStepStaysCountedAfterTheAccountIsConnected() {
        let tutorial = makeCoordinator()
        tutorial.startIfNeeded(.init(selectedAccountsConnected: false))
        tutorial.update(.init(isSettingsSheetPresented: true, selectedAccountsConnected: false))

        tutorial.update(.init(isSettingsSheetPresented: true, selectedAccountsConnected: true))

        XCTAssertEqual(tutorial.stage?.step, .startPreparation)
        XCTAssertEqual(tutorial.progress, .init(index: 3, total: 5))
    }

    func testUpdatesAreIgnoredWhenTheGuideIsNotRunning() {
        let tutorial = makeCoordinator()

        tutorial.update(.init(isSettingsSheetPresented: true, selectedAccountsConnected: true))

        XCTAssertNil(tutorial.stage)
    }

    func testFinishingAtTheLastStepRecordsCompletion() {
        let tutorial = makeCoordinator()
        tutorial.startIfNeeded(.init(selectedAccountsConnected: true))
        tutorial.update(.init(selectedAccountsConnected: true, phase: .prepared))

        tutorial.finish()

        XCTAssertNil(tutorial.stage)
        XCTAssertTrue(BroadcastTutorialStore(userDefaults: defaults).hasFinishedPreparationGuide)
    }

    func testRestartShowsTheGuideAgainFromTheCurrentState() {
        let tutorial = makeCoordinator()
        tutorial.startIfNeeded(.init(selectedAccountsConnected: true))
        tutorial.skip()

        tutorial.restart(.init(selectedAccountsConnected: true))

        XCTAssertEqual(tutorial.stage, .init(step: .openPreparation, host: .home))
        XCTAssertFalse(BroadcastTutorialStore(userDefaults: defaults).hasFinishedPreparationGuide)
    }

    func testRestartAfterAFailedPreparationShowsTheRetryStep() {
        let tutorial = makeCoordinator()
        let failed = BroadcastPreparationStatus(phase: .connectingServer, failedPhase: .connectingServer)

        tutorial.restart(.init(selectedAccountsConnected: true, preparation: failed))

        XCTAssertEqual(tutorial.stage, .init(step: .retryPreparation, host: .home))
    }

    func testStartAndRestartUseTheLatestReportedState() {
        let tutorial = makeCoordinator()
        tutorial.update(.init(selectedAccountsConnected: true, phase: .prepared))

        tutorial.startIfNeeded()
        XCTAssertNil(tutorial.stage)

        tutorial.update(.init(selectedAccountsConnected: true, phase: .idle))
        tutorial.startIfNeeded()
        XCTAssertEqual(tutorial.stage, .init(step: .openPreparation, host: .home))

        tutorial.skip()
        tutorial.restart()
        XCTAssertEqual(tutorial.stage, .init(step: .openPreparation, host: .home))
    }

    func testLiveStatusTipShowsOnceWhenTheFirstBroadcastStarts() {
        BroadcastTutorialStore(userDefaults: defaults).recordPreparationGuideFinished()
        let tutorial = makeCoordinator()

        tutorial.update(.init(selectedAccountsConnected: true, phase: .live, hasStartedBroadcast: true))
        XCTAssertTrue(tutorial.isShowingLiveStatusTip)

        tutorial.dismissLiveStatusTip()
        tutorial.update(.init(selectedAccountsConnected: true, phase: .idle))
        tutorial.update(.init(selectedAccountsConnected: true, phase: .live, hasStartedBroadcast: true))

        XCTAssertFalse(tutorial.isShowingLiveStatusTip)
    }

    func testGuideEndingAtGoLiveHandsOverToTheLiveStatusTip() {
        let tutorial = makeCoordinator()
        tutorial.startIfNeeded(.init(selectedAccountsConnected: true))
        tutorial.update(.init(selectedAccountsConnected: true, phase: .prepared))

        tutorial.update(.init(selectedAccountsConnected: true, phase: .live, hasStartedBroadcast: true))

        XCTAssertNil(tutorial.stage)
        XCTAssertTrue(tutorial.isShowingLiveStatusTip)
    }

    func testLiveStatusTipHidesWhenTheBroadcastEndsWithoutBeingDismissed() {
        let tutorial = makeCoordinator()
        tutorial.update(.init(selectedAccountsConnected: true, phase: .live, hasStartedBroadcast: true))

        tutorial.update(.init(selectedAccountsConnected: true, phase: .idle))

        XCTAssertFalse(tutorial.isShowingLiveStatusTip)
        XCTAssertFalse(BroadcastTutorialStore(userDefaults: defaults).hasSeenLiveStatusTip)
    }

    // MARK: - Helpers

    private func stage(_ snapshot: BroadcastTutorialSnapshot) -> BroadcastTutorialStage? {
        BroadcastTutorialPolicy.stage(for: snapshot)
    }

    private func makeCoordinator() -> BroadcastTutorialCoordinator {
        BroadcastTutorialCoordinator(store: BroadcastTutorialStore(userDefaults: defaults))
    }
}
