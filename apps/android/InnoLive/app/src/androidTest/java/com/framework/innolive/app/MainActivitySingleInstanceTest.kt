package com.framework.innolive.app

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivitySingleInstanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After fun closeActivities() {
        instrumentation.runOnMainSync { liveMainActivities().forEach(Activity::finish) }
    }

    /** 개발 도구처럼 컴포넌트로 연 뒤 런처 아이콘으로 다시 열어도 아래에 이전 화면이 남지 않아야 합니다. */
    @Test fun launchingFromDifferentEntryPointsKeepsOneMainActivity() {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitForMainActivities(1)
        context.startActivity(
            checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        Thread.sleep(1_500)
        instrumentation.waitForIdleSync()

        var count = 0
        instrumentation.runOnMainSync { count = liveMainActivities().size }
        assertEquals(1, count)
    }

    private fun waitForMainActivities(expected: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var count = 0
            instrumentation.runOnMainSync { count = liveMainActivities().size }
            if (count >= expected) return
            Thread.sleep(100)
        }
    }

    private fun liveMainActivities(): List<Activity> {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        return listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
            .flatMap { monitor.getActivitiesInStage(it) }
            .filterIsInstance<MainActivity>()
            .distinct()
    }
}
