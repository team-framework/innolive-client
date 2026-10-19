package com.framework.innolive.feature.live.recording

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import com.framework.innolive.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** 내보낼 영상입니다. Android는 블러 전·후 영상을 모두 저장하므로 고른 쪽을 내보냅니다. */
enum class RecordingExportSource(@param:StringRes val labelRes: Int, internal val fileSuffix: String) {
    ORIGINAL(R.string.recording_export_source_original, "original"),
    PROCESSED(R.string.recording_export_source_processed, "blurred");

    fun isAvailable(recording: SavedBroadcastRecording): Boolean = this == ORIGINAL || recording.hasProcessed
}

/** iOS 내보내기 시트와 같은 세 가지 동작입니다. 공유 두 가지는 시스템 공유 시트를 엽니다. */
enum class RecordingExportAction(@param:StringRes val labelRes: Int) {
    /** 영상과 음성 MP4 한 파일을 공유합니다. 저장 파일을 복사하지 않습니다. */
    SHARE_VIDEO(R.string.recording_export_share_video),

    /** 무음 MP4와 M4A 두 파일로 나눠 공유합니다. */
    SHARE_SPLIT(R.string.recording_export_share_split),

    /** 갤러리(Movies/InnoLive)에 MP4로 저장합니다(iOS 사진 앱 저장). */
    SAVE_TO_GALLERY(R.string.recording_export_save_gallery),
}

/** 내보내는 파일 하나의 종류입니다. */
internal enum class RecordingExportPart(val suffix: String, val extension: String, val mimeType: String) {
    FULL("", "mp4", "video/mp4"),
    VIDEO("-video", "mp4", "video/mp4"),
    AUDIO("-audio", "m4a", "audio/mp4"),
}

/** 내보낼 파일 이름입니다. 방송 시작 시각과 영상·파일 종류로 구분합니다. */
internal fun exportFileName(recording: SavedBroadcastRecording, source: RecordingExportSource, part: RecordingExportPart): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(recording.createdAtMillis))
    return "InnoLive_${stamp}_${source.fileSuffix}${part.suffix}.${part.extension}"
}

/** 내보내기에 필요한 여유 공간입니다. 파일 크기에 기록 여유를 더합니다. */
internal fun requiredExportBytes(sourceBytes: Long): Long = sourceBytes + EXPORT_SPACE_MARGIN_BYTES

internal const val EXPORT_SPACE_MARGIN_BYTES = 32L * 1024 * 1024

