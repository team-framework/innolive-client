package com.framework.innolive.feature.live.recording

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RecordingAudioSinkOwnershipTest {
    private val first = RecordingAudioSink { _, _, _, _, _ -> }
    private val second = RecordingAudioSink { _, _, _, _, _ -> }

    @Test fun lateReleaseOfPreviousConnectionKeepsCurrentSink() {
        val tap = WebRtcRecordingAudioTap()
        val ownership = RecordingAudioSinkOwnership(tap)
        val previous = Any()
        val current = Any()

        ownership.set(previous, first)
        ownership.set(current, second)
        // 이전 연결이 늦게 해제되며 입력을 떼도 새 연결의 입력은 남습니다.
        ownership.set(previous, null)
        assertSame(second, tap.sink)

        ownership.set(current, null)
        assertNull(tap.sink)
    }
}
