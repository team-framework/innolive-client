package com.framework.innolive.feature.live.direct.lifecycle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.framework.innolive.feature.live.ForegroundServiceCommands
import com.framework.innolive.feature.live.direct.broadcast.BroadcastKeepAlive
import com.framework.innolive.feature.live.direct.broadcast.DirectMediaControl

/**
 * 라이브 동안 [DirectBroadcastService]를 켜 두고, 백그라운드 진입을 대기 화면으로 바꿉니다.
 *
 * 상태 머신은 golive 직전에 [onBroadcastStarting], 종료·실패·close 때 [onBroadcastEnded]를 부르면 됩니다.
 * 두 함수는 어느 스레드에서 불러도 메인 스레드에서 순서대로 처리합니다.
 *
 * @param mediaControl 백그라운드 진입·복귀 때 [com.framework.innolive.feature.live.direct.broadcast.SlateReason.BACKGROUND]를 전달할 곳
 * @param onStopRequested 알림의 "방송 종료"를 눌렀을 때 부를 동작
 */
internal class ServiceBroadcastKeepAlive(
    context: Context,
    mediaControl: DirectMediaControl,
    private val onStopRequested: () -> Unit,
    private val processLifecycle: () -> Lifecycle = { ProcessLifecycleOwner.get().lifecycle },
    private val stopRequests: DirectBroadcastStopRequests = DirectBroadcastStopRequests.shared,
) : BroadcastKeepAlive {
    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleObserver = DirectBroadcastLifecycleObserver(mediaControl)
    private var stopRegistration: AutoCloseable? = null
    private var serviceStarted = false

    override fun onBroadcastStarting() = onMain {
        if (stopRegistration != null) return@onMain
        stopRegistration = stopRequests.register(onStopRequested)
        lifecycleObserver.attach(processLifecycle())
        serviceStarted = try {
            ContextCompat.startForegroundService(context, DirectBroadcastService.startIntent(context))
            true
        } catch (error: IllegalStateException) {
            // 앱이 이미 백그라운드면 시작할 수 없습니다. 방송은 화면에 있는 동안만 이어집니다.
            Log.w(TAG, "Keep-alive service not started: ${error.javaClass.simpleName}")
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "Keep-alive service rejected: ${error.javaClass.simpleName}")
            false
        }
    }

    override fun onBroadcastEnded() = onMain {
        stopRegistration?.close()
        stopRegistration = null
        lifecycleObserver.detach()
        if (serviceStarted) {
            serviceStarted = false
            // golive가 바로 실패하면 서비스가 아직 startForeground 전일 수 있어 STOP 명령으로 끕니다.
            ForegroundServiceCommands.requestStop(
                sendStop = { context.startService(DirectBroadcastService.stopIntent(context)) },
                stopService = { context.stopService(DirectBroadcastService.startIntent(context)) },
            )
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "DirectBroadcastKeepAlive"
    }
}
