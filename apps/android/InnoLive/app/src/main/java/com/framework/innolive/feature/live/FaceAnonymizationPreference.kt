package com.framework.innolive.feature.live

import android.content.Context

/**
 * 실험실의 비식별화 방법입니다. 합성은 서버 AI 처리에서, 토끼·부엉이는 온디바이스 처리에서만 쓰고 그 밖에는 블러입니다.
 */
enum class FaceAnonymizationMethod(val storageValue: String) {
    BLUR("blur"), FACE_SWAP("face_swap"), RABBIT("rabbit"), OWL("owl");

    /** 온디바이스 처리에서 얼굴에 씌우는 동물 자산 이름입니다. */
    val animal: String? get() = storageValue.takeIf { this == RABBIT || this == OWL }

    companion object {
        fun from(value: String?): FaceAnonymizationMethod? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * 실험실의 비식별화 방법 선택입니다. 관리자 전용 실험 기능이며 기본값은 블러입니다.
 * 세션을 만들 때 고정되므로 다음 방송 준비부터 적용합니다. 이전 버전의 `face_swap` 값도 읽습니다.
 */
internal class FaceAnonymizationPreference(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("live_face_anonymization", Context.MODE_PRIVATE)

    var method: FaceAnonymizationMethod
        get() = FaceAnonymizationMethod.from(preferences.getString("method", null))
            ?: if (preferences.getBoolean("face_swap", false)) FaceAnonymizationMethod.FACE_SWAP else FaceAnonymizationMethod.BLUR
        set(value) {
            preferences.edit().putString("method", value.storageValue)
                .putBoolean("face_swap", value == FaceAnonymizationMethod.FACE_SWAP).apply()
        }
}

internal const val FaceAnonymizationFaceSwap = "face_swap"

/**
 * 세션 생성 요청에 보낼 얼굴 가림 방식입니다. 합성은 서버 AI 처리에서만 보냅니다. 서버가 온디바이스 세션의
 * 합성을 400으로 거절하기 때문입니다. 블러는 서버 기본값이라 null로 생략합니다.
 */
internal fun sessionFaceAnonymization(faceSwapSelected: Boolean, onDeviceProcessing: Boolean): String? =
    FaceAnonymizationFaceSwap.takeIf { faceSwapSelected && !onDeviceProcessing }

/** 세션에 고정할 동물입니다. 동물은 온디바이스 처리에서만 씁니다. */
internal fun sessionAnimal(method: FaceAnonymizationMethod, onDeviceProcessing: Boolean): String? =
    method.animal.takeIf { onDeviceProcessing }
