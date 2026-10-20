package com.framework.innolive.feature.live.recording

import android.content.ActivityNotFoundException
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.formatBroadcastDuration
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.asString
import com.framework.innolive.ui.theme.InnoLiveAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * 저장된 방송 목록입니다(iOS `BroadcastRecordingLibraryView`). 열 때 앱 종료로 마치지 못한 녹화를 복구하고,
 * 각 항목을 블러 전·후 중 하나로 골라 iOS와 같은 세 동작(영상과 음성 공유, 분리 공유, 갤러리 저장)으로
 * 내보내거나 삭제합니다. 방송 준비·방송 중에는 이 화면에 들어올 수 없습니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastRecordingLibraryScreen(
    isSaving: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { BroadcastRecordingStore(BroadcastRecordingStore.defaultRoot(context)) }
    val exporter = remember(store) { BroadcastRecordingExporter(context.applicationContext, store) }
    val scope = rememberCoroutineScope()
    var library by remember { mutableStateOf<BroadcastRecordingLibrary?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var exportTarget by remember { mutableStateOf<SavedBroadcastRecording?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedBroadcastRecording?>(null) }
    var deletePreserved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<UiText?>(null) }

    // 저장이 끝나면 목록을 다시 읽습니다.
    LaunchedEffect(reloadKey, isSaving) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { exporter.cleanupStaleShares() }
            runCatching { store.load() }.getOrDefault(BroadcastRecordingLibrary(emptyList(), emptyList()))
        }
        library = loaded
        if (loaded.recoveredCount > 0) {
            message = UiText.Resource(R.string.recording_library_recovered, listOf(loaded.recoveredCount))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.recording_library_title)) },
            navigationIcon = {
                IconButton(onClick = onBack, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        message?.let { text ->
            Text(
                text = text.asString(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (isSaving) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                Text(stringResource(R.string.recording_library_saving), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val preserved = library?.preserved.orEmpty()
        if (preserved.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("recording-preserved"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.recording_library_preserved, preserved.size,
                        Formatter.formatShortFileSize(context, preserved.sumOf { it.bytes })),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { deletePreserved = true }, enabled = !busy) {
                    Text(stringResource(R.string.recording_library_preserved_delete))
                }
            }
        }
        val list = library?.recordings
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.recording_library_empty), style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(list, key = SavedBroadcastRecording::id) { recording ->
                    ListItem(
                        modifier = Modifier.testTag("recording-${recording.id}"),
                        headlineContent = {
                            Text(DateFormat.getMediumDateFormat(context).format(Date(recording.createdAtMillis)) + " " +
                                DateFormat.getTimeFormat(context).format(Date(recording.createdAtMillis)))
                        },
                        supportingContent = {
                            val details = listOfNotNull(
                                formatBroadcastDuration(recording.durationMs),
                                Formatter.formatShortFileSize(context, recording.bytes),
                                stringResource(
                                    if (recording.hasProcessed) R.string.recording_item_processed_available
                                    else R.string.recording_item_original_only,
                                ),
                                if (recording.recovered) stringResource(R.string.recording_item_recovered) else null,
                            )
                            Text(details.joinToString(" · "))
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { exportTarget = recording }, enabled = !busy) {
                                    Text(stringResource(R.string.action_export))
                                }
                                TextButton(onClick = { deleteTarget = recording }, enabled = !busy) {
                                    Text(stringResource(R.string.action_delete))
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    exportTarget?.let { recording ->
        val chooserTitle = stringResource(R.string.recording_export_chooser_title)
        RecordingExportDialog(
            recording = recording,
            busy = busy,
            onSelect = { source, action ->
                busy = true
                message = UiText.Resource(R.string.recording_export_in_progress)
                scope.launch {
                    try {
                        if (action == RecordingExportAction.SAVE_TO_GALLERY) {
                            val name = exporter.saveToGallery(recording, source)
                            message = UiText.Resource(R.string.recording_export_gallery_done, listOf(name))
                        } else {
                            val share = exporter.prepareShare(recording, source, split = action == RecordingExportAction.SHARE_SPLIT)
                            message = null
                            context.startActivity(exporter.chooserIntent(share, chooserTitle))
                        }
                        exportTarget = null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: RecordingExportException) {
                        message = UiText.Resource(
                            if (error.insufficientSpace) R.string.recording_export_no_space else R.string.recording_export_failed,
                        )
                    } catch (_: ActivityNotFoundException) {
                        message = UiText.Resource(R.string.recording_export_failed)
                    } catch (_: Exception) {
                        message = UiText.Resource(R.string.recording_export_failed)
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { if (!busy) exportTarget = null },
        )
    }

    deleteTarget?.let { recording ->
        InnoLiveAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.recording_delete_title)) },
            text = {
                Text(stringResource(R.string.recording_delete_description, Formatter.formatShortFileSize(context, recording.bytes)))
            },
            confirmButton = {
                Button(onClick = {
                    deleteTarget = null
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            runCatching { exporter.deleteShares(recording.id) }
                            runCatching { store.delete(recording) }.getOrDefault(false)
                        }
                        message = if (deleted) null else UiText.Resource(R.string.recording_delete_failed)
                        reloadKey++
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (deletePreserved) {
        val preserved = library?.preserved.orEmpty()
        InnoLiveAlertDialog(
            onDismissRequest = { deletePreserved = false },
            title = { Text(stringResource(R.string.recording_library_preserved_delete_title)) },
            text = {
                Text(stringResource(R.string.recording_library_preserved_delete_description,
                    Formatter.formatShortFileSize(context, preserved.sumOf { it.bytes })))
            },
            confirmButton = {
                Button(onClick = {
                    deletePreserved = false
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) {
                            runCatching { store.deletePreserved(preserved) }.getOrDefault(false)
                        }
                        message = if (deleted) null else UiText.Resource(R.string.recording_delete_failed)
                        reloadKey++
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { deletePreserved = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun RecordingExportDialog(
    recording: SavedBroadcastRecording,
    busy: Boolean,
    onSelect: (RecordingExportSource, RecordingExportAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var source by remember(recording.id) { mutableStateOf(RecordingExportSource.ORIGINAL) }
    InnoLiveAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recording_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.recording_export_description), style = MaterialTheme.typography.bodySmall)
                RecordingExportSource.entries.forEach { option ->
                    val available = option.isAvailable(recording)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = source == option, enabled = available && !busy, role = Role.RadioButton) {
                                source = option
                            }
                            .testTag("recording-export-source-${option.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = source == option, onClick = null, enabled = available && !busy)
                        Text(stringResource(option.labelRes), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                RecordingExportAction.entries.forEach { action ->
                    TextButton(
                        onClick = { onSelect(source, action) },
                        enabled = !busy && source.isAvailable(recording),
                        modifier = Modifier.fillMaxWidth().testTag("recording-export-${action.name}"),
                    ) { Text(stringResource(action.labelRes)) }
                }
                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_close)) }
        },
    )
}
