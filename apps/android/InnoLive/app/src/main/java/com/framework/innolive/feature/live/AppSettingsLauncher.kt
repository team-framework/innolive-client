package com.framework.innolive.feature.live

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 권한을 켤 수 있는 앱 정보 화면을 엽니다. 제조사 설정 앱이 이 화면을 지원하지 않으면 시스템 설정을 엽니다.
 * 둘 다 열 수 없어도 앱이 끝나지 않게 실패를 돌려줍니다.
 */
internal fun Context.openAppPermissionSettings(): Boolean = startFirstAvailable(
    listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        Intent(Settings.ACTION_SETTINGS),
    ),
    ::startActivity,
)

/** [candidates]를 순서대로 열어 처음 성공한 항목에서 멈추며, 모두 열 수 없으면 false를 돌려줍니다. */
internal fun <T> startFirstAvailable(candidates: List<T>, start: (T) -> Unit): Boolean =
    candidates.any { candidate ->
        try {
            start(candidate)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
