package com.framework.innolive.feature.live.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class BroadcastRecordingExportTest {
    private val recording = SavedBroadcastRecording(
        id = "a", createdAtMillis = 1_760_000_000_000L, durationMs = 65_000L, sessionId = "s",
        provider = "youtube", hasProcessed = true, bytes = 1L,
    )

    @Test fun exportMatchesTheThreeIosActions() {
        // iOS: 영상과 음성 MP4 공유, 분리(MP4 + M4A) 공유, 사진 앱 저장
        assertEquals(
            listOf(RecordingExportAction.SHARE_VIDEO, RecordingExportAction.SHARE_SPLIT, RecordingExportAction.SAVE_TO_GALLERY),
            RecordingExportAction.entries,
        )
    }

    @Test fun processedSourceRequiresTheProcessedFile() {
        assertTrue(RecordingExportSource.PROCESSED.isAvailable(recording))
        assertFalse(RecordingExportSource.PROCESSED.isAvailable(recording.copy(hasProcessed = false)))
        assertTrue(RecordingExportSource.ORIGINAL.isAvailable(recording.copy(hasProcessed = false)))
    }

    @Test fun fileNamesAreUniquePerSourceAndPart() {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        try {
            val names = RecordingExportSource.entries.flatMap { source ->
                RecordingExportPart.entries.map { exportFileName(recording, source, it) }
            }
            assertEquals(names.size, names.toSet().size)
            assertEquals("InnoLive_20251009_175320_blurred.mp4",
                exportFileName(recording, RecordingExportSource.PROCESSED, RecordingExportPart.FULL))
            assertEquals("InnoLive_20251009_175320_original-audio.m4a",
                exportFileName(recording, RecordingExportSource.ORIGINAL, RecordingExportPart.AUDIO))
            assertEquals("audio/mp4", RecordingExportPart.AUDIO.mimeType)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun copyingExportsRequireTheFileSizePlusMargin() {
        assertEquals(1_000L + EXPORT_SPACE_MARGIN_BYTES, requiredExportBytes(1_000L))
    }
}
