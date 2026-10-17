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
            )
        }
    }
}

/**
 * 앱 내부 no-backup 저장소의 녹화 목록입니다. 메타데이터가 없는 폴더는 저장을 끝내지 못한 녹화이므로
 * 지웁니다. MediaMuxer를 멈추지 못한 MP4는 재생할 수 없습니다. 진행 중인 녹화([activeId])와 다른
 * 저장소 인스턴스(설정 화면)가 보는 최근에 쓰인 폴더는 저장이 끝나지 않은 것이므로 남겨 둡니다.
 */
internal class BroadcastRecordingStore(
    private val root: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    @Volatile var activeId: String? = null

    fun list(): List<SavedBroadcastRecording> {
        val directories = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        val recordings = mutableListOf<SavedBroadcastRecording>()
        for (directory in directories) {
            if (directory.name == activeId) continue
            val recording = readMetadata(directory)
            if (recording == null || !originalFile(recording).isFile) {
                if (!isRecentlyWritten(directory)) directory.deleteRecursively()
                continue
            }
            // 송출 파일이 사라졌으면 원본만 있는 녹화로 다룬다.
            recordings += if (recording.hasProcessed && !processedFile(recording).isFile) {
                recording.copy(hasProcessed = false)
            } else recording
        }
        return recordings.sortedByDescending { it.createdAtMillis }
    }

    /** 먹서가 쓰는 중인 파일은 수정 시각이 계속 갱신됩니다. 멈춘 지 [UNFINISHED_GRACE_MILLIS]가 지나야 지웁니다. */
    private fun isRecentlyWritten(directory: File): Boolean {
        val latest = directory.walkTopDown().maxOfOrNull { it.lastModified() } ?: return false
        return nowMillis() - latest < UNFINISHED_GRACE_MILLIS
    }

    fun createDirectory(id: String = UUID.randomUUID().toString()): File = File(root, id).also {
        check(it.isDirectory || it.mkdirs()) { "녹화 폴더를 만들지 못했습니다." }
    }

    fun delete(recording: SavedBroadcastRecording): Boolean {
        val directory = File(root, recording.id)
        if (!directory.exists()) return true
        return directory.deleteRecursively()
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
        const val UNFINISHED_GRACE_MILLIS = 10L * 60L * 1_000L

        /** 시스템 백업과 기기 이전에서 제외되는 위치입니다. 영상은 크고 사용자가 직접 내보냅니다. */
        fun defaultRoot(context: Context): File = File(context.noBackupFilesDir, "recordings")

        fun readMetadata(directory: File): SavedBroadcastRecording? {
            val file = File(directory, METADATA_FILE_NAME)
            if (!file.isFile) return null
            return runCatching { SavedBroadcastRecording.fromJson(JSONObject(file.readText())) }.getOrNull()
        }

        /** 임시 파일에 쓴 뒤 이름을 바꿔 반쯤 쓴 메타데이터를 남기지 않습니다. */
        fun writeMetadata(directory: File, recording: SavedBroadcastRecording) {
            val temp = File(directory, "$METADATA_FILE_NAME.tmp")
            temp.writeText(recording.toJson().toString())
            val target = File(directory, METADATA_FILE_NAME)
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "메타데이터를 저장하지 못했습니다." }
            }
        }
    }
}