internal class RecordingExportException(
    message: String,
    val insufficientSpace: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 공유 시트에 넘길 파일입니다. */
internal class RecordingSharePackage(val uris: List<Uri>, val names: List<String>, val mimeType: String)

/**
 * 저장한 녹화를 iOS처럼 시스템 공유 시트나 갤러리로 내보냅니다. 앱 안의 원본 파일은 바꾸지 않습니다.
 *
 * - 영상과 음성: 저장 파일에 하드 링크를 만들어 FileProvider로 공유합니다. 링크를 만들 수 없을 때만 복사합니다.
 * - 분리: 트랙을 하나씩 새 파일로 옮겨(재인코딩 없음, 시작 오프셋 유지) 두 파일을 공유합니다.
 * - 갤러리: 저장 파일을 `Movies/InnoLive`에 한 번 복사합니다.
 *
 * 복사가 필요한 동작은 시작 전에 여유 공간을 확인합니다. 공유 파일은 다른 앱이 늦게 읽을 수 있어 바로 지우지
 * 않고, 하루가 지난 것과 삭제한 녹화의 것을 정리합니다.
 */
internal class BroadcastRecordingExporter(
    private val context: Context,
    private val store: BroadcastRecordingStore,
    private val shareRoot: File = File(context.cacheDir, SHARE_DIRECTORY),
) {
    private fun sourceFile(recording: SavedBroadcastRecording, source: RecordingExportSource): File {
        if (!source.isAvailable(recording)) throw RecordingExportException("블러 후 파일이 없는 녹화입니다.")
        val file = if (source == RecordingExportSource.PROCESSED) store.processedFile(recording) else store.originalFile(recording)
        if (!file.isFile || file.length() == 0L) throw RecordingExportException("저장 파일을 찾을 수 없습니다.")
        return file
    }

    suspend fun prepareShare(recording: SavedBroadcastRecording, source: RecordingExportSource, split: Boolean): RecordingSharePackage =
        withContext(Dispatchers.IO) {
            val file = sourceFile(recording, source)
            val directory = File(File(shareRoot, recording.id), UUID.randomUUID().toString())
            check(directory.mkdirs()) { "공유 폴더를 만들지 못했습니다." }
            try {
                val outputs = if (!split) {
                    val target = File(directory, exportFileName(recording, source, RecordingExportPart.FULL))
                    linkOrCopy(file, target)
                    listOf(target)
                } else {
                    requireSpace(directory, requiredExportBytes(file.length()))
                    val video = File(directory, exportFileName(recording, source, RecordingExportPart.VIDEO))
                    val audio = File(directory, exportFileName(recording, source, RecordingExportPart.AUDIO))
                    Mp4TrackExtractor.extract(file, video, audio = false)
                    coroutineContext.ensureActive()
                    Mp4TrackExtractor.extract(file, audio, audio = true)
                    listOf(video, audio)
                }
                coroutineContext.ensureActive()
                outputs.forEach { if (!it.isFile || it.length() == 0L) throw RecordingExportException("내보낸 파일이 비어 있습니다.") }
                val authority = "${context.packageName}$PROVIDER_SUFFIX"
                RecordingSharePackage(
                    uris = outputs.map { FileProvider.getUriForFile(context, authority, it) },
                    names = outputs.map { it.name },
                    mimeType = if (split) "*/*" else RecordingExportPart.FULL.mimeType,
                )
            } catch (error: Exception) {
                directory.deleteRecursively()
                throw error
            }
        }

    /** 공유 시트를 여는 Intent입니다. 받는 앱에 읽기 권한만 줍니다. */
    fun chooserIntent(share: RecordingSharePackage, title: CharSequence): Intent {
        val send = if (share.uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, share.uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(share.uris))
        }
        send.type = share.mimeType
        send.clipData = ClipData.newRawUri(share.names.first(), share.uris.first()).apply {
            share.uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** 갤러리에 MP4로 저장하고 표시 이름을 돌려줍니다. `IS_PENDING`으로 숨긴 채 복사한 뒤 공개합니다. */
    suspend fun saveToGallery(recording: SavedBroadcastRecording, source: RecordingExportSource): String =
        withContext(Dispatchers.IO) {
            val file = sourceFile(recording, source)
            @Suppress("DEPRECATION")
            requireSpace(Environment.getExternalStorageDirectory(), requiredExportBytes(file.length()))
            val name = exportFileName(recording, source, RecordingExportPart.FULL)
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, RecordingExportPart.FULL.mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/InnoLive")
                put(MediaStore.Video.Media.DATE_TAKEN, recording.createdAtMillis)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw RecordingExportException("갤러리에 파일을 만들지 못했습니다.")
            try {
                resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: throw RecordingExportException("갤러리 파일을 열지 못했습니다.")
                coroutineContext.ensureActive()
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                name
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }

    /** 하루가 지난 공유 파일을 지웁니다. 보관함을 열 때 부릅니다. */
    fun cleanupStaleShares(nowMillis: Long = System.currentTimeMillis()) {
        shareRoot.listFiles()?.forEach { recordingDirectory ->
            recordingDirectory.listFiles()?.forEach { share ->
                if (nowMillis - share.lastModified() > SHARE_RETENTION_MILLIS) share.deleteRecursively()
            }
            if (recordingDirectory.listFiles().isNullOrEmpty()) recordingDirectory.delete()
        }
    }

    /** 삭제한 녹화의 공유 파일(하드 링크 포함)을 지워 공간을 돌려받습니다. */
    fun deleteShares(recordingId: String) {
        File(shareRoot, recordingId).deleteRecursively()
    }

    private fun linkOrCopy(source: File, target: File) {
        try {
            Os.link(source.absolutePath, target.absolutePath)
            return
        } catch (error: Exception) {
            RecordingLog.w("share_link_failed type=${error.javaClass.simpleName}")
        }
        requireSpace(target.parentFile ?: shareRoot, requiredExportBytes(source.length()))
        source.copyTo(target, overwrite = true)
    }

    private fun requireSpace(directory: File, bytes: Long) {
        val available = runCatching { directory.usableSpace }.getOrDefault(0L)
        if (available < bytes) throw RecordingExportException("저장 공간이 부족합니다.", insufficientSpace = true)
    }

    internal companion object {
        const val SHARE_DIRECTORY = "recording-shares"
        const val PROVIDER_SUFFIX = ".recordings"
        const val SHARE_RETENTION_MILLIS = 24L * 60 * 60 * 1000
    }
}
