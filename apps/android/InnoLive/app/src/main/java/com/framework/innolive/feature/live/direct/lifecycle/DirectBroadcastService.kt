package com.framework.innolive.feature.live.direct.lifecycle

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.framework.innolive.R
import com.framework.innolive.feature.live.ForegroundServiceCommands

/**
 * 직접 송출이 라이브인 동안 프로세스를 유지하는 foreground service입니다. 앱이 백그라운드로 가거나
 * 화면이 꺼져도 RTMP 연결과 하트비트가 계속 돌고, 그동안 송출은 대기 화면과 무음으로 바뀝니다.
 *
 * 타입은 `microphone`입니다. 방송 중 오디오 경로를 백그라운드에서도 유지하는 것이 이 서비스의 목적이고,
 * Android 15의 `dataSync` 6시간 제한이 없습니다. Android 14 이상은 이 타입을 백그라운드에서 시작할 수
 * 없으므로 [ServiceBroadcastKeepAlive]가 golive 직전, 앱이 화면에 있을 때 시작합니다.
 */
class DirectBroadcastService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val commands = ForegroundServiceCommands(::enterForeground, ::acquireWakeLock) { stopSelf(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_BROADCAST) {
            // 진행 중인 방송이 없으면 알림만 남지 않게 서비스를 끝냅니다.
            if (!DirectBroadcastStopRequests.shared.request()) stopSelf()
            return START_NOT_STICKY
        }
        commands.onStartCommand(stop = intent?.action == ACTION_STOP_SERVICE, startId = startId)
        // 프로세스가 죽으면 송출 상태도 사라지므로 다시 시작하지 않습니다. 세션은 다음 실행 때 정리합니다.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 최근 앱에서 지우면 방송 화면이 사라지므로 방송도 끝냅니다.
        DirectBroadcastStopRequests.shared.request()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        true
    } catch (error: SecurityException) {
        // 마이크 권한이 없으면 microphone 타입으로 올릴 수 없습니다. 방송은 화면에 있는 동안만 이어집니다.
        Log.w(TAG, "Foreground service rejected: ${error.javaClass.simpleName}")
        false
    } catch (error: IllegalStateException) {
        // Android 12 이상에서 백그라운드 시작이 막힌 경우입니다.
        Log.w(TAG, "Foreground service not allowed: ${error.javaClass.simpleName}")
        false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MILLIS)
        }
    }

    private fun buildNotification(): Notification {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.direct_broadcast_notification_channel))
                .setShowBadge(false)
                .build(),
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, DirectBroadcastService::class.java).setAction(ACTION_STOP_BROADCAST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?.let { launch ->
                PendingIntent.getActivity(
                    this,
                    REQUEST_OPEN,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.direct_broadcast_notification_title))
            .setContentText(getString(R.string.direct_broadcast_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.direct_broadcast_notification_text)),
            )
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.direct_broadcast_notification_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    internal companion object {
        private const val TAG = "DirectBroadcastService"
        private const val CHANNEL_ID = "direct_broadcast"
        private const val NOTIFICATION_ID = 4_901
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        private const val ACTION_STOP_BROADCAST = "com.framework.innolive.action.STOP_DIRECT_BROADCAST"
        private const val ACTION_STOP_SERVICE = "com.framework.innolive.action.STOP_DIRECT_BROADCAST_SERVICE"
        private const val WAKE_LOCK_TAG = "InnoLive:DirectBroadcast"

        /** 가장 긴 플랫폼 라이브(YouTube 12시간)보다 길게 잡아, 놓친 해제가 있어도 결국 풀리게 합니다. */
        private const val MAX_WAKE_LOCK_MILLIS = 13L * 60 * 60 * 1000

        fun startIntent(context: Context): Intent = Intent(context, DirectBroadcastService::class.java)

        /** 방송이 끝나 서비스를 끕니다. `stopService` 대신 써서 foreground 진입 뒤에 멈춥니다. */
        fun stopIntent(context: Context): Intent = startIntent(context).setAction(ACTION_STOP_SERVICE)
    }
}
