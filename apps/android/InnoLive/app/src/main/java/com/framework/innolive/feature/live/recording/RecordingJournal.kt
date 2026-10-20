package com.framework.innolive.feature.live.recording

import org.json.JSONObject
import java.io.File

/**
 * 저장을 끝내기 전 녹화의 복구 기록입니다(iOS `RecordingRecoveryJournal`과 같은 상태). 앱이 저장 도중
 * 종료되면 다음에 보관함을 열 때 [Status.mayRecover]인 녹화만 조각 MP4를 마무리해 목록에 올립니다.
 */
internal data class RecordingJournal(
    val id: String,
    val sessionId: String?,
    val provider: String,
    val createdAtMillis: Long,
    val status: Status,
    /** 서버가 돌려준 영상(블러 후)의 왕복 지연입니다. 복구할 때도 영상 트랙 시작에서 뺍니다. */
    val processedDelayUs: Long = 0L,
) {
    enum class Status(val wireValue: String) {
        PROVISIONAL("provisional"),
        CONFIRMED("confirmed"),
        FINALIZING("finalizing"),
        WRITER_COMPLETED("writer_completed"),
        FAILED("failed"),
        ABORTED("aborted");

        /** 방송 시작을 확인한 녹화만 복구합니다. 실패한 녹화는 iOS처럼 파일만 보관합니다. */
        val mayRecover: Boolean get() = this == CONFIRMED || this == FINALIZING || this == WRITER_COMPLETED

        companion object {
            fun from(value: String?): Status? = entries.firstOrNull { it.wireValue == value }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("session_id", sessionId ?: JSONObject.NULL)
        .put("provider", provider)
        .put("created_at", createdAtMillis)
        .put("status", status.wireValue)
        .put("processed_delay_us", processedDelayUs)

    companion object {
        const val FILE_NAME = "journal.json"

        fun fromJson(json: JSONObject): RecordingJournal? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val status = Status.from(json.optString("status")) ?: return null
            return RecordingJournal(
                id = id,
                sessionId = if (json.isNull("session_id")) null else json.optString("session_id").takeIf { it.isNotBlank() },
                provider = json.optString("provider", "youtube"),
                createdAtMillis = json.optLong("created_at", 0L),
                status = status,
                processedDelayUs = json.optLong("processed_delay_us", 0L).coerceAtLeast(0L),
            )
        }

        fun read(directory: File): RecordingJournal? {
            val file = File(directory, FILE_NAME)
            if (!file.isFile) return null
            return runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()?.takeIf { it.id == directory.name }
        }

        /** 임시 파일에 쓴 뒤 이름을 바꿔 반쯤 쓴 기록을 남기지 않습니다. */
        fun write(directory: File, journal: RecordingJournal) {
            writeAtomically(directory, FILE_NAME, journal.toJson().toString())
        }

        fun delete(directory: File) {
            File(directory, FILE_NAME).delete()
        }
    }
}

/** 같은 폴더의 임시 파일에 쓴 뒤 이름을 바꿉니다. */
internal fun writeAtomically(directory: File, name: String, text: String) {
    val temp = File(directory, "$name.tmp")
    temp.writeText(text)
    val target = File(directory, name)
    if (!temp.renameTo(target)) {
        target.delete()
        check(temp.renameTo(target)) { "$name 파일을 저장하지 못했습니다." }
    }
}

/**
 * 이 프로세스가 쓰거나 마무리하는 중인 녹화 폴더입니다(iOS `ownedPaths`). 다른 저장소 인스턴스(보관함 화면)가
 * 진행 중인 녹화를 복구하거나 지우지 않게 합니다. 프로세스가 죽으면 비므로 남은 폴더는 복구 대상입니다.
 */
internal object RecordingOwnership {
    private val owned = mutableSetOf<String>()

    @Synchronized fun claim(id: String) { owned += id }

    /** 아무도 쓰지 않는 폴더면 점유하고 true를 돌려줍니다. */
    @Synchronized fun tryClaim(id: String): Boolean = owned.add(id)

    @Synchronized fun release(id: String) { owned -= id }

    @Synchronized fun isOwned(id: String): Boolean = id in owned
}
