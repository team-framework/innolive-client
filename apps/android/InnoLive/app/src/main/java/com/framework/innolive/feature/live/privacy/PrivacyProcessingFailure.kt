package com.framework.innolive.feature.live.privacy

/**
 * 프로세스를 끝내지 않고 온디바이스 처리 시도 하나만 끝냅니다. JNI 링크 실패(R8이 제거한 클래스나
 * 생성자, 로드되지 않은 네이티브 라이브러리)도 예외처럼 취급하며, OutOfMemoryError 같은 가상 머신
 * 오류는 그대로 전파합니다.
 */
internal fun Throwable.isPrivacyProcessingFailure(): Boolean = this is Exception || this is LinkageError

/**
 * 객체 한도를 넘는 군중처럼 내용을 보호할 수 없는 프레임입니다. 프레임은 여전히 fail-closed로 처리하지만,
 * 장면이 바뀌면 GPU 그래프와 네이티브 입력을 다시 시도할 수 있습니다.
 */
internal class PrivacyFrameContentException(message: String?, cause: Throwable? = null) :
    IllegalStateException(message, cause)
