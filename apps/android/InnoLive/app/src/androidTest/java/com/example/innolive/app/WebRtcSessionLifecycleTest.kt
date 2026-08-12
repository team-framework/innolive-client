package com.example.innolive.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.innolive.feature.live.WebRtcSessionViewModel
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebRtcSessionLifecycleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityRecreationKeepsWebRtcSession() {
        lateinit var sessionBeforeRecreation: WebRtcSessionViewModel
        composeRule.activityRule.scenario.onActivity { activity ->
            sessionBeforeRecreation = ViewModelProvider(activity)[WebRtcSessionViewModel::class.java]
        }

        composeRule.activityRule.scenario.recreate()

        composeRule.activityRule.scenario.onActivity { activity ->
            val sessionAfterRecreation =
                ViewModelProvider(activity)[WebRtcSessionViewModel::class.java]
            assertSame(sessionBeforeRecreation, sessionAfterRecreation)
        }
    }
}
