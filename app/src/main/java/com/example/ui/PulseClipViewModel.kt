package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.PulseClipApplication
import com.example.data.local.ClipStatus
import com.example.data.local.MonitoredChannelEntity
import com.example.data.local.RecordedClipEntity
import com.example.data.prefs.PipelineConfig
import com.example.data.youtube.YouTubeResult
import com.example.engine.ActiveRecordingBannerState
import com.example.engine.ChannelLiveTelemetry
import com.example.engine.EmbeddedPlayerManager
import com.example.engine.EmbeddedPlayerStatus
import com.example.engine.EngineLogEntry
import com.example.engine.PipelineQueueTelemetry
import com.example.engine.ResourceSnapshot
import com.example.service.StreamMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface AddChannelState {
    data object Idle : AddChannelState
    data object Resolving : AddChannelState
    data class Error(val message: String) : AddChannelState
}

data class PulseClipUiState(
    val isEngineRunning: Boolean = false,
    val isScreenCaptureActive: Boolean = false,
    val channels: List<MonitoredChannelEntity> = emptyList(),
    val telemetryByChannel: Map<String, ChannelLiveTelemetry> = emptyMap(),
    val playerStatusByChannel: Map<String, EmbeddedPlayerStatus> = emptyMap(),
    val activeRecording: ActiveRecordingBannerState? = null,
    val clips: List<RecordedClipEntity> = emptyList(),
    val config: PipelineConfig = PipelineConfig(),
    val queueTelemetry: PipelineQueueTelemetry = PipelineQueueTelemetry(),
    val resourceSnapshot: ResourceSnapshot = ResourceSnapshot(),
    val logs: List<EngineLogEntry> = emptyList(),
    val addChannelState: AddChannelState = AddChannelState.Idle,
    val snackbarMessage: String? = null,
    val isYouTubeSignedIn: Boolean = false,
    val showYouTubeLoginPopup: Boolean = false
) {
    val liveChannelCount: Int
        get() = channels.count { ch ->
            telemetryByChannel[ch.channelId]?.isLive == true || ch.isLive
        }

    val savedClips: List<RecordedClipEntity>
        get() = clips.filter { it.status == ClipStatus.SAVED }

    val activePipelineJobs: List<RecordedClipEntity>
        get() = clips.filter { it.status == ClipStatus.QUEUED || it.status == ClipStatus.ENCODING }

    val totalSavedBytes: Long
        get() = savedClips.sumOf { it.fileSizeBytes }
}

class PulseClipViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PulseClipApplication
    private val container = app.container

    val embeddedPlayerManager: EmbeddedPlayerManager
        get() = container.embeddedPlayerManager

    private val addChannelState = MutableStateFlow<AddChannelState>(AddChannelState.Idle)
    private val snackbarMessage = MutableStateFlow<String?>(null)

    private val coreDataFlow = combine(
        container.database.channelDao().observeAllChannels(),
        container.database.clipDao().observeAllClips(),
        container.settingsRepository.configFlow,
        container.monitorEngine.state,
        container.clipProcessingPipeline.telemetry
    ) { channels, clips, config, engineState, queueTelemetry ->
        CoreBundle(channels, clips, config, engineState, queueTelemetry)
    }

    private val authUiFlow = combine(
        container.embeddedPlayerManager.isYouTubeSignedIn,
        container.embeddedPlayerManager.loginPopupRequested,
        addChannelState,
        snackbarMessage
    ) { signedIn, showLogin, addState, snack ->
        AuthAndTransientBundle(signedIn, showLogin, addState, snack)
    }

    val uiState: StateFlow<PulseClipUiState> = combine(
        coreDataFlow,
        container.screenCaptureManager.isActive,
        container.embeddedPlayerManager.playerStatuses,
        authUiFlow
    ) { core, screenActive, playerStatuses, auth ->
        PulseClipUiState(
            isEngineRunning = core.engineState.isRunning,
            isScreenCaptureActive = screenActive,
            channels = core.channels,
            telemetryByChannel = core.engineState.channelTelemetry,
            playerStatusByChannel = playerStatuses,
            activeRecording = core.engineState.activeRecording,
            clips = core.clips,
            config = core.config,
            queueTelemetry = core.queueTelemetry,
            resourceSnapshot = core.engineState.resourceSnapshot,
            logs = core.engineState.logs,
            addChannelState = auth.addState,
            snackbarMessage = auth.snackbar,
            isYouTubeSignedIn = auth.isYouTubeSignedIn,
            showYouTubeLoginPopup = auth.showYouTubeLoginPopup
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = PulseClipUiState()
    )

    fun toggleEngine() {
        if (uiState.value.isEngineRunning) {
            StreamMonitorService.stop(app)
            postMessage("Background monitoring engine stopped")
        } else {
            StreamMonitorService.start(app)
            postMessage("Multi-channel monitoring started in background")
        }
    }

    fun toggleManualClipCut(enabled: Boolean) {
        updateConfig { it.copy(manualClipCutEnabled = enabled) }
        postMessage(
            if (enabled) "Manual Clip Cut enabled • Use Start Cut / Stop & Save anytime"
            else "Manual Clip Cut turned off • Auto chat-spike mode only"
        )
    }

    fun startManualCut(channel: MonitoredChannelEntity) {
        container.monitorEngine.startManualCut(channel, fixedDurationSec = null)
        postMessage("Manual cut started for ${channel.title} • Tap Stop & Save anytime")
    }

    fun stopManualCut(channelId: String) {
        container.monitorEngine.stopManualCut(channelId)
        postMessage("Cutting & saving 9:16 clip…")
    }

    fun cancelManualCut(channelId: String) {
        container.monitorEngine.cancelManualCut(channelId)
        postMessage("Manual cut cancelled")
    }

    fun triggerManualLiveClip(channel: MonitoredChannelEntity) {
        container.monitorEngine.triggerManualLiveClip(channel)
        postMessage("Capturing quick 9:16 live clip from ${channel.title}…")
    }

    fun attachScreenCapture(resultCode: Int, data: Intent) {
        StreamMonitorService.startWithProjection(app, resultCode, data)
        postMessage("Live screen video bridge attached to 9:16 pipeline")
    }

    fun detachScreenCapture() {
        container.screenCaptureManager.stopCapture()
        container.monitorEngine.appendLog("INFO", "SCREEN", "Screen capture bridge detached")
        postMessage("Screen capture bridge detached")
    }

    fun forcePollAll() {
        container.monitorEngine.triggerImmediatePoll()
        postMessage("Polling tracked YouTube channels…")
    }

    fun addChannel(rawInput: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val entries = com.example.data.youtube.YouTubeRepository.parseMultiLineInputs(rawInput)
            if (entries.isEmpty()) {
                addChannelState.value = AddChannelState.Error(
                    "Paste 1–6 YouTube Live Video links (one per line) or a @handle."
                )
                return@launch
            }

            addChannelState.value = AddChannelState.Resolving
            val maxSlots = uiState.value.config.maxConcurrentChannels.coerceAtLeast(6)
            var addedCount = 0
            var firstError: String? = null

            for (entry in entries.take(maxSlots)) {
                when (val result = container.youTubeRepository.resolveChannel(entry)) {
                    is YouTubeResult.Success -> {
                        val info = result.data
                        val isDirectLive = !info.directVideoId.isNullOrBlank()
                        container.database.channelDao().upsertChannel(
                            MonitoredChannelEntity(
                                channelId = info.channelId,
                                handle = info.handle,
                                title = info.title,
                                avatarUrl = info.avatarUrl,
                                isMonitoringEnabled = true,
                                isLive = isDirectLive,
                                activeVideoId = info.directVideoId,
                                activeLiveTitle = info.liveTitle
                            )
                        )
                        if (isDirectLive && info.directVideoId != null) {
                            container.embeddedPlayerManager.ensurePlayerLoaded(info.channelId, info.directVideoId)
                        }
                        container.monitorEngine.appendLog(
                            "OK",
                            "STREAM",
                            if (isDirectLive) "Added direct 1080p live link ${info.title} (${info.directVideoId})"
                            else "Added ${info.title} (${info.handle}) to multi-stream monitor"
                        )
                        addedCount++
                    }

                    is YouTubeResult.Error -> {
                        if (firstError == null) firstError = result.message
                    }
                }
            }

            if (addedCount > 0) {
                addChannelState.value = AddChannelState.Idle
                postMessage(
                    if (addedCount == 1) "Live stream added • 1080p player & chat trigger armed"
                    else "Added $addedCount live streams • 1080p players & chat triggers armed"
                )
                onSuccess()
                container.monitorEngine.triggerImmediatePoll()
            } else {
                addChannelState.value = AddChannelState.Error(
                    firstError ?: "Could not add live link. Paste a youtube.com/live/… or youtube.com/watch?v=… URL."
                )
            }
        }
    }

    fun clearAddChannelError() {
        addChannelState.value = AddChannelState.Idle
    }

    fun toggleChannelEnabled(channelId: String, enabled: Boolean) {
        viewModelScope.launch {
            container.database.channelDao().setMonitoringEnabled(channelId, enabled)
            if (!enabled) {
                container.embeddedPlayerManager.releaseChannel(channelId)
            }
        }
    }

    fun removeChannel(channelId: String) {
        viewModelScope.launch {
            container.embeddedPlayerManager.releaseChannel(channelId)
            container.database.channelDao().deleteChannel(channelId)
            postMessage("Channel removed from monitor")
        }
    }

    fun togglePlayerMute(channelId: String) {
        container.embeddedPlayerManager.toggleMute(channelId)
    }

    fun reloadPlayer(channelId: String) {
        container.embeddedPlayerManager.reloadPlayer(channelId)
    }

    fun openYouTubeSignIn() {
        container.embeddedPlayerManager.requestLoginPopup()
    }

    fun dismissYouTubeSignIn() {
        container.embeddedPlayerManager.dismissLoginPopup()
    }

    fun completeYouTubeSignIn() {
        container.embeddedPlayerManager.onLoginCompleted()
        container.monitorEngine.appendLog("OK", "AUTH", "YouTube sign-in saved • Reloading all 1080p live players")
        postMessage("YouTube signed in • Playing 1080p live streams now!")
    }

    fun updateConfig(transform: (PipelineConfig) -> PipelineConfig) {
        viewModelScope.launch {
            container.settingsRepository.updateConfig(transform)
        }
    }

    fun deleteClip(clip: RecordedClipEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (clip.contentUri.startsWith("content://")) {
                    runCatching {
                        app.contentResolver.delete(Uri.parse(clip.contentUri), null, null)
                    }
                }
                if (clip.filePath.isNotBlank()) {
                    runCatching { File(clip.filePath).delete() }
                }
                if (clip.thumbnailPath.isNotBlank()) {
                    runCatching { File(clip.thumbnailPath).delete() }
                }
            }
            container.database.clipDao().deleteClip(clip.id)
            postMessage("Deleted clip from local storage")
        }
    }

    fun consumeSnackbar() {
        snackbarMessage.value = null
    }

    fun dismissPipelineBanner() {
        container.clipProcessingPipeline.dismissLastStatusBanner()
    }

    private fun postMessage(msg: String) {
        snackbarMessage.value = msg
    }

    private data class AuthAndTransientBundle(
        val isYouTubeSignedIn: Boolean,
        val showYouTubeLoginPopup: Boolean,
        val addState: AddChannelState,
        val snackbar: String?
    )

    private data class CoreBundle(
        val channels: List<MonitoredChannelEntity>,
        val clips: List<RecordedClipEntity>,
        val config: PipelineConfig,
        val engineState: com.example.engine.MonitorEngineState,
        val queueTelemetry: PipelineQueueTelemetry
    )
}
