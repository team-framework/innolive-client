package com.framework.innolive.feature.live

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder

internal class AudioInputRouteMonitor(
    context: Context,
    private val onRouteChanged: (deviceId: Int?, isSilenced: Boolean) -> Unit,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private var knownSessionIds = emptySet<Int>()
    private var recordingSessionId: Int? = null
    private var started = false
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            reportRoute(configs)
        }
    }

    fun start() {
        if (started) return

        knownSessionIds = audioManager.activeRecordingConfigurations
            .map(AudioRecordingConfiguration::getClientAudioSessionId)
            .toSet()
        audioManager.registerAudioRecordingCallback(recordingCallback, null)
        started = true
    }

    fun refresh() {
        if (started) reportRoute(audioManager.activeRecordingConfigurations)
    }

    override fun close() {
        if (!started) return

        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        started = false
        recordingSessionId = null
    }

    private fun reportRoute(configs: List<AudioRecordingConfiguration>) {
        val config = if (recordingSessionId == null) {
            configs.firstOrNull { config ->
                config.clientAudioSessionId !in knownSessionIds &&
                    config.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }?.also { config ->
                recordingSessionId = config.clientAudioSessionId
            }
        } else {
            configs.firstOrNull { it.clientAudioSessionId == recordingSessionId }
        } ?: return

        onRouteChanged(config.audioDevice?.id, config.isClientSilenced)
    }
}
