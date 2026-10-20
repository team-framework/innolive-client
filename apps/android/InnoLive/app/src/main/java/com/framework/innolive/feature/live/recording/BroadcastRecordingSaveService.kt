package com.framework.innolive.feature.live.recording

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.framework.innolive.R

/**
 * 방송 영상 저장이 끝날 때까지 프로세스를 유지하는 foreground service입니다(iOS의 background task와 같은 역할).
 *
 * 직접 송출의 [com.framework.innolive.feature.live.direct.lifecycle.DirectBroadcastService]는 `microphone`
 * 타입이고 방송 종료와 함께 멈추며 서버 중계 모드에서는 쓰지 않으므로, 저장에는 마이크를 붙잡지 않는 짧은
 * 서비스를 따로 씁니다. Android 14 이상은 `shortService`(약 3분, 별도 권한 없음)이고 그 아래는 타입 없이
 * 시작합니다. 시간이 끝나면 서비스만 멈추고 저장 작업은 계속합니다.
 */
class BroadcastRecordingSaveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        return START_NOT_STICKY
    }

    @Deprecated("Android 15부터 onTimeout(Int, Int)이 대신 불립니다.")
    override fun onTimeout(startId: Int) {
        RecordingLog.w("save_service_timeout")
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        RecordingLog.w("save_service_timeout type=$fgsType")
        stopSelf()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else 0,
        )
        true
    } catch (error: Exception) {
        RecordingLog.w("save_service_rejected type=${error.javaClass.simpleName}")
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
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.recording_save_notification_channel))
                .setShowBadge(false)
                .build(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.recording_save_notification_title))
            .setContentText(getString(R.string.recording_save_notification_text))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    internal companion object {
        private const val CHANNEL_ID = "recording_save"
        private const val NOTIFICATION_ID = 4_902
        private const val WAKE_LOCK_TAG = "InnoLive:RecordingSave"
        /** shortService 제한(약 3분)보다 조금 길게 잡아 놓친 해제가 있어도 풀리게 합니다. */
        private const val MAX_WAKE_LOCK_MILLIS = 4L * 60 * 1000

        fun intent(context: Context): Intent = Intent(context, BroadcastRecordingSaveService::class.java)
    }
}

/** 저장하는 동안 프로세스를 유지합니다. [acquire]가 돌려준 값을 닫으면 풀립니다. */
internal fun interface RecordingKeepAlive {
    fun acquire(): AutoCloseable
}

/**
 * [BroadcastRecordingSaveService]를 참조 횟수로 켜고 끕니다. 이전 방송 저장과 새 방송 저장이 겹쳐도 서비스는
 * 하나입니다. 앱이 이미 백그라운드라 시작할 수 없으면 저장은 프로세스가 살아 있는 동안만 이어집니다.
 */
internal class ServiceRecordingKeepAlive(context: Context) : RecordingKeepAlive {
    private val context = context.applicationContext

    override fun acquire(): AutoCloseable {
        synchronized(lock) {
            if (holders++ == 0) {
                started = try {
                    ContextCompat.startForegroundService(context, BroadcastRecordingSaveService.intent(context))
                    true
                } catch (error: Exception) {
                    RecordingLog.w("save_service_not_started type=${error.javaClass.simpleName}")
                    false
                }
            }
        }
        var released = false
        return AutoCloseable {
            synchronized(lock) {
                if (released) return@AutoCloseable
                released = true
                if (--holders == 0 && started) {
                    started = false
                    runCatching { context.stopService(BroadcastRecordingSaveService.intent(context)) }
                }
            }
        }
    }

    private companion object {
        val lock = Any()
        var holders = 0
        var started = false
    }
}
