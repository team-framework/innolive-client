package com.framework.innolive.feature.live.recording

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 저장을 마친 방송 녹화입니다. 파일은 `<root>/<id>/original.mp4`와 선택적인 `processed.mp4`입니다. */
data class SavedBroadcastRecording(
    val id: String,
    val createdAtMillis: Long,
    val durationMs: Long,
    val sessionId: String?,
    val provider: String,
    /** 실제 송출 프레임(블러 후) 파일이 있으면 true입니다. 없으면 블러 후 내보내기를 막습니다. */
    val hasProcessed: Boolean,
    val bytes: Long,
    val originalFrames: Long = 0L,
    val processedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    /** 앱이 저장 도중 종료돼 다음 실행에서 복구한 녹화면 true입니다. */
    val recovered: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("created_at", createdAtMillis)
        .put("duration_ms", durationMs)
        .put("session_id", sessionId ?: JSONObject.NULL)
        .put("provider", provider)
        .put("has_processed", hasProcessed)
        .put("bytes", bytes)
        .put("original_frames", originalFrames)
        .put("processed_frames", processedFrames)
        .put("dropped_frames", droppedFrames)
        .put("recovered", recovered)

    companion object {
        fun fromJson(json: JSONObject): SavedBroadcastRecording? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val createdAt = json.optLong("created_at", -1L).takeIf { it >= 0 } ?: return null
            val duration = json.optLong("duration_ms", -1L).takeIf { it > 0 } ?: return null
            return SavedBroadcastRecording(
                id = id,
                createdAtMillis = createdAt,
                durationMs = duration,
                sessionId = if (json.isNull("session_id")) null else json.optString("session_id").takeIf { it.isNotBlank() },
                provider = json.optString("provider", "youtube"),
                hasProcessed = json.optBoolean("has_processed", false),
                bytes = json.optLong("bytes", 0L),
                originalFrames = json.optLong("original_frames", 0L),
                processedFrames = json.optLong("processed_frames", 0L),
                droppedFrames = json.optLong("dropped_frames", 0L),
                recovered = json.optBoolean("recovered", false),
            )
        }
    }
}

/** 복구하지 않고 기기에 남겨 둔 미완성 녹화입니다(iOS `PreservedBroadcastRecording`). */
data class PreservedBroadcastRecording(val id: String, val bytes: Long, val modifiedAtMillis: Long, val reason: Reason) {
    enum class Reason {
        /** 방송 시작을 확인하지 못한 채 앱이 종료됐습니다. */
        UNCONFIRMED,
        /** 녹화 중 인코더·저장 오류가 났습니다. iOS처럼 자동 복구하지 않습니다. */
        FAILED,
        /** 복구를 시도했지만 재생할 수 있는 파일을 만들지 못했습니다. */
        UNREADABLE,
    }
}

data class BroadcastRecordingLibrary(
    val recordings: List<SavedBroadcastRecording>,
    val preserved: List<PreservedBroadcastRecording>,
    /** 이번 확인에서 복구한 녹화 수입니다. */
    val recoveredCount: Int = 0,
)

/**
 * 앱 내부 no-backup 저장소의 녹화 목록입니다. 메타데이터가 없는 폴더는 저장을 끝내지 못한 녹화이며
 * 복구 기록([RecordingJournal])을 보고 다룹니다(iOS `RecordingSessionStore.reloadLibrary`와 같은 정책).
 *
 * - 방송 시작을 확인했고 저장을 끝내지 못한 녹화는 조각 MP4를 일반 MP4로 마무리해 목록에 올립니다.
 * - 시작을 확인하지 못했거나 녹화 중 실패한 녹화, 복구하지 못한 파일은 지우지 않고 보관 목록에 둡니다.
 * - 이 프로세스가 쓰는 중인 녹화([RecordingOwnership])는 건드리지 않습니다.
 */
