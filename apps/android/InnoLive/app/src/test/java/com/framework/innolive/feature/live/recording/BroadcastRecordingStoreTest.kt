package com.framework.innolive.feature.live.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

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

    /** 재생 확인은 기기 API이므로 일반 MP4(마지막 moov에 mvex 없음)인지로 대신합니다. */
    private val validate: (File) -> Long? = { file ->
        val top = readTopLevel(file)
        val moov = top.lastOrNull { it.type == "moov" }
        if (moov != null && moov.child("mvex") == null && top.none { it.type == "moof" }) 1_000L else null
    }

    private fun store(root: File) = BroadcastRecordingStore(root, validate = validate)

    private fun journal(directory: File, status: RecordingJournal.Status, delayUs: Long = 0L) =
        RecordingJournal.write(directory, RecordingJournal(directory.name, "session", "youtube", 42L, status, delayUs))

    @Test fun metadataRoundTripsThroughJson() {
        val original = recording("abc", 123L, processed = true).copy(recovered = true)
        assertEquals(original, SavedBroadcastRecording.fromJson(original.toJson()))
        val legacy = SavedBroadcastRecording.fromJson(org.json.JSONObject("""{"id":"x","created_at":1,"duration_ms":2}"""))
        assertEquals(recording("x", 1L, processed = false).copy(durationMs = 2L, sessionId = null, bytes = 0L,
            originalFrames = 0L, processedFrames = 0L, droppedFrames = 0L), legacy)
        assertNull(SavedBroadcastRecording.fromJson(org.json.JSONObject("""{"created_at":1,"duration_ms":2}""")))
        assertNull(SavedBroadcastRecording.fromJson(org.json.JSONObject("""{"id":"x","created_at":1,"duration_ms":0}""")))
    }

    @Test fun journalRoundTripsAndOnlyConfirmedStatesMayRecover() {
        val journal = RecordingJournal("id", null, "chzzk", 5L, RecordingJournal.Status.WRITER_COMPLETED, 420_000L)
        assertEquals(journal, RecordingJournal.fromJson(journal.toJson()))
        assertEquals(
            setOf(RecordingJournal.Status.CONFIRMED, RecordingJournal.Status.FINALIZING, RecordingJournal.Status.WRITER_COMPLETED),
            RecordingJournal.Status.entries.filter { it.mayRecover }.toSet(),
        )
    }

    @Test fun listIsNewestFirstAndKeepsOwnedDirectories() {
        val root = folder.newFolder("recordings")
        val store = store(root)
        save(store, recording("old", 1L, processed = true), processedFile = true)
        save(store, recording("new", 2L, processed = true), processedFile = false)
        val active = store.createDirectory("active")
        File(active, "original.partial.mp4").writeBytes(ByteArray(5))
        journal(active, RecordingJournal.Status.CONFIRMED)
        RecordingOwnership.claim("active")
        try {
            val library = store.load()
            assertEquals(listOf("new", "old"), library.recordings.map { it.id })
            // 송출 파일이 사라진 녹화는 원본만 있는 녹화로 보여 준다.
            assertFalse(library.recordings.first().hasProcessed)
            assertTrue(library.recordings.last().hasProcessed)
            // 이 프로세스가 쓰는 중인 녹화는 복구하거나 지우지 않는다.
            assertTrue(File(active, "original.partial.mp4").isFile)
            assertTrue(library.preserved.isEmpty())
        } finally {
            RecordingOwnership.release("active")
        }
    }

    @Test fun confirmedRecordingInterruptedMidFragmentIsRecoveredWithProcessedDelay() {
        val root = folder.newFolder("recordings")
        val store = store(root)
        val directory = store.createDirectory("crashed")
        // 녹화 중 프로세스가 죽은 상태: 조각을 쓰는 중에 파일이 끝났다.
        val original = File(directory, "original.partial.mp4")
        writeSyntheticRecording(original, seconds = 5)
        RandomAccessFile(original, "rw").use { it.setLength(it.length() - 700) }
        writeSyntheticRecording(File(directory, "processed.partial.mp4"), seconds = 5, videoStartUs = 400_000L)
        journal(directory, RecordingJournal.Status.CONFIRMED, delayUs = 400_000L)

        val library = store.load()
        val recovered = library.recordings.single()
        assertEquals("crashed", recovered.id)
        assertTrue(recovered.recovered)
        assertTrue(recovered.hasProcessed)
        assertEquals(42L, recovered.createdAtMillis)
        assertTrue(recovered.originalFrames in 1 until 150)
        assertEquals(1, library.recoveredCount)
        assertTrue(File(directory, "original.mp4").isFile)
        assertFalse(original.exists())
        assertNull(RecordingJournal.read(directory))
        // 블러 후 영상의 왕복 지연(400ms)을 빼서 영상이 음성과 같은 시각에 시작한다.
        val trak = readTopLevel(File(directory, "processed.mp4")).last { it.type == "moov" }.children()
            .first { it.type == "trak" && it.path("mdia", "hdlr")!!.let { hdlr -> String(hdlr.data, hdlr.payloadStart + 8, 4) } == "vide" }
        assertNull(trak.child("edts"))
        // 다시 열어도 같은 녹화 하나다.
        assertEquals(listOf("crashed"), store.load().recordings.map { it.id })
    }

    @Test fun unconfirmedFailedAndUnreadableRecordingsArePreservedNotDeleted() {
        val root = folder.newFolder("recordings")
        val store = store(root)
        val provisional = store.createDirectory("provisional")
        writeSyntheticRecording(File(provisional, "original.partial.mp4"), seconds = 2)
        journal(provisional, RecordingJournal.Status.PROVISIONAL)
        val failed = store.createDirectory("failed")
        writeSyntheticRecording(File(failed, "original.partial.mp4"), seconds = 2)
        journal(failed, RecordingJournal.Status.FAILED)
        val corrupt = store.createDirectory("corrupt")
        File(corrupt, "original.partial.mp4").writeBytes(ByteArray(100))
        journal(corrupt, RecordingJournal.Status.FINALIZING)
        val legacy = store.createDirectory("legacy")
        File(legacy, "original.partial.mp4").writeBytes(ByteArray(10))
        val aborted = store.createDirectory("aborted")
        File(aborted, "original.partial.mp4").writeBytes(ByteArray(10))
        journal(aborted, RecordingJournal.Status.ABORTED)
        val empty = store.createDirectory("empty")
        journal(empty, RecordingJournal.Status.CONFIRMED)

        val library = store.load()
        assertTrue(library.recordings.isEmpty())
        val reasons = library.preserved.associate { it.id to it.reason }
        assertEquals(PreservedBroadcastRecording.Reason.UNCONFIRMED, reasons["provisional"])
        assertEquals(PreservedBroadcastRecording.Reason.FAILED, reasons["failed"])
        assertEquals(PreservedBroadcastRecording.Reason.UNREADABLE, reasons["corrupt"])
        assertEquals(PreservedBroadcastRecording.Reason.UNREADABLE, reasons["legacy"])
        assertEquals(4, library.preserved.size)
        assertTrue(provisional.exists() && failed.exists() && corrupt.exists() && legacy.exists())
        assertFalse(aborted.exists())
        assertFalse(empty.exists())

        assertTrue(store.deletePreserved(library.preserved))
        assertTrue(store.load().preserved.isEmpty())
    }

    @Test fun renamedOriginalWithoutMetadataIsCompleted() {
        val root = folder.newFolder("recordings")
        val store = store(root)
        val directory = store.createDirectory("renamed")
        val partial = File(directory, "original.partial.mp4")
        writeSyntheticRecording(partial, seconds = 2)
        Mp4Finalizer.finish(partial)
        check(partial.renameTo(File(directory, "original.mp4")))
        journal(directory, RecordingJournal.Status.WRITER_COMPLETED)
        val recovered = store.load().recordings.single()
        assertFalse(recovered.hasProcessed)
        assertTrue(File(directory, BroadcastRecordingStore.METADATA_FILE_NAME).isFile)
    }

    @Test fun deleteRemovesOnlyThatRecording() {
        val root = folder.newFolder("recordings")
        val store = store(root)
        save(store, recording("a", 1L, processed = false), processedFile = false)
        save(store, recording("b", 2L, processed = false), processedFile = false)
        assertTrue(store.delete(recording("a", 1L, processed = false)))
        assertEquals(listOf("b"), store.list().map { it.id })
        assertTrue(store.originalFile(recording("b", 2L, processed = false)).isFile)
        assertTrue(store.delete(recording("missing", 0L, processed = false)))
        // 쓰는 중인 녹화는 지우지 않는다.
        RecordingOwnership.claim("b")
        try {
            assertFalse(store.delete(recording("b", 2L, processed = false)))
        } finally {
            RecordingOwnership.release("b")
        }
    }
}
