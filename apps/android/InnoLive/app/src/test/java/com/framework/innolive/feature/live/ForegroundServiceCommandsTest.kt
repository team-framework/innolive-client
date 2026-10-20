package com.framework.innolive.feature.live

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceCommandsTest {
    private val calls = mutableListOf<String>()

    private fun commands(allowed: Boolean = true) = ForegroundServiceCommands(
        enterForeground = { calls += "foreground"; allowed },
        onStarted = { calls += "started" },
        stopSelf = { calls += "stop:$it" },
    )

    @Test fun stopRightAfterStartEntersForegroundBeforeStopping() {
        val commands = commands()
        commands.onStartCommand(stop = false, startId = 1)
        commands.onStartCommand(stop = true, startId = 2)
        assertEquals(listOf("foreground", "started", "stop:2"), calls)
    }

    @Test fun stopAsTheFirstCommandStillEntersForeground() {
        commands().onStartCommand(stop = true, startId = 1)
        // startForegroundService로 시작했을 수 있으므로 멈추기 전에 반드시 foreground로 올린다.
        assertEquals(listOf("foreground", "stop:1"), calls)
    }

    @Test fun rejectedForegroundStopsThatCommandOnly() {
        commands(allowed = false).onStartCommand(stop = false, startId = 3)
        assertEquals(listOf("foreground", "stop:3"), calls)
    }

    @Test fun repeatedStartsKeepTheServiceInForeground() {
        val commands = commands()
        commands.onStartCommand(stop = false, startId = 1)
        commands.onStartCommand(stop = false, startId = 2)
        assertEquals(listOf("foreground", "started", "started"), calls)
    }

    @Test fun stopFallsBackToStopServiceWhenTheCommandCannotBeSent() {
        ForegroundServiceCommands.requestStop(
            sendStop = { calls += "send"; throw IllegalStateException("app is in background") },
            stopService = { calls += "stopService" },
        )
        ForegroundServiceCommands.requestStop(sendStop = { calls += "send" }, stopService = { calls += "stopService" })
        // 명령을 보낼 수 있으면 stopService를 부르지 않는다.
        assertEquals(listOf("send", "stopService", "send"), calls)
    }
}
