package com.framework.innolive.feature.live.tutorial

import com.framework.innolive.feature.live.BroadcastState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastTutorialTest {
    private class MemoryStore : BroadcastTutorialStore {
        override var hasFinishedPreparationGuide = false
        override var hasSeenLiveStatusTip = false
    }

    private val store = MemoryStore()

    // Policy

    @Test
    fun idleHomeAsksToOpenPreparation() {
        assertEquals(stage(BroadcastTutorialStep.OPEN_PREPARATION, BroadcastTutorialHost.HOME), policy(snapshot()))
    }

    @Test
    fun openPlatformDialogAsksToChoosePlatform() {
        assertEquals(
            stage(BroadcastTutorialStep.CHOOSE_PLATFORM, BroadcastTutorialHost.PLATFORM_DIALOG),
            policy(snapshot(dialog = BroadcastTutorialDialog.PLATFORM)),
        )
    }

    @Test
    fun openSettingsWithoutConnectedAccountAsksToConnect() {
        assertEquals(
            stage(BroadcastTutorialStep.CONNECT_ACCOUNT, BroadcastTutorialHost.SETTINGS_DIALOG),
            policy(snapshot(dialog = BroadcastTutorialDialog.SETTINGS, connected = false)),
        )
    }

    @Test
    fun openSettingsWithConnectedAccountAsksToStartPreparation() {
        assertEquals(
            stage(BroadcastTutorialStep.START_PREPARATION, BroadcastTutorialHost.SETTINGS_DIALOG),
            policy(snapshot(dialog = BroadcastTutorialDialog.SETTINGS, connected = true)),
        )
    }

    @Test
    fun runningPreparationWaitsOnHome() {
        assertEquals(
            stage(BroadcastTutorialStep.WAIT_FOR_PREPARATION, BroadcastTutorialHost.HOME),
            policy(snapshot(connected = true, preparing = true)),
        )
        assertEquals(
            stage(BroadcastTutorialStep.WAIT_FOR_PREPARATION, BroadcastTutorialHost.HOME),
            policy(snapshot(connected = true, state = BroadcastState.PREPARING)),
        )
    }

    @Test
    fun runningPreparationWithSettingsStillOpenKeepsWaitingThere() {
        assertEquals(
            stage(BroadcastTutorialStep.WAIT_FOR_PREPARATION, BroadcastTutorialHost.SETTINGS_DIALOG),
            policy(snapshot(dialog = BroadcastTutorialDialog.SETTINGS, connected = true, state = BroadcastState.SAVING_SETTINGS)),
        )
    }

    @Test
    fun failedPreparationAsksToRetryWhereTheUserIs() {
        assertEquals(
            stage(BroadcastTutorialStep.RETRY_PREPARATION, BroadcastTutorialHost.SETTINGS_DIALOG),
            policy(snapshot(dialog = BroadcastTutorialDialog.SETTINGS, connected = true, state = BroadcastState.FAILED)),
        )
        assertEquals(
            stage(BroadcastTutorialStep.RETRY_PREPARATION, BroadcastTutorialHost.HOME),
            policy(snapshot(connected = true, state = BroadcastState.FAILED)),
        )
    }

    @Test
    fun preparedSessionOnHomeAsksToGoLive() {
        assertEquals(
            stage(BroadcastTutorialStep.GO_LIVE, BroadcastTutorialHost.HOME),
            policy(snapshot(connected = true, state = BroadcastState.PREPARED)),
        )
    }

    @Test
    fun cancelledPreparationReturnsToTheFirstStep() {
        assertEquals(
            stage(BroadcastTutorialStep.OPEN_PREPARATION, BroadcastTutorialHost.HOME),
            policy(snapshot(connected = true, state = BroadcastState.CANCELLING_PREPARATION)),
        )
    }

    @Test
    fun goingLiveOrLiveEndsTheGuide() {
        assertNull(policy(snapshot(connected = true, state = BroadcastState.GOING_LIVE)))
        assertNull(policy(snapshot(connected = true, state = BroadcastState.LIVE, started = true)))
        assertNull(policy(snapshot(connected = true, state = BroadcastState.PAUSED, started = true)))
    }

    @Test
    fun progressCountsTheAccountStepOnlyWhenNeeded() {
        assertEquals(progress(1, 5), BroadcastTutorialPolicy.progress(BroadcastTutorialStep.OPEN_PREPARATION, true))
        assertEquals(progress(1, 5), BroadcastTutorialPolicy.progress(BroadcastTutorialStep.CHOOSE_PLATFORM, true))
        assertEquals(progress(2, 5), BroadcastTutorialPolicy.progress(BroadcastTutorialStep.CONNECT_ACCOUNT, true))
        assertEquals(progress(3, 5), BroadcastTutorialPolicy.progress(BroadcastTutorialStep.START_PREPARATION, true))
        assertEquals(progress(4, 5), BroadcastTutorialPolicy.progress(BroadcastTutorialStep.RETRY_PREPARATION, true))
        assertEquals(progress(5, 5), BroadcastTutorialPolicy.progress(BroadcastTutorialStep.GO_LIVE, true))

        assertEquals(progress(2, 4), BroadcastTutorialPolicy.progress(BroadcastTutorialStep.START_PREPARATION, false))
        assertEquals(progress(3, 4), BroadcastTutorialPolicy.progress(BroadcastTutorialStep.WAIT_FOR_PREPARATION, false))
        assertEquals(progress(4, 4), BroadcastTutorialPolicy.progress(BroadcastTutorialStep.GO_LIVE, false))
    }

    @Test
    fun dialogStepsPointAtTheirOwnControls() {
        assertEquals(
            BroadcastTutorialAnchor.START_PREPARATION,
            BroadcastTutorialStep.RETRY_PREPARATION.anchor(BroadcastTutorialHost.SETTINGS_DIALOG),
        )
        assertEquals(
            BroadcastTutorialAnchor.PRIMARY_BUTTON,
            BroadcastTutorialStep.RETRY_PREPARATION.anchor(BroadcastTutorialHost.HOME),
        )
        assertNull(BroadcastTutorialStep.WAIT_FOR_PREPARATION.anchor(BroadcastTutorialHost.SETTINGS_DIALOG))
    }

    // Coordinator

    @Test
    fun firstLaunchStartsAtTheFirstStep() {
        val tutorial = coordinator()

        tutorial.startIfNeeded(snapshot(connected = false))

        assertEquals(stage(BroadcastTutorialStep.OPEN_PREPARATION, BroadcastTutorialHost.HOME), tutorial.stage)
        assertEquals(progress(1, 5), tutorial.progress)
    }

    @Test
    fun guideDoesNotStartAgainAfterSkipping() {
        val tutorial = coordinator()
        tutorial.startIfNeeded(snapshot())
        tutorial.skip()

        val relaunched = coordinator()
        relaunched.startIfNeeded(snapshot())

        assertNull(tutorial.stage)
        assertNull(relaunched.stage)
    }

    @Test
    fun guideDoesNotAutoStartWhileASessionIsInProgress() {
        val tutorial = coordinator()

        tutorial.startIfNeeded(snapshot(connected = true, state = BroadcastState.PREPARED))

        assertNull(tutorial.stage)
    }

    @Test
    fun guideFollowsTheAppStateAndRecordsCompletionWhenGoingLive() {
        val tutorial = coordinator()
        tutorial.startIfNeeded(snapshot(connected = true))

        tutorial.update(snapshot(dialog = BroadcastTutorialDialog.SETTINGS, connected = true))
        assertEquals(BroadcastTutorialStep.START_PREPARATION, tutorial.stage?.step)
        assertEquals(progress(2, 4), tutorial.progress)

        tutorial.update(snapshot(connected = true, state = BroadcastState.PREPARED))
        assertEquals(BroadcastTutorialStep.GO_LIVE, tutorial.stage?.step)

        tutorial.update(snapshot(connected = true, state = BroadcastState.GOING_LIVE))
        assertNull(tutorial.stage)
        assertTrue(store.hasFinishedPreparationGuide)
    }

    @Test
    fun accountStepStaysCountedAfterTheAccountIsConnected() {
        val tutorial = coordinator()
        tutorial.startIfNeeded(snapshot(connected = false))
        tutorial.update(snapshot(dialog = BroadcastTutorialDialog.SETTINGS, connected = false))

        tutorial.update(snapshot(dialog = BroadcastTutorialDialog.SETTINGS, connected = true))

        assertEquals(BroadcastTutorialStep.START_PREPARATION, tutorial.stage?.step)
        assertEquals(progress(3, 5), tutorial.progress)
    }

    @Test
    fun updatesAreIgnoredWhenTheGuideIsNotRunning() {
        val tutorial = coordinator()

        tutorial.update(snapshot(dialog = BroadcastTutorialDialog.SETTINGS, connected = true))

        assertNull(tutorial.stage)
    }

    @Test
    fun finishingAtTheLastStepRecordsCompletion() {
        val tutorial = coordinator()
        tutorial.startIfNeeded(snapshot(connected = true))
        tutorial.update(snapshot(connected = true, state = BroadcastState.PREPARED))

        tutorial.finish()

        assertNull(tutorial.stage)
        assertTrue(store.hasFinishedPreparationGuide)
    }

    @Test
    fun restartShowsTheGuideAgainFromTheCurrentState() {
        val tutorial = coordinator()
        tutorial.startIfNeeded(snapshot(connected = true))
        tutorial.skip()

        tutorial.restart(snapshot(connected = true))

        assertEquals(stage(BroadcastTutorialStep.OPEN_PREPARATION, BroadcastTutorialHost.HOME), tutorial.stage)
        assertFalse(store.hasFinishedPreparationGuide)
    }

    @Test
    fun restartAfterAFailedPreparationShowsTheRetryStep() {
        val tutorial = coordinator()

        tutorial.restart(snapshot(connected = true, state = BroadcastState.FAILED))

        assertEquals(stage(BroadcastTutorialStep.RETRY_PREPARATION, BroadcastTutorialHost.HOME), tutorial.stage)
    }

    @Test
    fun startAndRestartUseTheLatestReportedState() {
        val tutorial = coordinator()
        tutorial.update(snapshot(connected = true, state = BroadcastState.PREPARED))

        tutorial.startIfNeeded()
        assertNull(tutorial.stage)

        tutorial.update(snapshot(connected = true))
        tutorial.startIfNeeded()
        assertEquals(stage(BroadcastTutorialStep.OPEN_PREPARATION, BroadcastTutorialHost.HOME), tutorial.stage)

        tutorial.skip()
        tutorial.restart()
        assertEquals(stage(BroadcastTutorialStep.OPEN_PREPARATION, BroadcastTutorialHost.HOME), tutorial.stage)
    }

    @Test
    fun liveStatusTipShowsOnceWhenTheFirstBroadcastStarts() {
        store.hasFinishedPreparationGuide = true
        val tutorial = coordinator()

        tutorial.update(snapshot(connected = true, state = BroadcastState.LIVE, started = true))
        assertTrue(tutorial.isShowingLiveStatusTip)

        tutorial.dismissLiveStatusTip()
        tutorial.update(snapshot(connected = true))
        tutorial.update(snapshot(connected = true, state = BroadcastState.LIVE, started = true))

        assertFalse(tutorial.isShowingLiveStatusTip)
    }

    @Test
    fun guideEndingAtGoLiveHandsOverToTheLiveStatusTip() {
        val tutorial = coordinator()
        tutorial.startIfNeeded(snapshot(connected = true))
        tutorial.update(snapshot(connected = true, state = BroadcastState.PREPARED))

        tutorial.update(snapshot(connected = true, state = BroadcastState.LIVE, started = true))

        assertNull(tutorial.stage)
        assertTrue(tutorial.isShowingLiveStatusTip)
    }

    @Test
    fun liveStatusTipHidesWhenTheBroadcastEndsWithoutBeingDismissed() {
        val tutorial = coordinator()
        tutorial.update(snapshot(connected = true, state = BroadcastState.LIVE, started = true))

        tutorial.update(snapshot(connected = true))

        assertFalse(tutorial.isShowingLiveStatusTip)
        assertFalse(store.hasSeenLiveStatusTip)
    }

    @Test
    fun startingALiveBroadcastWithoutTheGuideStillRecordsCompletion() {
        val tutorial = coordinator()

        tutorial.update(snapshot(connected = true, state = BroadcastState.PREPARED))
        tutorial.update(snapshot(connected = true, state = BroadcastState.LIVE, started = true))
        tutorial.update(snapshot(connected = true))
        tutorial.startIfNeeded()

        assertTrue(store.hasFinishedPreparationGuide)
        assertNull(tutorial.stage)
    }

    // Helpers

    private fun coordinator() = BroadcastTutorialCoordinator(store)

    private fun policy(snapshot: BroadcastTutorialSnapshot) = BroadcastTutorialPolicy.stage(snapshot)

    private fun stage(step: BroadcastTutorialStep, host: BroadcastTutorialHost) = BroadcastTutorialStage(step, host)

    private fun progress(index: Int, total: Int) = BroadcastTutorialProgress(index, total)

    private fun snapshot(
        dialog: BroadcastTutorialDialog = BroadcastTutorialDialog.NONE,
        connected: Boolean = false,
        state: BroadcastState = BroadcastState.IDLE,
        preparing: Boolean = false,
        started: Boolean = false,
    ) = BroadcastTutorialSnapshot(
        openDialog = dialog,
        selectedAccountConnected = connected,
        broadcastState = state,
        isPreparingBroadcast = preparing,
        hasStartedBroadcast = started,
    )
}
