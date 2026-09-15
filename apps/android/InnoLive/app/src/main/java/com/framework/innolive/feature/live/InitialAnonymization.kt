package com.framework.innolive.feature.live

// 응답 유실·잘못된 응답·반대 설정은 모두 초기화 실패이며 미디어 전송을 허용하지 않습니다.
internal fun confirmInitialAnonymization(
    enabled: Boolean,
    applySetting: () -> AnonymizationState,
): AnonymizationState {
    val confirmed = try {
        applySetting()
    } catch (exception: Exception) {
        throw IllegalStateException("비식별화 초기 설정을 확인하지 못했습니다. 다시 연결해 주세요.", exception)
    }
    check(confirmed == if (enabled) AnonymizationState.ENABLED else AnonymizationState.DISABLED) {
        "서버가 선택한 비식별화 설정을 적용하지 않았습니다. 다시 연결해 주세요."
    }
    return confirmed
}
