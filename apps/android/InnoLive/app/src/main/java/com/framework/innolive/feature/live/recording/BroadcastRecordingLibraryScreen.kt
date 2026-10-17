package com.framework.innolive.feature.live.recording

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.formatBroadcastDuration
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.asString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * 저장된 방송 목록입니다. 각 항목을 5가지 형식(블러 전·후 × 오디오 유무, 오디오만)으로 다운로드 폴더에
 * 내보내거나 삭제합니다. 방송 중에는 송출 자원을 지키려고 내보내기를 막습니다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastRecordingLibraryScreen(
    canExport: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { BroadcastRecordingStore(BroadcastRecordingStore.defaultRoot(context)) }
    val exporter = remember(store) { BroadcastRecordingExporter(context.applicationContext, store) }
    val scope = rememberCoroutineScope()
    var recordings by remember { mutableStateOf<List<SavedBroadcastRecording>?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var exportTarget by remember { mutableStateOf<SavedBroadcastRecording?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedBroadcastRecording?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<UiText?>(null) }

    LaunchedEffect(reloadKey) {
        recordings = withContext(Dispatchers.IO) { runCatching { store.list() }.getOrDefault(emptyList()) }
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
        if (!canExport) {
            Text(
                stringResource(R.string.recording_export_unavailable_live),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val list = recordings
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
                            Text(
                                formatBroadcastDuration(recording.durationMs) + " · " +
                                    Formatter.formatShortFileSize(context, recording.bytes) + " · " +
                                    stringResource(
                                        if (recording.hasProcessed) R.string.recording_item_processed_available
                                        else R.string.recording_item_original_only,
                                    ),
                            )
                        },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { exportTarget = recording }, enabled = canExport && !busy) {
                                    Text(stringResource(R.string.action_download))
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
        RecordingExportDialog(
            recording = recording,
            busy = busy,
            onSelect = { format ->
                busy = true
                message = UiText.Resource(R.string.recording_export_in_progress)
                scope.launch {
                    try {
                        val exported = exporter.export(recording, format)
                        message = UiText.Resource(R.string.recording_export_done, listOf(exported.displayName))
                        exportTarget = null
                    } catch (error: CancellationException) {
                        throw error
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
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.recording_delete_title)) },
            text = {
                Text(stringResource(R.string.recording_delete_description, Formatter.formatShortFileSize(context, recording.bytes)))
            },
            confirmButton = {
                Button(onClick = {
                    deleteTarget = null
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) { runCatching { store.delete(recording) }.getOrDefault(false) }
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
}

@Composable
private fun RecordingExportDialog(
    recording: SavedBroadcastRecording,
    busy: Boolean,
    onSelect: (RecordingExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recording_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.recording_export_description), style = MaterialTheme.typography.bodySmall)
                RecordingExportFormat.entries.forEach { format ->
                    TextButton(
                        onClick = { onSelect(format) },
                        enabled = !busy && format.isAvailable(recording),
                        modifier = Modifier.fillMaxWidth().testTag("recording-export-${format.name}"),
                    ) { Text(stringResource(format.labelRes)) }
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
