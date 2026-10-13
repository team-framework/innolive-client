package com.framework.innolive.feature.live.tutorial

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.framework.innolive.feature.live.BroadcastState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BroadcastTutorialRecreationTest {
    @get:Rule
    val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test
    fun runningGuideSurvivesActivityRecreation() {
        activity.scenario.onActivity { current ->
            coordinator(current).restart(
                BroadcastTutorialSnapshot(selectedAccountConnected = true, broadcastState = BroadcastState.PREPARED),
            )
        }

        // 화면 회전처럼 Activity를 다시 만든다.
        activity.scenario.recreate()

        activity.scenario.onActivity { recreated ->
            val tutorial = coordinator(recreated)
            assertEquals(
                BroadcastTutorialStage(BroadcastTutorialStep.GO_LIVE, BroadcastTutorialHost.HOME),
                tutorial.stage,
            )
            tutorial.update(
                BroadcastTutorialSnapshot(
                    selectedAccountConnected = true,
                    broadcastState = BroadcastState.LIVE,
                    hasStartedBroadcast = true,
                ),
            )
            assertEquals(null, tutorial.stage)
            tutorial.skip()
        }
    }

    private fun coordinator(activity: ComponentActivity) =
        ViewModelProvider(activity)[BroadcastTutorialViewModel::class.java].coordinator
}
