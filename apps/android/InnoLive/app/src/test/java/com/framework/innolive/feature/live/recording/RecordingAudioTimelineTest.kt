package com.framework.innolive.feature.live.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingAudioTimelineTest {
    @Test fun timestampsFollowSampleCountNotJitteryArrival() {
        val timeline = RecordingAudioTimeline(48_000)
        assertEquals(1_000_000L, timeline.next(1_000_000L, 480))
        // 도착이 3ms 늦어도 시간축은 샘플 수(10ms)로 이어진다.
        assertEquals(1_010_000L, timeline.next(1_013_000L, 480))
        assertEquals(1_020_000L, timeline.next(1_019_000L, 480))
    }

    @Test fun longGapRealignsToArrivalInsteadOfCatchingUp() {
        val timeline = RecordingAudioTimeline(48_000)
        timeline.next(0L, 480)
        assertEquals(10_000L, timeline.next(10_000L, 480))
        // 500ms 공백은 실제 캡처 중단이므로 도착 시각으로 다시 맞춘다.
        assertEquals(520_000L, timeline.next(520_000L, 480))
        assertEquals(530_000L, timeline.next(531_000L, 480))
    }

    @Test fun resyncNeverMovesBackwards() {
        val timeline = RecordingAudioTimeline(48_000)
        timeline.next(1_000_000L, 48_000)
        // 1초치 샘플을 받았는데 도착이 그보다 훨씬 이르면(시계 역행) 기존 시간축 끝을 유지한다.
        assertEquals(2_000_000L, timeline.next(1_200_000L, 480))
    }
}
