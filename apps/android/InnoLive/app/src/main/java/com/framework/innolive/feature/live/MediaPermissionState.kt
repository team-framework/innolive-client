package com.framework.innolive.feature.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

data class MediaPermissionState(
    val hasCameraPermission: Boolean,
    val hasMicrophonePermission: Boolean,
) {
    val missingPermissions: List<String>
        get() = buildList {
            if (!hasCameraPermission) add(Manifest.permission.CAMERA)
            if (!hasMicrophonePermission) add(Manifest.permission.RECORD_AUDIO)
        }
}

fun readMediaPermissionState(context: Context): MediaPermissionState = MediaPermissionState(
    hasCameraPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED,
    hasMicrophonePermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED,
)

class MediaPermissionController internal constructor(
    private val context: Context,
) {
    var state by mutableStateOf(readMediaPermissionState(context))
        private set

    fun refresh() {
        state = readMediaPermissionState(context)
    }
}

@Composable
fun rememberMediaPermissionController(context: Context): MediaPermissionController {
    val controller = remember(context) { MediaPermissionController(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                controller.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return controller
}