internal class BroadcastRecordingStore(
    private val root: File,
    private val finalize: (File, Long) -> Mp4FinalizeResult = { file, shift -> Mp4Finalizer.finish(file, shift) },
    private val validate: (File) -> Long? = { file -> BroadcastRecordingSession.validateRecordingFile(file) },
) {
    fun list(): List<SavedBroadcastRecording> = load().recordings

    /** 미완성 녹화를 복구한 뒤 목록을 읽습니다. 작업 스레드에서 부릅니다. */
    fun load(): BroadcastRecordingLibrary {
        val directories = root.listFiles { file -> file.isDirectory } ?: return BroadcastRecordingLibrary(emptyList(), emptyList())
        val recordings = mutableListOf<SavedBroadcastRecording>()
        val preserved = mutableListOf<PreservedBroadcastRecording>()
        var recovered = 0
        for (directory in directories) {
            if (!RecordingOwnership.tryClaim(directory.name)) continue
            try {
                val recording = readMetadata(directory)?.takeIf { originalFile(it).isFile }
                if (recording != null) {
                    // 송출 파일이 사라졌으면 원본만 있는 녹화로 다룬다.
                    recordings += if (recording.hasProcessed && !processedFile(recording).isFile) {
                        recording.copy(hasProcessed = false)
                    } else recording
                    continue
                }
                when (val outcome = recoverDirectory(directory)) {
                    is Outcome.Recovered -> { recordings += outcome.recording; recovered++ }
                    is Outcome.Preserved -> preserved += outcome.recording
                    Outcome.Removed -> Unit
                }
            } finally {
                RecordingOwnership.release(directory.name)
            }
        }
        return BroadcastRecordingLibrary(
            recordings.sortedByDescending { it.createdAtMillis },
            preserved.sortedByDescending { it.modifiedAtMillis },
            recovered,
        )
    }

    private sealed interface Outcome {
        class Recovered(val recording: SavedBroadcastRecording) : Outcome
        class Preserved(val recording: PreservedBroadcastRecording) : Outcome
        object Removed : Outcome
    }

    private fun recoverDirectory(directory: File): Outcome {
        val journal = RecordingJournal.read(directory)
        val media = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }.orEmpty()
        if (journal?.status == RecordingJournal.Status.ABORTED || media.isEmpty()) {
            // 버리기로 한 녹화이거나 영상 파일이 없는 폴더입니다.
            directory.deleteRecursively()
            return Outcome.Removed
        }
        val finalOriginal = File(directory, "${BroadcastRecordingSession.ORIGINAL_FILE_NAME}.mp4")
        val reason = when {
            journal == null -> PreservedBroadcastRecording.Reason.UNREADABLE
            journal.status.mayRecover || finalOriginal.isFile -> try {
                return Outcome.Recovered(recover(directory, journal))
            } catch (error: Exception) {
                RecordingLog.w("recording_recovery_failed type=${error.javaClass.simpleName}")
                PreservedBroadcastRecording.Reason.UNREADABLE
            }
            journal.status == RecordingJournal.Status.FAILED -> PreservedBroadcastRecording.Reason.FAILED
            else -> PreservedBroadcastRecording.Reason.UNCONFIRMED
        }
        val files = directory.walkTopDown().filter { it.isFile }.toList()
        return Outcome.Preserved(PreservedBroadcastRecording(directory.name, files.sumOf { it.length() },
            files.maxOfOrNull { it.lastModified() } ?: directory.lastModified(), reason))
    }

    /** 조각 MP4를 마무리하고 확인한 뒤 최종 이름과 메타데이터를 남깁니다. 단계마다 다시 실행해도 됩니다. */
    private fun recover(directory: File, journal: RecordingJournal): SavedBroadcastRecording {
        val original = File(directory, "${BroadcastRecordingSession.ORIGINAL_FILE_NAME}.mp4")
        val originalPartial = File(directory, "${BroadcastRecordingSession.ORIGINAL_FILE_NAME}.partial.mp4")
        var originalFrames = 0L
        if (!original.isFile) {
            originalFrames = finalize(originalPartial, 0L).videoSamples.toLong()
            validate(originalPartial) ?: throw Mp4RepairException("복구한 원본을 재생할 수 없습니다.")
            check(originalPartial.renameTo(original)) { "원본 파일 이름을 바꾸지 못했습니다." }
        }
        val durationMs = validate(original) ?: throw Mp4RepairException("원본을 재생할 수 없습니다.")
        val processed = File(directory, "${BroadcastRecordingSession.PROCESSED_FILE_NAME}.mp4")
        val processedPartial = File(directory, "${BroadcastRecordingSession.PROCESSED_FILE_NAME}.partial.mp4")
        var processedFrames = 0L
        if (!processed.isFile && processedPartial.isFile) {
            runCatching {
                processedFrames = finalize(processedPartial, -journal.processedDelayUs).videoSamples.toLong()
                validate(processedPartial) ?: throw Mp4RepairException("복구한 블러 후 영상을 재생할 수 없습니다.")
                check(processedPartial.renameTo(processed))
            }.onFailure { RecordingLog.w("processed_recovery_failed type=${it.javaClass.simpleName}") }
        }
        processedPartial.delete()
        val hasProcessed = processed.isFile && validate(processed) != null
        if (!hasProcessed) processed.delete()
        val recording = SavedBroadcastRecording(
            id = directory.name,
            createdAtMillis = journal.createdAtMillis.takeIf { it > 0 } ?: directory.lastModified(),
            durationMs = durationMs,
            sessionId = journal.sessionId,
            provider = journal.provider,
            hasProcessed = hasProcessed,
            bytes = original.length() + (if (hasProcessed) processed.length() else 0L),
            originalFrames = originalFrames,
            processedFrames = processedFrames,
            recovered = true,
        )
        writeMetadata(directory, recording)
        RecordingJournal.delete(directory)
        return recording
    }

    fun createDirectory(id: String = UUID.randomUUID().toString()): File = File(root, id).also {
        check(it.isDirectory || it.mkdirs()) { "녹화 폴더를 만들지 못했습니다." }
    }

    fun directory(id: String): File = File(root, id)

    fun delete(recording: SavedBroadcastRecording): Boolean = deleteDirectory(recording.id)

    /** 보관 중인 미완성 녹화를 지웁니다. 진행 중인 녹화는 남깁니다. */
    fun deletePreserved(preserved: List<PreservedBroadcastRecording>): Boolean =
        preserved.map { deleteDirectory(it.id) }.all { it }

    private fun deleteDirectory(id: String): Boolean {
        if (!RecordingOwnership.tryClaim(id)) return false
        try {
            val directory = File(root, id)
            if (!directory.exists()) return true
            return directory.deleteRecursively()
        } finally {
            RecordingOwnership.release(id)
        }
    }

    fun originalFile(recording: SavedBroadcastRecording): File =
        File(File(root, recording.id), "${BroadcastRecordingSession.ORIGINAL_FILE_NAME}.mp4")

    fun processedFile(recording: SavedBroadcastRecording): File =
        File(File(root, recording.id), "${BroadcastRecordingSession.PROCESSED_FILE_NAME}.mp4")

    /** 저장 공간을 확인합니다. 조회에 실패하면 null입니다. */
    fun availableBytes(): Long? = try {
        if (!root.isDirectory && !root.mkdirs()) null else root.usableSpace.takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val METADATA_FILE_NAME = "meta.json"

        /** 시스템 백업과 기기 이전에서 제외되는 위치입니다. 영상은 크고 사용자가 직접 내보냅니다. */
        fun defaultRoot(context: Context): File = File(context.noBackupFilesDir, "recordings")

        fun readMetadata(directory: File): SavedBroadcastRecording? {
            val file = File(directory, METADATA_FILE_NAME)
            if (!file.isFile) return null
            return runCatching { SavedBroadcastRecording.fromJson(JSONObject(file.readText())) }.getOrNull()
        }

        /** 임시 파일에 쓴 뒤 이름을 바꿔 반쯤 쓴 메타데이터를 남기지 않습니다. */
        fun writeMetadata(directory: File, recording: SavedBroadcastRecording) {
            writeAtomically(directory, METADATA_FILE_NAME, recording.toJson().toString())
        }
    }
}
