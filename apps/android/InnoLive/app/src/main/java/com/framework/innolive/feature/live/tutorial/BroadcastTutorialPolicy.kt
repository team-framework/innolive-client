package com.framework.innolive.feature.live.tutorial

import com.framework.innolive.feature.live.BroadcastState

enum class BroadcastTutorialStep {
    OPEN_PREPARATION,
    CHOOSE_PLATFORM,
    CONNECT_ACCOUNT,
    START_PREPARATION,
    WAIT_FOR_PREPARATION,
    RETRY_PREPARATION,
    GO_LIVE,
}

/** 안내를 그릴 화면. Dialog는 홈과 다른 창이라 각자 안내를 그린다. */
enum class BroadcastTutorialHost {
    HOME,
    PLATFORM_DIALOG,
    SETTINGS_DIALOG,
}

enum class BroadcastTutorialDialog {
    NONE,
    PLATFORM,
    SETTINGS,
}

data class BroadcastTutorialStage(
    val step: BroadcastTutorialStep,
    val host: BroadcastTutorialHost,
)

data class BroadcastTutorialProgress(
    val index: Int,
    val total: Int,
)

data class BroadcastTutorialSnapshot(
    val openDialog: BroadcastTutorialDialog = BroadcastTutorialDialog.NONE,
    val selectedAccountConnected: Boolean = false,
    val broadcastState: BroadcastState = BroadcastState.IDLE,
    val isPreparingBroadcast: Boolean = false,
    val hasStartedBroadcast: Boolean = false,
)

/**
 * 안내 단계는 사용자가 누른 기록이 아니라 지금 앱 상태로 정한다.
 * Dialog를 닫거나 준비를 취소해도 맞는 단계로 자연스럽게 돌아간다.
 */
object BroadcastTutorialPolicy {
    private val endedStates = setOf(
        BroadcastState.GOING_LIVE,
        BroadcastState.LIVE,
        BroadcastState.PAUSING,
        BroadcastState.PAUSED,
        BroadcastState.RESUMING,
        BroadcastState.STOPPING,
    )
    private val preparingStates = setOf(
        BroadcastState.SAVING_SETTINGS,
        BroadcastState.PREPARING,
    )

    fun stage(snapshot: BroadcastTutorialSnapshot): BroadcastTutorialStage? {
        if (snapshot.hasStartedBroadcast || snapshot.broadcastState in endedStates) return null
        val isPreparing = snapshot.isPreparingBroadcast || snapshot.broadcastState in preparingStates
        return when (snapshot.openDialog) {
            BroadcastTutorialDialog.PLATFORM -> BroadcastTutorialStage(
                BroadcastTutorialStep.CHOOSE_PLATFORM,
                BroadcastTutorialHost.PLATFORM_DIALOG,
            )
            BroadcastTutorialDialog.SETTINGS -> BroadcastTutorialStage(
                when {
                    snapshot.broadcastState == BroadcastState.FAILED -> BroadcastTutorialStep.RETRY_PREPARATION
                    isPreparing || snapshot.broadcastState == BroadcastState.PREPARED ->
                        BroadcastTutorialStep.WAIT_FOR_PREPARATION
                    snapshot.selectedAccountConnected -> BroadcastTutorialStep.START_PREPARATION
                    else -> BroadcastTutorialStep.CONNECT_ACCOUNT
                },
                BroadcastTutorialHost.SETTINGS_DIALOG,
            )
            BroadcastTutorialDialog.NONE -> BroadcastTutorialStage(
                when {
                    snapshot.broadcastState == BroadcastState.FAILED -> BroadcastTutorialStep.RETRY_PREPARATION
                    snapshot.broadcastState == BroadcastState.PREPARED -> BroadcastTutorialStep.GO_LIVE
                    isPreparing -> BroadcastTutorialStep.WAIT_FOR_PREPARATION
                    else -> BroadcastTutorialStep.OPEN_PREPARATION
                },
                BroadcastTutorialHost.HOME,
            )
        }
    }

    /** 플랫폼 선택은 방송 준비 버튼을 누른 뒤 이어지는 같은 단계로 센다. */
    fun progress(step: BroadcastTutorialStep, includesAccountStep: Boolean): BroadcastTutorialProgress {
        val accountOffset = if (includesAccountStep) 1 else 0
        val index = when (step) {
            BroadcastTutorialStep.OPEN_PREPARATION,
            BroadcastTutorialStep.CHOOSE_PLATFORM -> 1
            BroadcastTutorialStep.CONNECT_ACCOUNT -> 2
            BroadcastTutorialStep.START_PREPARATION -> 2 + accountOffset
            BroadcastTutorialStep.WAIT_FOR_PREPARATION,
            BroadcastTutorialStep.RETRY_PREPARATION -> 3 + accountOffset
            BroadcastTutorialStep.GO_LIVE -> 4 + accountOffset
        }
        return BroadcastTutorialProgress(index, 4 + accountOffset)
    }
}
