import Combine
import Foundation

final class BroadcastTutorialStore {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    private enum Key {
        static let preparationGuide = "com.framework.innolive.tutorial.broadcast-preparation.v1"
        static let liveStatusTip = "com.framework.innolive.tutorial.live-status.v1"
    }

    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    var hasFinishedPreparationGuide: Bool {
        userDefaults.bool(forKey: Key.preparationGuide)
    }

    func recordPreparationGuideFinished() {
        userDefaults.set(true, forKey: Key.preparationGuide)
    }

    func resetPreparationGuide() {
        userDefaults.removeObject(forKey: Key.preparationGuide)
    }

    var hasSeenLiveStatusTip: Bool {
        userDefaults.bool(forKey: Key.liveStatusTip)
    }

    func recordLiveStatusTipSeen() {
        userDefaults.set(true, forKey: Key.liveStatusTip)
    }
}

final class BroadcastTutorialCoordinator: ObservableObject {
    // iOS 18 소멸자 충돌을 피한다. docs/ios-version-support.md 참고.
    nonisolated deinit {}

    @Published private(set) var stage: BroadcastTutorialStage?
    @Published private(set) var progress: BroadcastTutorialProgress?
    @Published private(set) var isShowingLiveStatusTip = false

    private let store: BroadcastTutorialStore
    private var isRunning = false
    private var includesAccountStep = true
    private var hadStartedBroadcast = false
    private var lastSnapshot = BroadcastTutorialSnapshot()

    init(store: BroadcastTutorialStore = BroadcastTutorialStore()) {
        self.store = store
    }

    /// 홈 화면은 시트 상태를 모르므로 방송 제어 영역이 마지막으로 알려 준 상태로 시작한다.
    func startIfNeeded() {
        startIfNeeded(lastSnapshot)
    }

    func restart() {
        restart(lastSnapshot)
    }

    /// 처음 쓰는 사용자가 아무 방송도 준비하지 않은 상태일 때만 자동으로 시작한다.
    func startIfNeeded(_ snapshot: BroadcastTutorialSnapshot) {
        guard !isRunning, !store.hasFinishedPreparationGuide,
              BroadcastTutorialPolicy.stage(for: snapshot)?.step == .openPreparation else { return }
        isRunning = true
        update(snapshot)
    }

    /// 사용자가 직접 다시 보기를 고르면 지금 상태에 맞는 단계부터 바로 보여 준다.
    func restart(_ snapshot: BroadcastTutorialSnapshot) {
        store.resetPreparationGuide()
        isRunning = true
        update(snapshot)
    }

    func update(_ snapshot: BroadcastTutorialSnapshot) {
        lastSnapshot = snapshot
        // 준비 안내가 라이브 시작으로 끝난 뒤에 상태 패널 안내를 판단한다.
        if isRunning { advanceGuide(snapshot) }
        updateLiveStatusTip(snapshot)
    }

    private func advanceGuide(_ snapshot: BroadcastTutorialSnapshot) {
        guard let next = BroadcastTutorialPolicy.stage(for: snapshot) else {
            finish()
            return
        }
        // 계정 연결 단계 수는 첫 단계에서만 정한다. 연결을 마친 뒤에도 전체 단계 수가 바뀌지 않게 한다.
        if next.step == .openPreparation {
            includesAccountStep = !snapshot.selectedAccountsConnected
        } else if next.step == .connectAccount {
            includesAccountStep = true
        }
        if stage != next { stage = next }
        let nextProgress = BroadcastTutorialPolicy.progress(for: next.step, includesAccountStep: includesAccountStep)
        if progress != nextProgress { progress = nextProgress }
    }

    func skip() {
        finish()
    }

    func finish() {
        store.recordPreparationGuideFinished()
        isRunning = false
        stage = nil
        progress = nil
    }

    func dismissLiveStatusTip() {
        store.recordLiveStatusTipSeen()
        isShowingLiveStatusTip = false
    }

    private func updateLiveStatusTip(_ snapshot: BroadcastTutorialSnapshot) {
        defer { hadStartedBroadcast = snapshot.hasStartedBroadcast }
        if !snapshot.hasStartedBroadcast {
            if isShowingLiveStatusTip { isShowingLiveStatusTip = false }
            return
        }
        if !hadStartedBroadcast, !isRunning, !store.hasSeenLiveStatusTip {
            isShowingLiveStatusTip = true
        }
    }
}
