package com.framework.innolive.feature.live.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class BroadcastRecordingEncodersTest {
    @Test fun recordingSizeKeepsOrientationAndCapsLongEdge() {
        assertEquals(RecordingSize(1280, 720), recordingSizeFor(1280, 720))
        assertEquals(RecordingSize(720, 1280), recordingSizeFor(720, 1280))
        assertEquals(RecordingSize(1920, 1080), recordingSizeFor(3840, 2160))
        assertEquals(RecordingSize(1080, 1920), recordingSizeFor(2160, 3840))
        // 홀수 크기는 인코더가 거부하므로 짝수로 내린다.
        assertEquals(RecordingSize(1278, 718), recordingSizeFor(1279, 719))
    }

    @Test fun bitrateGrowsWithResolution() {
        assertEquals(6_000_000, recordingVideoBitrateFor(RecordingSize(1280, 720)))
        assertEquals(6_000_000, recordingVideoBitrateFor(RecordingSize(720, 1280)))
        assertEquals(10_000_000, recordingVideoBitrateFor(RecordingSize(1920, 1080)))
    }
}
