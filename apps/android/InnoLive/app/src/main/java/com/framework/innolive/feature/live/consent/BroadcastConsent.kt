package com.framework.innolive.feature.live.consent

import android.content.Context
import com.framework.innolive.feature.live.BroadcastProvider

/** 방송 준비 전에 받는 동의·안내입니다. 목록 순서대로 보여 줍니다. */
internal enum class BroadcastConsentStep(val storageKey: String) {
    MEDIA_TRANSMISSION("media_transmission.v1"),
    YOUTUBE_TRANSMISSION("youtube_transmission.v1"),
    CHZZK_TRANSMISSION("chzzk_transmission.v1"),
}

/** 영상·음성 전송 동의는 항상, 플랫폼 전송 안내는 송출 대상에 포함된 플랫폼만 받습니다. */
internal fun requiredBroadcastConsentSteps(providers: Set<BroadcastProvider>): List<BroadcastConsentStep> =
    buildList {
        add(BroadcastConsentStep.MEDIA_TRANSMISSION)
        if (BroadcastProvider.YOUTUBE in providers) add(BroadcastConsentStep.YOUTUBE_TRANSMISSION)
        if (BroadcastProvider.CHZZK in providers) add(BroadcastConsentStep.CHZZK_TRANSMISSION)
    }

internal fun nextBroadcastConsentStep(
    providers: Set<BroadcastProvider>,
    hasAccepted: (BroadcastConsentStep) -> Boolean,
): BroadcastConsentStep? = requiredBroadcastConsentSteps(providers).firstOrNull { !hasAccepted(it) }

/**
 * 명시적으로 수락한 동의를 기기에 남깁니다. 로그아웃·로그인 만료·앱 재실행 후에도 유지하고,
 * 계정 삭제 시 전체를, 플랫폼 연결이 없어졌을 때 그 플랫폼 안내를 지웁니다.
 */
internal class BroadcastConsentStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun hasAccepted(step: BroadcastConsentStep): Boolean = preferences.getBoolean(step.storageKey, false)

    fun record(step: BroadcastConsentStep) {
        check(preferences.edit().putBoolean(step.storageKey, true).commit()) {
            "Unable to persist broadcast consent."
        }
    }

    fun clear(step: BroadcastConsentStep) {
        if (!hasAccepted(step)) return
        check(preferences.edit().remove(step.storageKey).commit()) { "Unable to clear broadcast consent." }
    }

    fun clearAll() {
        check(preferences.edit().clear().commit()) { "Unable to clear broadcast consents." }
    }

    private companion object {
        const val PREFERENCES_NAME = "innolive_broadcast_consent"
    }
}
