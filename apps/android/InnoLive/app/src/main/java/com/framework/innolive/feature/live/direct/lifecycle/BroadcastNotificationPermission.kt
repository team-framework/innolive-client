package com.framework.innolive.feature.live.direct.lifecycle

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.framework.innolive.R

/** 직접 송출 진행 중 알림을 보여 주기 위해 할 일입니다. */
internal enum class NotificationPermissionAction {
    /** 이미 알림을 보여 줄 수 있습니다. */
    READY,

    /** 권한 요청 창을 띄웁니다. */
    REQUEST,

    /** 거부됐거나 앱 알림이 꺼져 있습니다. 방송은 그대로 진행하고 알림이 보이지 않는다고 안내합니다. */
    SHOW_GUIDANCE,
}

/**
 * 알림 권한 처리 방식을 고릅니다. Android 13 미만은 런타임 권한이 없고, 이 화면에서 이미 한 번
 * 요청했으면 다시 띄우지 않습니다. 시스템이 다시 물어볼 수 있다고 할 때만([shouldShowRationale]) 재요청합니다.
 */
internal fun notificationPermissionAction(
    sdkInt: Int,
    notificationsEnabled: Boolean,
    permissionGranted: Boolean,
    requestedBefore: Boolean,
    shouldShowRationale: Boolean,
): NotificationPermissionAction = when {
    notificationsEnabled && (sdkInt < Build.VERSION_CODES.TIRAMISU || permissionGranted) ->
        NotificationPermissionAction.READY
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !permissionGranted &&
        (!requestedBefore || shouldShowRationale) -> NotificationPermissionAction.REQUEST
    else -> NotificationPermissionAction.SHOW_GUIDANCE
}

/** 방송 준비 때 알림 권한을 확인합니다. 결과와 관계없이 방송은 진행합니다. */
internal class BroadcastNotificationPermission(
    private val context: Context,
    private val launch: () -> Unit,
    private val isRequested: () -> Boolean,
    private val markRequested: () -> Unit,
) {
    /** 알림이 보이지 않아 안내가 필요하면 true입니다. 화면은 [hiddenMessage]를 보여 줍니다. */
    var notificationsHidden by mutableStateOf(false)
        internal set

    @StringRes
    val hiddenMessage: Int = R.string.direct_broadcast_notification_hidden

    /** 방송 준비 직전에 부릅니다. 요청 창은 비동기로 뜨며 송출을 막지 않습니다. */
    fun ensure() {
        val action = currentAction()
        notificationsHidden = action == NotificationPermissionAction.SHOW_GUIDANCE
        if (action == NotificationPermissionAction.REQUEST) {
            markRequested()
            launch()
        }
    }

    /** 요청 창 결과를 반영합니다. */
    fun refresh() {
        notificationsHidden = currentAction() != NotificationPermissionAction.READY
    }

    private fun currentAction(): NotificationPermissionAction {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val rationale = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (context as? Activity)?.shouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS,
            ) == true
        return notificationPermissionAction(
            sdkInt = Build.VERSION.SDK_INT,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            permissionGranted = granted,
            requestedBefore = isRequested(),
            shouldShowRationale = rationale,
        )
    }
}

@Composable
internal fun rememberBroadcastNotificationPermission(context: Context): BroadcastNotificationPermission {
    var requested by rememberSaveable { mutableStateOf(false) }
    var permission: BroadcastNotificationPermission? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission?.refresh()
    }
    permission = remember(context, launcher) {
        BroadcastNotificationPermission(
            context = context,
            launch = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            isRequested = { requested },
            markRequested = { requested = true },
        )
    }
    return permission
}
