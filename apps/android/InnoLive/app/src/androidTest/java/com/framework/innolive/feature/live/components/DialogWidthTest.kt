package com.framework.innolive.feature.live.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DialogWidthTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun wideParentStillCapsPlatformAndBroadcastDialogs() {
        rule.setContent {
            Box(Modifier.requiredWidth(800.dp)) {
                Box(Modifier.cappedDialogWidth(360.dp).height(40.dp).testTag("platform"))
                Box(Modifier.cappedDialogWidth(400.dp).height(40.dp).testTag("broadcast"))
            }
        }

        val platform = rule.onNodeWithTag("platform").getUnclippedBoundsInRoot()
        val broadcast = rule.onNodeWithTag("broadcast").getUnclippedBoundsInRoot()
        assertTrue(platform.right - platform.left <= 360.dp)
        assertTrue(broadcast.right - broadcast.left <= 400.dp)
    }
}
