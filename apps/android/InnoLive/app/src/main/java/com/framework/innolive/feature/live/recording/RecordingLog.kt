package com.framework.innolive.feature.live.recording

import android.util.Log

/** 녹화 로그입니다. 단위 테스트(android.jar 스텁)에서도 예외 없이 지나가도록 감쌉니다. */
internal object RecordingLog {
    private const val TAG = "BroadcastRecording"

    fun w(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    fun i(message: String) {
        runCatching { Log.i(TAG, message) }
    }
}
