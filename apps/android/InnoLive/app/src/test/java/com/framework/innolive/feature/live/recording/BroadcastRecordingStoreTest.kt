package com.framework.innolive.feature.live.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BroadcastRecordingStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun recording(id: String, createdAt: Long, processed: Boolean) = SavedBroadcastRecording(
        id = id, createdAtMillis = createdAt, durationMs = 1_000L, sessionId = "s", provider = "youtube",
        hasProcessed = processed, bytes = 10L, originalFrames = 30L, processedFrames = 29L, droppedFrames = 1L,
    )

    private fun save(store: BroadcastRecordingStore, recording: SavedBroadcastRecording, processedFile: Boolean) {
        val directory = store.createDirectory(recording.id)
        File(directory, "original.mp4").writeBytes(ByteArray(5))
        if (processedFile) File(directory, "processed.mp4").writeBytes(ByteArray(5))
        BroadcastRecordingStore.writeMetadata(directory, recording)
    }

    @Test fun metadataRoundTripsThroughJson() {
        val original = recording("abc", 123L, processed = true)
        assertEquals(original, SavedBroadcastRecording.fromJson(original.toJson()))
        val legacy = SavedBroadcastRecording.fromJson(org.json.JSONObject("""{"id":"x","created_at":1,"duration_ms":2}"""))
        assertEquals(recording("x", 1L, processed = false).copy(durationMs = 2L, sessionId = null, bytes = 0L,
            originalFrames = 0L, processedFrames = 0L, droppedFrames = 0L), legacy)
        assertNull(SavedBroadcastRecording.fromJson(org.json.JSONObject("""{"created_at":1,"duration_ms":2}""")))
        assertNull(SavedBroadcastRecording.fromJson(org.json.JSONObject("""{"id":"x","created_at":1,"duration_ms":0}""")))
    }

    @Test fun listIsNewestFirstAndSkipsUnfinishedDirectoriesExceptActiveOrRecentOnes() {
        val root = folder.newFolder("recordings")
        var now = 0L
        val store = BroadcastRecordingStore(root) { now }
        save(store, recording("old", 1L, processed = true), processedFile = true)
        save(store, recording("new", 2L, processed = true), processedFile = false)
        val abandoned = store.createDirectory("abandoned")
        File(abandoned, "original.partial.mp4").writeBytes(ByteArray(5))
        val active = store.createDirectory("active")
        File(active, "original.partial.mp4").writeBytes(ByteArray(5))
        store.activeId = "active"
        // 다른 저장소 인스턴스가 보는 저장 중인 폴더는 최근 수정 시각으로 구분한다.
        val writing = store.createDirectory("writing")
        File(writing, "original.partial.mp4").writeBytes(ByteArray(5))
        now = writing.lastModified() + BroadcastRecordingStore.UNFINISHED_GRACE_MILLIS - 1
        abandoned.setLastModified(now - BroadcastRecordingStore.UNFINISHED_GRACE_MILLIS - 60_000L)
        File(abandoned, "original.partial.mp4").setLastModified(now - BroadcastRecordingStore.UNFINISHED_GRACE_MILLIS - 60_000L)

        val listed = store.list()
        assertEquals(listOf("new", "old"), listed.map { it.id })
        // 송출 파일이 사라진 녹화는 원본만 있는 녹화로 보여 준다.
        assertFalse(listed.first().hasProcessed)
        assertTrue(listed.last().hasProcessed)
        assertFalse(abandoned.exists())
        assertTrue(active.exists())
        assertTrue(writing.exists())
        now += BroadcastRecordingStore.UNFINISHED_GRACE_MILLIS + 60_000L
        store.list()
        assertFalse(writing.exists())
        assertTrue(active.exists())
    }

    @Test fun deleteRemovesOnlyThatRecording() {
        val root = folder.newFolder("recordings")
        val store = BroadcastRecordingStore(root)
        save(store, recording("a", 1L, processed = false), processedFile = false)
        save(store, recording("b", 2L, processed = false), processedFile = false)
        assertTrue(store.delete(recording("a", 1L, processed = false)))
        assertEquals(listOf("b"), store.list().map { it.id })
        assertTrue(store.originalFile(recording("b", 2L, processed = false)).isFile)
        assertTrue(store.delete(recording("missing", 0L, processed = false)))
    }
}
