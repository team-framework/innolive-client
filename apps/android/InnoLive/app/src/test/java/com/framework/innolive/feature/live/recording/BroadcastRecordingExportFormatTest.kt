package com.framework.innolive.feature.live.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class BroadcastRecordingExportFormatTest {
    private val recording = SavedBroadcastRecording(
        id = "a", createdAtMillis = 1_760_000_000_000L, durationMs = 65_000L, sessionId = "s",
        provider = "youtube", hasProcessed = true, bytes = 1L,
    )

    @Test fun fiveFormatsCoverBothVideosAudioPresenceAndAudioOnly() {
        assertEquals(5, RecordingExportFormat.entries.size)
        val video = RecordingExportFormat.entries.filter { it.includesVideo }
        assertEquals(2, video.count { it.usesProcessed })
        assertEquals(2, video.count { !it.usesProcessed })
        assertEquals(2, video.count { it.includesAudio })
        val audio = RecordingExportFormat.AUDIO_ONLY
        assertTrue(audio.includesAudio && !audio.includesVideo && !audio.usesProcessed)
        assertEquals("m4a", audio.extension)
        assertEquals("audio/mp4", audio.mimeType)
        assertEquals("video/mp4", RecordingExportFormat.ORIGINAL_VIDEO_ONLY.mimeType)
        assertTrue(RecordingExportFormat.ORIGINAL_WITH_AUDIO.isFullCopy)
        assertTrue(RecordingExportFormat.PROCESSED_WITH_AUDIO.isFullCopy)
        assertFalse(RecordingExportFormat.PROCESSED_VIDEO_ONLY.isFullCopy)
    }

    @Test fun processedFormatsRequireTheProcessedFile() {
        val originalOnly = recording.copy(hasProcessed = false)
        RecordingExportFormat.entries.forEach { format ->
            assertTrue(format.isAvailable(recording))
            assertEquals(!format.usesProcessed, format.isAvailable(originalOnly))
        }
    }

    @Test fun exportFileNamesAreUniquePerFormat() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        try {
            val names = RecordingExportFormat.entries.map { exportFileName(recording, it) }
            assertEquals(names.size, names.toSet().size)
            assertEquals("InnoLive_20251009_175320_blurred-muted.mp4",
                exportFileName(recording, RecordingExportFormat.PROCESSED_VIDEO_ONLY))
            assertEquals("InnoLive_20251009_175320_audio.m4a", exportFileName(recording, RecordingExportFormat.AUDIO_ONLY))
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
