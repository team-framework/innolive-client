package com.framework.innolive.feature.live.direct.publish

import android.util.Log

/**
 * RootEncoder(`com.pedro.*`)의 `android.util.Log` 호출이 빌드 때 이 객체로 바뀝니다
 * (buildSrc `RedactLibraryLogsPlugin`). 라이브러리는 RTMP 명령과 함께 스트림 키를 로그에 남기므로
 * 메시지는 버립니다. 정보성 로그는 남기지 않고, 경고·오류는 태그와 예외 종류만 남깁니다.
 *
 * 시그니처는 `android.util.Log`의 정적 메서드와 같아야 합니다. 빠지면 실행 중 NoSuchMethodError가 납니다.
 */
@Suppress("UNUSED_PARAMETER", "unused")
internal object RedactedLibraryLog {
    @JvmStatic fun v(tag: String?, msg: String?): Int = 0
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun d(tag: String?, msg: String?): Int = 0
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun i(tag: String?, msg: String?): Int = 0
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = 0
    @JvmStatic fun w(tag: String?, msg: String?): Int = write(Log.WARN, tag, null)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = write(Log.WARN, tag, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = write(Log.WARN, tag, tr)
    @JvmStatic fun e(tag: String?, msg: String?): Int = write(Log.ERROR, tag, null)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = write(Log.ERROR, tag, tr)
    @JvmStatic fun wtf(tag: String?, msg: String?): Int = write(Log.ERROR, tag, null)
    @JvmStatic fun wtf(tag: String?, tr: Throwable?): Int = write(Log.ERROR, tag, tr)
    @JvmStatic fun wtf(tag: String?, msg: String?, tr: Throwable?): Int = write(Log.ERROR, tag, tr)
    @JvmStatic fun println(priority: Int, tag: String?, msg: String?): Int =
        if (priority >= Log.WARN) write(priority, tag, null) else 0

    /** 예외 메시지에도 주소가 들어갈 수 있어 종류만 남깁니다. */
    private fun write(priority: Int, tag: String?, error: Throwable?): Int =
        Log.println(priority, "Rtmp/${tag ?: "?"}", "message redacted${error?.let { " type=${it.javaClass.simpleName}" } ?: ""}")
}
