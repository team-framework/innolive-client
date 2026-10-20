package com.framework.innolive.feature.live

import android.content.Context

/**
 * 실험실의 `블러 대신 얼굴 합성 사용하기` 선택입니다. 관리자 전용 실험 기능이며 기본값은 블러입니다.
 * 세션을 만들 때 고정되므로 다음 방송 준비부터 적용합니다.
 */
internal class FaceAnonymizationPreference(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("live_face_anonymization", Context.MODE_PRIVATE)

    var faceSwap: Boolean
        get() = preferences.getBoolean("face_swap", false)
        set(value) { preferences.edit().putBoolean("face_swap", value).apply() }
}

internal const val FaceAnonymizationFaceSwap = "face_swap"

/**
 * 세션 생성 요청에 보낼 얼굴 가림 방식입니다. 합성은 서버 AI 처리에서만 보냅니다. 서버가 온디바이스 세션의
 * 합성을 400으로 거절하기 때문입니다. 블러는 서버 기본값이라 null로 생략합니다.
 */
internal fun sessionFaceAnonymization(faceSwapSelected: Boolean, onDeviceProcessing: Boolean): String? =
    FaceAnonymizationFaceSwap.takeIf { faceSwapSelected && !onDeviceProcessing }
