package com.framework.innolive.feature.live

/**
 * `startForegroundService`로 켜는 서비스의 시작·종료 명령입니다.
 *
 * 서비스가 `startForeground`를 부르기 전에 `stopService`가 먼저 처리되면 시스템이 앱을 종료합니다
 * ("did not then call startForeground"). 그래서 끌 때도 [requestStop]으로 STOP 시작 명령을 보내고, 서비스는
 * [onStartCommand]에서 명령 종류와 관계없이 먼저 foreground로 올린 뒤 STOP이면 스스로 멈춥니다. 시작 명령은
 * 보낸 순서대로 처리되므로 STOP은 앞선 시작 명령 뒤에 옵니다.
 *
 * 서비스의 메인 스레드에서만 부릅니다.
 *
 * @param enterForeground `startForeground`를 부릅니다. 시스템이 거절하면 false입니다.
 * @param onStarted foreground로 올린 시작 명령마다 부릅니다.
 * @param stopSelf `stopSelf(startId)`입니다. 뒤에 온 시작 명령이 있으면 시스템이 서비스를 남깁니다.
 */
internal class ForegroundServiceCommands(
    private val enterForeground: () -> Boolean,
    private val onStarted: () -> Unit,
    private val stopSelf: (Int) -> Unit,
) {
    private var foreground = false

    fun onStartCommand(stop: Boolean, startId: Int) {
        // 이미 foreground면 다시 올리지 않아도 시스템이 이번 시작 명령의 의무를 면제합니다.
        if (!foreground) foreground = enterForeground()
        if (stop || !foreground) stopSelf(startId) else onStarted()
    }

    companion object {
        /**
         * STOP 명령을 보냅니다. 서비스가 이미 멈췄고 앱이 백그라운드라 시작 명령을 보낼 수 없으면
         * [stopService]로 대신 끕니다. 이때는 기다리는 `startForeground` 의무가 없습니다.
         */
        fun requestStop(sendStop: () -> Unit, stopService: () -> Unit) {
            try {
                sendStop()
            } catch (_: IllegalStateException) {
                runCatching { stopService() }
            } catch (_: SecurityException) {
                runCatching { stopService() }
            }
        }
    }
}
