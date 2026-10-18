package com.framework.innolive.feature.live

import com.framework.innolive.feature.live.privacy.PrivacyGpuBackpressureException
import com.framework.innolive.feature.live.privacy.isPrivacyProcessingFailure

internal enum class ProtectedFrameOutcome { DELIVERED, STALE, BUSY, FAILED }

/**
 * 가림 처리한 결과만 내보냅니다. 처리나 전달에 실패하면 원본으로 대신하지 않고 프레임을 버린 뒤
 * [onFailure]로 알립니다. JNI 링크 오류(`NoSuchMethodError` 등)도 같은 실패로 다룹니다.
 * GPU 대기열이 가득 찬 경우는 실패가 아니라 프레임만 건너뜁니다.
 */
internal fun <T> deliverProtectedFrame(
    process: () -> T,
    deliver: (T) -> Boolean,
    release: (T) -> Unit,
    onFailure: (Throwable) -> Unit,
): ProtectedFrameOutcome {
    val output = try {
        process()
    } catch (_: PrivacyGpuBackpressureException) {
        return ProtectedFrameOutcome.BUSY
    } catch (error: Throwable) {
        if (!error.isPrivacyProcessingFailure()) throw error
        onFailure(error)
        return ProtectedFrameOutcome.FAILED
    }
    return try {
        if (deliver(output)) ProtectedFrameOutcome.DELIVERED else ProtectedFrameOutcome.STALE
    } catch (error: Throwable) {
        if (!error.isPrivacyProcessingFailure()) throw error
        onFailure(error)
        ProtectedFrameOutcome.FAILED
    } finally {
        release(output)
    }
}
