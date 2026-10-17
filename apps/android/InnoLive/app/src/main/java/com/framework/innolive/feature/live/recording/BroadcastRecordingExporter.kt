package com.framework.innolive.feature.live.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.StringRes
import com.framework.innolive.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** 다운로드 폴더에 내보내는 형식입니다. 모두 재인코딩 없이 저장 파일의 트랙을 복사합니다. */
enum class RecordingExportFormat(
    @param:StringRes val labelRes: Int,
    val fileSuffix: String,
    val extension: String,
    /** 블러 후(실제 송출) 파일을 원본으로 씁니다. 해당 파일이 없는 녹화에서는 고를 수 없습니다. */
    val usesProcessed: Boolean,
    val includesVideo: Boolean,
    val includesAudio: Boolean,
) {
    ORIGINAL_WITH_AUDIO(R.string.recording_export_original_audio, "original", "mp4", false, true, true),
    ORIGINAL_VIDEO_ONLY(R.string.recording_export_original_muted, "original-muted", "mp4", false, true, false),
    PROCESSED_WITH_AUDIO(R.string.recording_export_processed_audio, "blurred", "mp4", true, true, true),
    PROCESSED_VIDEO_ONLY(R.string.recording_export_processed_muted, "blurred-muted", "mp4", true, true, false),
    AUDIO_ONLY(R.string.recording_export_audio, "audio", "m4a", false, false, true);

    val mimeType: String get() = if (includesVideo) "video/mp4" else "audio/mp4"

    /** 트랙을 그대로 복사하는 형식은 파일 복사로 충분합니다. */
    val isFullCopy: Boolean get() = includesVideo && includesAudio

    fun isAvailable(recording: SavedBroadcastRecording): Boolean = !usesProcessed || recording.hasProcessed
}

/** 다운로드 폴더에 저장할 파일 이름입니다. 방송 시작 시각과 형식으로 구분합니다. */
internal fun exportFileName(recording: SavedBroadcastRecording, format: RecordingExportFormat): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(recording.createdAtMillis))
    return "InnoLive_${stamp}_${format.fileSuffix}.${format.extension}"
}

internal data class ExportedRecording(val displayName: String, val uri: Uri)

internal class RecordingExportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 저장한 녹화를 다운로드 폴더(`Download/InnoLive`)로 내보냅니다. 블러 전·후 영상은 각 파일을 그대로 복사하고,
 * 무음 영상과 음성 파일은 해당 트랙만 다시 담습니다. 앱 안의 원본 파일은 바꾸지 않습니다.
 */
internal class BroadcastRecordingExporter(
    private val context: Context,
    private val store: BroadcastRecordingStore,
) {
    suspend fun export(recording: SavedBroadcastRecording, format: RecordingExportFormat): ExportedRecording =
        withContext(Dispatchers.IO) {
            if (!format.isAvailable(recording)) throw RecordingExportException("블러 후 파일이 없는 녹화입니다.")
            val source = if (format.usesProcessed) store.processedFile(recording) else store.originalFile(recording)
            if (!source.isFile) throw RecordingExportException("저장 파일을 찾을 수 없습니다.")
            val name = exportFileName(recording, format)
            val workDirectory = File(File(context.cacheDir, "recording-exports"), UUID.randomUUID().toString())
            check(workDirectory.mkdirs()) { "임시 폴더를 만들지 못했습니다." }
            try {
                val prepared = File(workDirectory, name)
                if (format.isFullCopy) source.copyTo(prepared, overwrite = true)
                else remuxTracks(source, prepared, format.includesVideo, format.includesAudio)
                coroutineContext.ensureActive()
                if (!prepared.isFile || prepared.length() == 0L) throw RecordingExportException("내보낸 파일이 비어 있습니다.")
                ExportedRecording(name, saveToDownloads(prepared, name, format.mimeType))
            } finally {
                workDirectory.deleteRecursively()
            }
        }

    /** `IS_PENDING`으로 숨긴 채 복사한 뒤 공개합니다. 실패하면 만들다 만 항목을 지웁니다. */
    private fun saveToDownloads(file: File, displayName: String, mimeType: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/InnoLive")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RecordingExportException("다운로드 폴더에 파일을 만들지 못했습니다.")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw RecordingExportException("다운로드 파일을 열지 못했습니다.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}

/** 원본 MP4에서 고른 트랙만 새 MP4(또는 M4A)에 다시 담습니다. 샘플 시각과 키프레임 표시는 유지합니다. */
internal fun remuxTracks(source: File, target: File, includeVideo: Boolean, includeAudio: Boolean) {
    require(includeVideo || includeAudio)
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    try {
        extractor.setDataSource(source.absolutePath)
        val tracks = mutableMapOf<Int, Int>()
        var maxInputSize = 0
        val created = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = created
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val wanted = (includeVideo && mime.startsWith("video/")) || (includeAudio && mime.startsWith("audio/"))
            if (!wanted) continue
            extractor.selectTrack(index)
            tracks[index] = created.addTrack(format)
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxInputSize = maxOf(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
        }
        if (tracks.isEmpty()) throw RecordingExportException("내보낼 트랙이 없습니다.")
        created.start()
        val buffer = ByteBuffer.allocateDirect(maxOf(maxInputSize, DEFAULT_SAMPLE_BUFFER_BYTES))
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val track = tracks[extractor.sampleTrackIndex]
            if (track != null) {
                info.set(0, size, extractor.sampleTime,
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                created.writeSampleData(track, buffer, info)
            }
            if (!extractor.advance()) break
        }
        created.stop()
    } catch (error: Exception) {
        target.delete()
        throw error
    } finally {
        runCatching { muxer?.release() }
        runCatching { extractor.release() }
    }
}

private const val DEFAULT_SAMPLE_BUFFER_BYTES = 4 * 1024 * 1024
