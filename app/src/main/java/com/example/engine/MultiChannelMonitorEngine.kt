package com.example.engine

import android.content.Context
import com.example.data.local.ChannelDao
import com.example.data.local.MonitoredChannelEntity
import com.example.data.prefs.PipelineConfig
import com.example.data.prefs.SettingsRepository
import com.example.data.youtube.LiveChatEntry
import com.example.data.youtube.LiveChatSession
import com.example.data.youtube.ResolvedStreamMedia
import com.example.data.youtube.YouTubeRepository
import com.example.data.youtube.YouTubeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class ChannelLiveTelemetry(
    val channelId: String,
    val phase: ChannelMonitorPhase = ChannelMonitorPhase.OFFLINE,
    val isLive: Boolean = false,
    val activeVideoId: String? = null,
    val activeLiveTitle: String? = null,
    val concurrentViewers: Long = 0L,
    val baselineVelocityPerMin: Float = 0f,
    val currentVelocityPerMin: Float = 0f,
    val spikeMultiplier: Float = 0f,
    val recordingElapsedSec: Int = 0,
    val recordingTargetSec: Int = 30,
    val recordedVideoMb: Float = 0f,
    val isManualCut: Boolean = false,
    val velocityHistory: List<VelocitySample> = emptyList(),
    val statusNote: String = "Standby"
)

data class ActiveRecordingBannerState(
    val channelId: String,
    val channelTitle: String,
    val elapsedSeconds: Int,
    val targetSeconds: Int,
    val recordedMb: Float,
    val stageLabel: String,
    val isManualCut: Boolean = false
)

data class EngineLogEntry(
    val timestampMs: Long,
    val level: String,
    val tag: String,
    val message: String
)

data class ResourceSnapshot(
    val usedHeapMb: Int = 0,
    val maxHeapMb: Int = 256,
    val activeWorkers: Int = 0,
    val clipsPerFiveMin: Float = 0f
)

data class MonitorEngineState(
    val isRunning: Boolean = false,
    val startedAtMs: Long = 0L,
    val channelTelemetry: Map<String, ChannelLiveTelemetry> = emptyMap(),
    val activeRecording: ActiveRecordingBannerState? = null,
    val logs: List<EngineLogEntry> = emptyList(),
    val resourceSnapshot: ResourceSnapshot = ResourceSnapshot(),
    val lastPollAtMs: Long = 0L
)

class MultiChannelMonitorEngine(
    private val context: Context,
    private val youTubeRepository: YouTubeRepository,
    private val liveVideoRecorder: LiveVideoRecorder,
    private val embeddedPlayerManager: EmbeddedPlayerManager,
    private val channelDao: ChannelDao,
    private val settingsRepository: SettingsRepository,
    private val pipeline: ClipProcessingPipeline,
    private val screenCaptureManager: ScreenCaptureManager
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var coordinatorJob: Job? = null
    private var telemetryJob: Job? = null
    private val channelJobs = ConcurrentHashMap<String, Job>()
    private val trackers = ConcurrentHashMap<String, ChatVelocityTracker>()
    private val cachedMedia = ConcurrentHashMap<String, ResolvedStreamMedia>()
    private val manualCuts = ConcurrentHashMap<String, ManualCutSession>()
    private val autoCutStoppers = ConcurrentHashMap<String, () -> Unit>()
    private val logBuffer = ArrayDeque<EngineLogEntry>(140)
    private val logLock = Any()

    private data class ManualCutSession(
        val stopFlag: AtomicBoolean,
        val cancelFlag: AtomicBoolean,
        val job: Job
    )

    private val _state = MutableStateFlow(MonitorEngineState())
    val state: StateFlow<MonitorEngineState> = _state.asStateFlow()

    private var latestConfig: PipelineConfig = PipelineConfig()

    init {
        engineScope.launch {
            settingsRepository.configFlow.collect { cfg ->
                latestConfig = cfg
            }
        }
        // Automatically update direct live-link cards with real stream title & author from the WebView player
        engineScope.launch {
            embeddedPlayerManager.playerMeta.collect { meta ->
                val displayTitle = meta.author.ifBlank { meta.title }.ifBlank { return@collect }
                val liveTitle = meta.title.ifBlank { null }
                runCatching {
                    channelDao.updateTitleAndLiveTitle(meta.channelId, displayTitle, liveTitle)
                }
                updateChannelTelemetry(meta.channelId) { prev ->
                    prev.copy(
                        isLive = true,
                        activeVideoId = prev.activeVideoId ?: meta.videoId,
                        activeLiveTitle = liveTitle ?: prev.activeLiveTitle
                    )
                }
            }
        }
        refreshResourceSnapshot()
    }

    fun startMonitoring() {
        if (_state.value.isRunning) return
        sweepStaleTempDirs()
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                isRunning = true,
                startedAtMs = now
            )
        }
        appendLog("INFO", "ENGINE", "24×7 multi-stream live video & in-player chat monitor started")

        coordinatorJob?.cancel()
        coordinatorJob = engineScope.launch {
            latestConfig = settingsRepository.configFlow.first()
            channelDao.observeAllChannels().collect { channels ->
                reconcileChannelWorkers(channels)
            }
        }

        telemetryJob?.cancel()
        telemetryJob = engineScope.launch {
            while (isActive) {
                refreshResourceSnapshot()
                delay(2000L)
            }
        }
    }

    fun stopMonitoring() {
        coordinatorJob?.cancel()
        coordinatorJob = null
        telemetryJob?.cancel()
        telemetryJob = null

        channelJobs.values.forEach { it.cancel() }
        channelJobs.clear()
        trackers.clear()
        cachedMedia.clear()

        _state.update {
            it.copy(
                isRunning = false,
                startedAtMs = 0L,
                activeRecording = null,
                channelTelemetry = it.channelTelemetry.mapValues { (_, t) ->
                    t.copy(
                        phase = ChannelMonitorPhase.OFFLINE,
                        recordingElapsedSec = 0,
                        recordedVideoMb = 0f,
                        statusNote = "Standby"
                    )
                }
            )
        }
        refreshResourceSnapshot()
        appendLog("INFO", "ENGINE", "Multi-channel monitoring engine stopped")
    }

    fun triggerImmediatePoll() {
        engineScope.launch {
            val enabled = channelDao.getEnabledChannels()
            if (enabled.isEmpty()) {
                appendLog("WARN", "POLL", "No enabled channels to poll")
                return@launch
            }
            appendLog("INFO", "POLL", "Checking live video streams across ${enabled.size} channel(s)")
            enabled.forEach { ch ->
                launch { checkChannelOnce(ch) }
            }
        }
    }

    /**
     * Quick fixed-length manual cut (uses configured minClipSeconds).
     */
    fun triggerManualLiveClip(channel: MonitoredChannelEntity) {
        startManualCut(
            channel = channel,
            fixedDurationSec = latestConfig.minClipSeconds.coerceIn(30, 60)
        )
    }

    /**
     * Starts an interactive Manual Clip Cut from a live stream's internal 1080p player.
     * - When [fixedDurationSec] is null (Start/Stop mode), records up to 90s until the user taps Stop & Save.
     * - When [fixedDurationSec] is set (Quick Cut), auto-stops after that duration (or earlier if user taps Stop & Save).
     */
    fun startManualCut(channel: MonitoredChannelEntity, fixedDurationSec: Int? = null) {
        if (manualCuts.containsKey(channel.channelId)) return
        val existingTelemetry = _state.value.channelTelemetry[channel.channelId]
        if (existingTelemetry?.phase == ChannelMonitorPhase.RECORDING) return

        val stopFlag = AtomicBoolean(false)
        val cancelFlag = AtomicBoolean(false)

        val job = engineScope.launch {
            val telemetry = _state.value.channelTelemetry[channel.channelId]
            val videoId = telemetry?.activeVideoId
                ?: channel.activeVideoId
                ?: YouTubeRepository.extractDirectVideoId(channel.channelId)
            val liveTitle = telemetry?.activeLiveTitle ?: channel.activeLiveTitle ?: channel.title
            if (videoId.isNullOrBlank()) {
                manualCuts.remove(channel.channelId)
                appendLog("WARN", "MANUAL", "${channel.title} is not currently live")
                return@launch
            }

            val isQuickCut = fixedDurationSec != null
            val maxDurationSec = fixedDurationSec?.coerceIn(10, 90) ?: 90
            appendLog(
                "INFO",
                "MANUAL",
                if (isQuickCut) "Quick manual cut (${maxDurationSec}s) started for ${channel.title}"
                else "Manual clip cut started for ${channel.title} — tap Stop & Save anytime"
            )

            val recordingTask = startBackgroundVideoCapture(
                channelId = channel.channelId,
                channelTitle = channel.title,
                videoId = videoId,
                maxDurationSec = maxDurationSec,
                stopFlag = stopFlag
            )

            var elapsedSec = 0
            try {
                while (isActive && !stopFlag.get() && !cancelFlag.get() && elapsedSec < maxDurationSec) {
                    val playerBytes = embeddedPlayerManager.playerStatuses.value[channel.channelId]?.recordedBytes ?: 0L
                    val prevMb = _state.value.channelTelemetry[channel.channelId]?.recordedVideoMb ?: 0f
                    val liveMb = maxOf(prevMb, playerBytes / (1024f * 1024f))

                    updateChannelTelemetry(channel.channelId) { prev ->
                        prev.copy(
                            isLive = true,
                            activeVideoId = videoId,
                            phase = ChannelMonitorPhase.RECORDING,
                            isManualCut = true,
                            recordingElapsedSec = elapsedSec,
                            recordingTargetSec = maxDurationSec,
                            recordedVideoMb = liveMb,
                            statusNote = String.format(
                                "MANUAL CUT %02ds (%.1f MB) • Tap Stop & Save",
                                elapsedSec,
                                liveMb
                            )
                        )
                    }
                    _state.update {
                        it.copy(
                            activeRecording = ActiveRecordingBannerState(
                                channelId = channel.channelId,
                                channelTitle = channel.title,
                                elapsedSeconds = elapsedSec,
                                targetSeconds = maxDurationSec,
                                recordedMb = liveMb,
                                isManualCut = true,
                                stageLabel = if (isQuickCut) {
                                    String.format("Quick Cut • %02ds / %02ds (%.1f MB)", elapsedSec, maxDurationSec, liveMb)
                                } else {
                                    String.format("Manual Cut active • %.1f MB • Tap Stop & Save anytime", liveMb)
                                }
                            )
                        )
                    }

                    // Sleep 1s in 100ms slices so Stop & Save / Cancel responds instantly
                    for (slice in 0 until 10) {
                        if (stopFlag.get() || cancelFlag.get()) break
                        delay(100L)
                    }
                    if (!stopFlag.get() && !cancelFlag.get()) {
                        elapsedSec++
                    }
                }

                stopFlag.set(true)

                if (cancelFlag.get()) {
                    val discarded = finishBackgroundVideoCapture(recordingTask)
                    discarded?.videoFile?.parentFile?.let { dir ->
                        if (dir.name.startsWith("internal_rec_") || dir.name.startsWith("screen_rec_")) {
                            runCatching { dir.deleteRecursively() }
                        }
                    }
                    _state.update {
                        if (it.activeRecording?.channelId == channel.channelId) it.copy(activeRecording = null) else it
                    }
                    updateChannelTelemetry(channel.channelId) { prev ->
                        prev.copy(
                            phase = ChannelMonitorPhase.MONITORING,
                            isManualCut = false,
                            recordingElapsedSec = 0,
                            recordedVideoMb = 0f,
                            statusNote = "Manual cut cancelled"
                        )
                    }
                    appendLog("INFO", "MANUAL", "Manual clip cut cancelled for ${channel.title}")
                    return@launch
                }

                val finalDurationSec = elapsedSec.coerceAtLeast(3)
                _state.update {
                    it.copy(
                        activeRecording = ActiveRecordingBannerState(
                            channelId = channel.channelId,
                            channelTitle = channel.title,
                            elapsedSeconds = finalDurationSec,
                            targetSeconds = finalDurationSec,
                            recordedMb = _state.value.channelTelemetry[channel.channelId]?.recordedVideoMb ?: 0f,
                            isManualCut = true,
                            stageLabel = "Cutting & finalizing ${finalDurationSec}s clip…"
                        )
                    )
                }

                val recorded = finishBackgroundVideoCapture(recordingTask)
                _state.update {
                    if (it.activeRecording?.channelId == channel.channelId) it.copy(activeRecording = null) else it
                }
                updateChannelTelemetry(channel.channelId) { prev ->
                    prev.copy(
                        phase = ChannelMonitorPhase.MONITORING,
                        isManualCut = false,
                        recordingElapsedSec = 0,
                        recordedVideoMb = 0f,
                        statusNote = if (recorded != null) "Encoding ${finalDurationSec}s manual cut to 9:16 MP4…" else "Manual cut unavailable"
                    )
                }

                if (recorded == null) {
                    pipeline.reportCaptureError(
                        channelTitle = channel.title,
                        reason = "Mini-player video wasn't ready yet. Let the 1080p live player buffer for 2–3 seconds and tap Start Cut again."
                    )
                    return@launch
                }

                val spokenCaptions = resolveSpokenCaptionsForClip(
                    recorded = recorded,
                    channelId = channel.channelId,
                    videoId = videoId,
                    durationSeconds = finalDurationSec
                )

                val baseVel = (telemetry?.baselineVelocityPerMin ?: 10f).coerceAtLeast(2f)
                val curVel = (telemetry?.currentVelocityPerMin ?: baseVel).coerceAtLeast(baseVel)
                appendLog(
                    "OK",
                    "MANUAL",
                    String.format(
                        "Manual cut saved %ds (%s • %.2f MB) -> 9:16 render",
                        finalDurationSec,
                        recorded.sourceLabel,
                        recorded.totalBytes / (1024f * 1024f)
                    )
                )
                pipeline.enqueueRecordedVideo(
                    channelId = channel.channelId,
                    channelTitle = channel.title,
                    videoId = videoId,
                    streamTitle = liveTitle,
                    durationSeconds = finalDurationSec,
                    baselineVelocity = baseVel,
                    peakVelocity = curVel,
                    spikeMultiplier = (curVel / baseVel).coerceAtLeast(1.0f),
                    recorded = recorded,
                    spokenCaptions = spokenCaptions,
                    config = latestConfig
                )
            } finally {
                manualCuts.remove(channel.channelId)
            }
        }

        manualCuts[channel.channelId] = ManualCutSession(
            stopFlag = stopFlag,
            cancelFlag = cancelFlag,
            job = job
        )
    }

    /**
     * Stops an active Manual Cut (or an active Auto-Spike recording) immediately and sends it to 9:16 Render & Save.
     */
    fun stopManualCut(channelId: String) {
        val manual = manualCuts[channelId]
        if (manual != null) {
            manual.stopFlag.set(true)
            return
        }
        // If an auto-spike recording is running on this channel, cut & save it immediately!
        autoCutStoppers[channelId]?.invoke()
    }

    /**
     * Cancels and discards an active Manual Cut without saving.
     */
    fun cancelManualCut(channelId: String) {
        val manual = manualCuts[channelId] ?: return
        manual.cancelFlag.set(true)
        manual.stopFlag.set(true)
    }

    private suspend fun resolveSpokenCaptionsForClip(
        recorded: RecordedRawVideo,
        channelId: String,
        videoId: String,
        durationSeconds: Int
    ): List<CaptionSegment> {
        if (!latestConfig.autoCaptionsEnabled) return emptyList()
        if (recorded.liveVttCaptions.isNotEmpty()) {
            val formatted = AutoCaptionGenerator.formatForVerticalShorts(recorded.liveVttCaptions)
            appendLog("OK", "CAPTIONS", "Attached ${formatted.size} live spoken caption cues from mini-player")
            return formatted
        }
        var media = cachedMedia[channelId]
        if (media?.captionTrackUrl.isNullOrBlank() && videoId.isNotBlank()) {
            val fresh = youTubeRepository.resolveFreshLiveStreamMedia(videoId)
            if (!fresh.captionTrackUrl.isNullOrBlank()) {
                media = fresh
                cachedMedia[channelId] = fresh
            }
        }
        val fetched = youTubeRepository.fetchSpokenCaptions(
            captionTrackUrl = media?.captionTrackUrl,
            durationSeconds = durationSeconds,
            userAgent = media?.userAgent ?: YouTubeRepository.DESKTOP_USER_AGENT
        )
        val formatted = AutoCaptionGenerator.formatForVerticalShorts(fetched)
        if (formatted.isNotEmpty()) {
            appendLog("OK", "CAPTIONS", "Fetched ${formatted.size} YouTube spoken caption cues for editing")
        } else {
            appendLog("INFO", "CAPTIONS", "No spoken captions available on this broadcast (grant Mic permission for on-device live speech captions)")
        }
        return formatted
    }

    private fun sweepStaleTempDirs() {
        engineScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 60L * 60L * 1000L
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isDirectory && f.lastModified() < cutoff && (
                        f.name.startsWith("player_rec_") ||
                            f.name.startsWith("hls_rec_") ||
                            f.name.startsWith("dash_rec_") ||
                            f.name.startsWith("screen_rec_")
                        )
                ) {
                    runCatching { f.deleteRecursively() }
                }
            }
        }
    }

    private suspend fun checkChannelOnce(channel: MonitoredChannelEntity) {
        val directVideoId = YouTubeRepository.extractDirectVideoId(channel.channelId)
        if (!directVideoId.isNullOrBlank()) {
            // Direct Live Video Link: load 1080p player immediately — NEVER fails on Wi-Fi API blocks!
            embeddedPlayerManager.ensurePlayerLoaded(channel.channelId, directVideoId)
            val det = youTubeRepository.detectDirectVideoStream(directVideoId)
            cachedMedia[channel.channelId] = ResolvedStreamMedia(
                hlsManifestUrl = det.hlsManifestUrl,
                userAgent = det.hlsUserAgent,
                dashManifestUrl = det.dashManifestUrl,
                captionTrackUrl = det.captionTrackUrl
            )
            channelDao.updateLiveStatus(
                channelId = channel.channelId,
                checkedAt = System.currentTimeMillis(),
                isLive = true,
                videoId = directVideoId,
                liveTitle = det.title ?: channel.activeLiveTitle ?: "Direct 1080p Live Stream",
                liveChatId = det.liveChatSession?.continuation?.take(32),
                viewers = det.concurrentViewers,
                error = null
            )
            updateChannelTelemetry(channel.channelId) { prev ->
                prev.copy(
                    isLive = true,
                    activeVideoId = directVideoId,
                    activeLiveTitle = det.title ?: prev.activeLiveTitle ?: channel.activeLiveTitle,
                    concurrentViewers = det.concurrentViewers,
                    phase = if (prev.phase == ChannelMonitorPhase.RECORDING) prev.phase else ChannelMonitorPhase.MONITORING,
                    statusNote = "1080p live player active • In-player chat armed"
                )
            }
            appendLog("OK", "LIVE", "${channel.title} direct live stream loaded ($directVideoId)")
            return
        }

        when (val result = youTubeRepository.detectActiveLiveStream(channel.channelId)) {
            is YouTubeResult.Success -> {
                val det = result.data
                cachedMedia[channel.channelId] = ResolvedStreamMedia(
                    hlsManifestUrl = det.hlsManifestUrl,
                    userAgent = det.hlsUserAgent,
                    dashManifestUrl = det.dashManifestUrl,
                    captionTrackUrl = det.captionTrackUrl
                )
                channelDao.updateLiveStatus(
                    channelId = channel.channelId,
                    checkedAt = System.currentTimeMillis(),
                    isLive = det.isLive,
                    videoId = det.videoId,
                    liveTitle = det.title,
                    liveChatId = det.liveChatSession?.continuation?.take(32),
                    viewers = det.concurrentViewers,
                    error = null
                )
                if (det.isLive && !det.videoId.isNullOrBlank()) {
                    embeddedPlayerManager.ensurePlayerLoaded(channel.channelId, det.videoId)
                } else {
                    embeddedPlayerManager.releaseChannel(channel.channelId)
                }
                updateChannelTelemetry(channel.channelId) { prev ->
                    prev.copy(
                        isLive = det.isLive,
                        activeVideoId = det.videoId,
                        activeLiveTitle = det.title,
                        concurrentViewers = det.concurrentViewers,
                        phase = if (det.isLive) ChannelMonitorPhase.MONITORING else ChannelMonitorPhase.OFFLINE,
                        statusNote = if (det.isLive) "1080p live mini-player active" else "Channel offline"
                    )
                }
                appendLog(
                    if (det.isLive) "OK" else "INFO",
                    "YOUTUBE",
                    if (det.isLive) "${channel.title} is LIVE (${det.concurrentViewers} viewers) • 1080p mini-player loaded"
                    else "${channel.title} is currently offline"
                )
            }

            is YouTubeResult.Error -> {
                val existingVid = channel.activeVideoId
                val playerPlaying = embeddedPlayerManager.playerStatuses.value[channel.channelId]?.isPlaying == true
                if (!existingVid.isNullOrBlank() && playerPlaying) {
                    appendLog("WARN", "YOUTUBE", "${channel.title}: Wi-Fi API check blocked, continuing via live player")
                    return
                }
                channelDao.updateLiveStatus(
                    channelId = channel.channelId,
                    checkedAt = System.currentTimeMillis(),
                    isLive = false,
                    videoId = existingVid,
                    liveTitle = channel.activeLiveTitle,
                    liveChatId = null,
                    viewers = 0L,
                    error = result.message
                )
                appendLog("ERR", "YOUTUBE", "${channel.title}: ${result.message}")
            }
        }
    }

    private fun reconcileChannelWorkers(channels: List<MonitoredChannelEntity>) {
        val activeCap = latestConfig.maxConcurrentChannels
        val desired = channels.filter { it.isMonitoringEnabled }.take(activeCap)
        val desiredIds = desired.map { it.channelId }.toSet()

        channelJobs.keys.filter { it !in desiredIds }.forEach { removedId ->
            channelJobs.remove(removedId)?.cancel()
            trackers.remove(removedId)
            cachedMedia.remove(removedId)
            embeddedPlayerManager.releaseChannel(removedId)
        }

        desired.forEach { channel ->
            if (!channelJobs.containsKey(channel.channelId)) {
                channelJobs[channel.channelId] = engineScope.launch {
                    runChannelMonitorLoop(channel)
                }
            }
        }
        refreshResourceSnapshot()
    }

    private data class ActiveVideoRecordingHandle(
        val stopFlag: AtomicBoolean,
        val usingScreenCapture: Boolean,
        val captureDeferred: Deferred<RecordedRawVideo?>?
    )

    private suspend fun startBackgroundVideoCapture(
        channelId: String,
        channelTitle: String,
        videoId: String,
        maxDurationSec: Int,
        stopFlag: AtomicBoolean
    ): ActiveVideoRecordingHandle {
        // Ensure the channel's 1080p embedded mini-player is loaded
        embeddedPlayerManager.ensurePlayerLoaded(channelId, videoId)

        // 1. If MediaProjection Screen + Audio Bridge is explicitly active, use it directly
        if (screenCaptureManager.isActive.value) {
            val outDir = File(context.cacheDir, "screen_rec_$channelId").apply { mkdirs() }
            val outFile = File(outDir, "screen_${System.currentTimeMillis()}.mp4")
            if (screenCaptureManager.startVideoRecording(outFile)) {
                appendLog("INFO", "VIDEO", "Recording live Video + Internal Audio for $channelTitle via Screen Bridge")
                return ActiveVideoRecordingHandle(
                    stopFlag = stopFlag,
                    usingScreenCapture = true,
                    captureDeferred = null
                )
            }
        }

        // 2. PRIMARY PIPELINE: Record directly from the channel's embedded 1080p live mini-player
        //    (pure internal YouTube MSE bitstream tap + offscreen <video>/WebAudio fallback — ZERO screen/UI capture)
        val deferred = engineScope.async(Dispatchers.IO) {
            appendLog("INFO", "PLAYER", "Recording live 1080p HD video + audio + captions from $channelTitle mini-player")
            val playerRecorded = embeddedPlayerManager.recordFromPlayer(
                channelId = channelId,
                videoId = videoId,
                maxDurationSec = maxDurationSec,
                stopFlag = stopFlag,
                onProgress = { bytes, captionCount, mode ->
                    val mb = bytes / (1024f * 1024f)
                    val capStr = if (captionCount > 0) " • $captionCount caps" else ""
                    updateChannelTelemetry(channelId) { prev ->
                        prev.copy(
                            recordedVideoMb = mb,
                            statusNote = String.format("REC %s (%.1f MB%s)", mode, mb, capStr)
                        )
                    }
                    _state.update { cur ->
                        val active = cur.activeRecording
                        if (active != null && active.channelId == channelId) {
                            cur.copy(activeRecording = active.copy(recordedMb = mb))
                        } else {
                            cur
                        }
                    }
                }
            )
            if (playerRecorded != null) {
                return@async playerRecorded
            }

            // 3. Fallback: Direct HLS / DASH stream capture if mini-player couldn't yield frames
            if (!stopFlag.get()) {
                val freshMedia = youTubeRepository.resolveFreshLiveStreamMedia(videoId)
                val existingMedia = cachedMedia[channelId]
                val hlsUrl = freshMedia.hlsManifestUrl ?: existingMedia?.hlsManifestUrl
                val hlsUa = if (!freshMedia.hlsManifestUrl.isNullOrBlank()) {
                    freshMedia.userAgent
                } else {
                    existingMedia?.userAgent ?: YouTubeRepository.DESKTOP_USER_AGENT
                }
                val dashUrl = freshMedia.dashManifestUrl ?: existingMedia?.dashManifestUrl
                val dashUa = if (!freshMedia.dashManifestUrl.isNullOrBlank()) {
                    freshMedia.dashUserAgent
                } else {
                    existingMedia?.dashUserAgent ?: YouTubeRepository.DESKTOP_USER_AGENT
                }
                cachedMedia[channelId] = ResolvedStreamMedia(
                    hlsManifestUrl = hlsUrl,
                    userAgent = hlsUa,
                    dashManifestUrl = dashUrl,
                    dashUserAgent = dashUa,
                    captionTrackUrl = freshMedia.captionTrackUrl ?: existingMedia?.captionTrackUrl,
                    clientLabel = freshMedia.clientLabel,
                    diagnosticNote = freshMedia.diagnosticNote
                )

                if (!hlsUrl.isNullOrBlank()) {
                    val hlsRecorded = liveVideoRecorder.recordHlsStream(
                        hlsManifestUrl = hlsUrl,
                        userAgent = hlsUa,
                        channelId = channelId,
                        stopFlag = stopFlag,
                        maxDurationSec = maxDurationSec,
                        onSegmentRecorded = { segs, bytes, hasAudio ->
                            val mb = bytes / (1024f * 1024f)
                            val avText = if (hasAudio) "VIDEO+AUDIO" else "VIDEO"
                            updateChannelTelemetry(channelId) { prev ->
                                prev.copy(
                                    recordedVideoMb = mb,
                                    statusNote = String.format("REC %s • %d segs (%.1f MB)", avText, segs, mb)
                                )
                            }
                        }
                    )
                    if (hlsRecorded != null) return@async hlsRecorded
                }

                if (!dashUrl.isNullOrBlank() && !stopFlag.get()) {
                    val dashRecorded = liveVideoRecorder.recordDashStream(
                        dashManifestUrl = dashUrl,
                        userAgent = dashUa,
                        channelId = channelId,
                        stopFlag = stopFlag,
                        maxDurationSec = maxDurationSec,
                        onSegmentRecorded = { segs, bytes, hasAudio ->
                            val mb = bytes / (1024f * 1024f)
                            val avText = if (hasAudio) "DASH V+A" else "DASH VID"
                            updateChannelTelemetry(channelId) { prev ->
                                prev.copy(
                                    recordedVideoMb = mb,
                                    statusNote = String.format("REC %s • %d segs (%.1f MB)", avText, segs, mb)
                                )
                            }
                        }
                    )
                    if (dashRecorded != null) return@async dashRecorded
                }
            }

            appendLog("WARN", "VIDEO", "Could not capture video frames for $channelTitle — keep mini-player playing and retry")
            null
        }

        return ActiveVideoRecordingHandle(
            stopFlag = stopFlag,
            usingScreenCapture = false,
            captureDeferred = deferred
        )
    }

    private suspend fun finishBackgroundVideoCapture(handle: ActiveVideoRecordingHandle?): RecordedRawVideo? {
        if (handle == null) return null
        handle.stopFlag.set(true)
        if (handle.usingScreenCapture) {
            val out = screenCaptureManager.stopVideoRecording() ?: return null
            return RecordedRawVideo(
                videoFile = out.videoFile,
                audioFile = out.audioFile,
                segmentCount = 1,
                totalBytes = out.videoFile.length() + (out.audioFile?.length() ?: 0L),
                hasAudioTrack = out.audioFile != null,
                sourceLabel = if (out.audioFile != null) "Screen Video + Audio" else "Screen Bridge MP4"
            )
        }
        return runCatching { handle.captureDeferred?.await() }.getOrNull()
    }

    private suspend fun runChannelMonitorLoop(channel: MonitoredChannelEntity) {
        val tracker = trackers.getOrPut(channel.channelId) { ChatVelocityTracker() }
        val directVideoId = YouTubeRepository.extractDirectVideoId(channel.channelId)
        val workerMutex = Mutex()
        val lastWebViewChatPulseMs = AtomicLong(0L)

        var activeVideoId: String? = directVideoId ?: channel.activeVideoId
        var activeLiveTitle: String? = channel.activeLiveTitle ?: channel.title
        var liveChatSession: LiveChatSession? = null
        var isFirstOkHttpBatch = true
        var lastLiveStatusCheckMs = 0L
        var lastPlayerEnsureMs = 0L
        var activeVideoHandle: ActiveVideoRecordingHandle? = null
        var receivedFirstWebViewPulse = false

        suspend fun processVelocityBatch(
            messageCount: Int,
            nowMs: Long,
            isInitialSeed: Boolean,
            sourceLabel: String
        ) {
            workerMutex.withLock {
                if (manualCuts.containsKey(channel.channelId)) return@withLock
                val vid = activeVideoId ?: directVideoId ?: return@withLock
                val syntheticEntries = if (messageCount > 0) {
                    List(messageCount) { idx ->
                        LiveChatEntry(
                            id = "p_${nowMs}_$idx",
                            author = "",
                            text = "",
                            receivedAtMillis = nowMs
                        )
                    }
                } else {
                    emptyList()
                }

                val action = tracker.onChatBatch(
                    messages = syntheticEntries,
                    nowMs = nowMs,
                    config = latestConfig,
                    isInitialSeedBatch = isInitialSeed
                )

                channelDao.updateVelocity(
                    channelId = channel.channelId,
                    baseline = tracker.baselineVelocityPerMin,
                    current = tracker.currentVelocityPerMin
                )

                val elapsed = tracker.getRecordingElapsedSeconds(nowMs)
                updateChannelTelemetry(channel.channelId) { prev ->
                    prev.copy(
                        isLive = true,
                        activeVideoId = vid,
                        activeLiveTitle = activeLiveTitle ?: prev.activeLiveTitle,
                        phase = tracker.phase,
                        baselineVelocityPerMin = tracker.baselineVelocityPerMin,
                        currentVelocityPerMin = tracker.currentVelocityPerMin,
                        spikeMultiplier = tracker.getCurrentMultiplier(),
                        recordingElapsedSec = elapsed,
                        recordingTargetSec = latestConfig.maxClipSeconds,
                        velocityHistory = tracker.getHistory(),
                        statusNote = when (tracker.phase) {
                            ChannelMonitorPhase.RECORDING -> String.format(
                                "REC 1080p STREAM %02ds / %02ds (%.1f MB)",
                                elapsed,
                                latestConfig.maxClipSeconds,
                                prev.recordedVideoMb
                            )
                            ChannelMonitorPhase.COOLDOWN -> "Clip saved • Re-arming for next spike…"
                            else -> String.format(
                                "%s • %.1fx / %.1fx trigger",
                                sourceLabel,
                                tracker.getCurrentMultiplier(),
                                latestConfig.spikeMultiplier
                            )
                        }
                    )
                }

                if (tracker.phase == ChannelMonitorPhase.RECORDING) {
                    val mb = _state.value.channelTelemetry[channel.channelId]?.recordedVideoMb ?: 0f
                    _state.update {
                        it.copy(
                            activeRecording = ActiveRecordingBannerState(
                                channelId = channel.channelId,
                                channelTitle = channel.title,
                                elapsedSeconds = elapsed,
                                targetSeconds = latestConfig.maxClipSeconds,
                                recordedMb = mb,
                                stageLabel = if (elapsed < latestConfig.minClipSeconds) {
                                    "Spike! Recording min ${latestConfig.minClipSeconds}s pure 1080p stream…"
                                } else {
                                    "Recording until chat subsides (${elapsed}s / ${latestConfig.maxClipSeconds}s)…"
                                }
                            )
                        )
                    }
                }

                when (action) {
                    is VelocityAction.StartRecording -> {
                        appendLog(
                            "SPIKE",
                            "TRIGGER",
                            String.format(
                                "%s hit %.1fx via %s -> recording 30–%ds 1080p stream",
                                channel.title,
                                action.spikeMultiplier,
                                sourceLabel,
                                latestConfig.maxClipSeconds
                            )
                        )
                        activeVideoHandle?.stopFlag?.set(true)
                        activeVideoHandle = startBackgroundVideoCapture(
                            channelId = channel.channelId,
                            channelTitle = channel.title,
                            videoId = vid,
                            maxDurationSec = latestConfig.maxClipSeconds,
                            stopFlag = AtomicBoolean(false)
                        )
                    }

                    is VelocityAction.StopAndProcessRecording -> {
                        val handleToFinish = activeVideoHandle
                        activeVideoHandle = null
                        val recorded = finishBackgroundVideoCapture(handleToFinish)
                        _state.update {
                            if (it.activeRecording?.channelId == channel.channelId) {
                                it.copy(activeRecording = null)
                            } else {
                                it
                            }
                        }
                        updateChannelTelemetry(channel.channelId) { it.copy(recordedVideoMb = 0f) }

                        if (recorded == null) {
                            pipeline.reportCaptureError(
                                channelTitle = channel.title,
                                reason = "1080p player buffered during clip — staying armed for next spike."
                            )
                        } else {
                            appendLog(
                                "OK",
                                "VIDEO",
                                String.format(
                                    "%s captured %ds (%s • %.2f MB) -> editing & saving 9:16 MP4",
                                    channel.title,
                                    action.durationSeconds,
                                    recorded.sourceLabel,
                                    recorded.totalBytes / (1024f * 1024f)
                                )
                            )
                            val spokenCaptions = resolveSpokenCaptionsForClip(
                                recorded = recorded,
                                channelId = channel.channelId,
                                videoId = vid,
                                durationSeconds = action.durationSeconds
                            )
                            pipeline.enqueueRecordedVideo(
                                channelId = channel.channelId,
                                channelTitle = channel.title,
                                videoId = vid,
                                streamTitle = activeLiveTitle ?: channel.title,
                                durationSeconds = action.durationSeconds,
                                baselineVelocity = action.baselineVelocity,
                                peakVelocity = action.peakVelocity,
                                spikeMultiplier = action.spikeMultiplier,
                                recorded = recorded,
                                spokenCaptions = spokenCaptions,
                                config = latestConfig
                            )
                        }
                    }

                    VelocityAction.None -> Unit
                }
            }
        }

        // PRIMARY CHAT TRIGGER SOURCE: In-WebView Live Chat Velocity Tap (works even when Wi-Fi blocks YouTube API!)
        val webViewChatJob = engineScope.launch {
            embeddedPlayerManager.chatPulses.collect { pulse ->
                if (pulse.channelId != channel.channelId) return@collect
                if (!_state.value.isRunning) return@collect
                lastWebViewChatPulseMs.set(pulse.timestampMs)
                if (!receivedFirstWebViewPulse) {
                    receivedFirstWebViewPulse = true
                    appendLog("OK", "CHAT", "${channel.title}: In-player live chat radar locked (Wi-Fi safe)")
                }
                processVelocityBatch(
                    messageCount = pulse.messageCount,
                    nowMs = pulse.timestampMs,
                    isInitialSeed = pulse.isInitialSeed,
                    sourceLabel = "Player Chat"
                )
            }
        }

        autoCutStoppers[channel.channelId] = {
            engineScope.launch {
                workerMutex.withLock {
                    val handleToFinish = activeVideoHandle ?: return@withLock
                    if (tracker.phase != ChannelMonitorPhase.RECORDING) return@withLock
                    val nowMs = System.currentTimeMillis()
                    val vid = activeVideoId ?: directVideoId ?: return@withLock
                    val elapsedSec = tracker.getRecordingElapsedSeconds(nowMs).coerceAtLeast(3)
                    val baseVel = tracker.baselineVelocityPerMin.coerceAtLeast(2f)
                    val curVel = tracker.currentVelocityPerMin.coerceAtLeast(baseVel)
                    val mult = tracker.getCurrentMultiplier().coerceAtLeast(1.0f)
                    activeVideoHandle = null
                    tracker.reset()

                    val recorded = finishBackgroundVideoCapture(handleToFinish)
                    _state.update {
                        if (it.activeRecording?.channelId == channel.channelId) it.copy(activeRecording = null) else it
                    }
                    updateChannelTelemetry(channel.channelId) {
                        it.copy(
                            phase = ChannelMonitorPhase.MONITORING,
                            isManualCut = false,
                            recordingElapsedSec = 0,
                            recordedVideoMb = 0f,
                            statusNote = if (recorded != null) "Manual stop • Encoding ${elapsedSec}s clip…" else "Recording stopped"
                        )
                    }

                    if (recorded != null) {
                        appendLog("OK", "MANUAL", "Cut active spike clip at ${elapsedSec}s for ${channel.title} -> 9:16 render")
                        val spokenCaptions = resolveSpokenCaptionsForClip(
                            recorded = recorded,
                            channelId = channel.channelId,
                            videoId = vid,
                            durationSeconds = elapsedSec
                        )
                        pipeline.enqueueRecordedVideo(
                            channelId = channel.channelId,
                            channelTitle = channel.title,
                            videoId = vid,
                            streamTitle = activeLiveTitle ?: channel.title,
                            durationSeconds = elapsedSec,
                            baselineVelocity = baseVel,
                            peakVelocity = curVel,
                            spikeMultiplier = mult,
                            recorded = recorded,
                            spokenCaptions = spokenCaptions,
                            config = latestConfig
                        )
                    }
                }
            }
        }

        // LIVE 1-SECOND RECORDING TICKER: Keeps 00s->45s timer, progress bar, and MB smooth even between chat polls
        val recordingTickerJob = engineScope.launch {
            while (isActive && _state.value.isRunning) {
                delay(1000L)
                if (tracker.phase == ChannelMonitorPhase.RECORDING && !manualCuts.containsKey(channel.channelId)) {
                    val nowMs = System.currentTimeMillis()
                    val elapsed = tracker.getRecordingElapsedSeconds(nowMs).coerceAtLeast(1)
                    val target = latestConfig.maxClipSeconds
                    val playerBytes = embeddedPlayerManager.playerStatuses.value[channel.channelId]?.recordedBytes ?: 0L
                    val prevMb = _state.value.channelTelemetry[channel.channelId]?.recordedVideoMb ?: 0f
                    val liveMb = maxOf(prevMb, playerBytes / (1024f * 1024f))

                    updateChannelTelemetry(channel.channelId) { prev ->
                        if (prev.phase != ChannelMonitorPhase.RECORDING) prev
                        else prev.copy(
                            recordingElapsedSec = elapsed.coerceAtMost(target),
                            recordingTargetSec = target,
                            recordedVideoMb = liveMb,
                            statusNote = String.format(
                                "REC 1080p STREAM %02ds / %02ds (%.1f MB)",
                                elapsed.coerceAtMost(target),
                                target,
                                liveMb
                            )
                        )
                    }
                    _state.update { cur ->
                        cur.copy(
                            activeRecording = ActiveRecordingBannerState(
                                channelId = channel.channelId,
                                channelTitle = channel.title,
                                elapsedSeconds = elapsed.coerceAtMost(target),
                                targetSeconds = target,
                                recordedMb = liveMb,
                                stageLabel = if (elapsed < latestConfig.minClipSeconds) {
                                    String.format(
                                        "Spike! Recording pure 1080p stream • %.1f MB",
                                        liveMb
                                    )
                                } else {
                                    String.format(
                                        "Capturing peak moment (%02ds / %02ds • %.1f MB)…",
                                        elapsed.coerceAtMost(target),
                                        target,
                                        liveMb
                                    )
                                }
                            )
                        )
                    }
                    if (elapsed >= target) {
                        processVelocityBatch(
                            messageCount = 0,
                            nowMs = nowMs,
                            isInitialSeed = false,
                            sourceLabel = "Player Chat"
                        )
                    }
                }
            }
        }

        appendLog(
            "INFO",
            "WORKER",
            if (directVideoId != null) "Direct live video monitor attached for ${channel.title} ($directVideoId)"
            else "Video monitor attached for ${channel.title} (${channel.handle})"
        )

        try {
            // If this is a Direct Live Video Link, boot its 1080p player immediately without waiting on OkHttp!
            if (directVideoId != null) {
                activeVideoId = directVideoId
                embeddedPlayerManager.ensurePlayerLoaded(channel.channelId, directVideoId)
                lastPlayerEnsureMs = System.currentTimeMillis()
                channelDao.updateLiveStatus(
                    channelId = channel.channelId,
                    checkedAt = lastPlayerEnsureMs,
                    isLive = true,
                    videoId = directVideoId,
                    liveTitle = channel.activeLiveTitle ?: "Direct 1080p Live Stream",
                    liveChatId = "webview_chat",
                    viewers = channel.concurrentViewers,
                    error = null
                )
                updateChannelTelemetry(channel.channelId) { prev ->
                    prev.copy(
                        isLive = true,
                        activeVideoId = directVideoId,
                        activeLiveTitle = channel.activeLiveTitle ?: prev.activeLiveTitle,
                        phase = ChannelMonitorPhase.MONITORING,
                        statusNote = "1080p live player active • Connecting in-player chat…"
                    )
                }
            }

            while (engineScope.isActive && _state.value.isRunning) {
                val now = System.currentTimeMillis()
                _state.update { it.copy(lastPollAtMs = now) }

                val webViewChatFresh = (now - lastWebViewChatPulseMs.get()) < 18_000L

                // 1) DIRECT LIVE VIDEO LINK LOOP (100% immune to Wi-Fi OkHttp API blocks!)
                if (directVideoId != null) {
                    if (now - lastPlayerEnsureMs > 60_000L) {
                        embeddedPlayerManager.ensurePlayerLoaded(channel.channelId, directVideoId)
                        lastPlayerEnsureMs = now
                    }

                    // If WebView in-player chat radar is streaming pulses, we don't need OkHttp at all!
                    if (webViewChatFresh) {
                        // Keep recording timer smooth if recording
                        if (tracker.phase == ChannelMonitorPhase.RECORDING) {
                            val elapsed = tracker.getRecordingElapsedSeconds(now)
                            if (elapsed >= latestConfig.maxClipSeconds) {
                                processVelocityBatch(0, now, isInitialSeed = false, sourceLabel = "Player Chat")
                            }
                        }
                        delay(2000L)
                        continue
                    }

                    // Backup: try optional OkHttp chat session if WebView chat hasn't pulsed yet
                    if (liveChatSession == null && (now - lastLiveStatusCheckMs > 45_000L)) {
                        lastLiveStatusCheckMs = now
                        val det = youTubeRepository.detectDirectVideoStream(directVideoId)
                        if (!det.title.isNullOrBlank()) {
                            activeLiveTitle = det.title
                        }
                        cachedMedia[channel.channelId] = ResolvedStreamMedia(
                            hlsManifestUrl = det.hlsManifestUrl,
                            userAgent = det.hlsUserAgent,
                            dashManifestUrl = det.dashManifestUrl,
                            captionTrackUrl = det.captionTrackUrl
                        )
                        if (det.liveChatSession != null) {
                            liveChatSession = det.liveChatSession
                            isFirstOkHttpBatch = true
                        }
                    }

                    val session = liveChatSession
                    if (session != null && !webViewChatFresh) {
                        when (val chatResult = youTubeRepository.pollLiveChat(session)) {
                            is YouTubeResult.Success -> {
                                val batch = chatResult.data
                                liveChatSession = batch.nextSession
                                val pollNow = System.currentTimeMillis()
                                // Only apply OkHttp batch if WebView didn't just pulse
                                if ((pollNow - lastWebViewChatPulseMs.get()) >= 15_000L) {
                                    processVelocityBatch(
                                        messageCount = batch.messages.size,
                                        nowMs = pollNow,
                                        isInitialSeed = isFirstOkHttpBatch,
                                        sourceLabel = "Live Chat"
                                    )
                                    isFirstOkHttpBatch = false
                                }
                                val sleepMs = if (tracker.phase == ChannelMonitorPhase.RECORDING) {
                                    batch.pollingIntervalMillis.coerceIn(2500L, 4000L)
                                } else {
                                    batch.pollingIntervalMillis.coerceIn(3000L, 7000L)
                                }
                                delay(sleepMs)
                                continue
                            }

                            is YouTubeResult.Error -> {
                                if (!chatResult.isRateLimited) liveChatSession = null
                                // Do NOT mark direct live video as ERROR — WebView player keeps running!
                                delay(8000L)
                                continue
                            }
                        }
                    } else {
                        // Waiting for WebView in-player chat tap to emit its first pulse
                        delay(3000L)
                        continue
                    }
                }

                // 2) CHANNEL (@handle / UC...) LOOP — uses In-WebView chat as primary once live!
                if ((activeVideoId == null || (liveChatSession == null && !webViewChatFresh)) ||
                    (now - lastLiveStatusCheckMs > 180_000L && tracker.phase != ChannelMonitorPhase.RECORDING && !webViewChatFresh)
                ) {
                    if (activeVideoId == null) {
                        updateChannelTelemetry(channel.channelId) {
                            it.copy(
                                phase = if (it.isLive) it.phase else ChannelMonitorPhase.CONNECTING,
                                statusNote = "Checking live video stream…"
                            )
                        }
                    }
                    when (val detResult = youTubeRepository.detectActiveLiveStream(channel.channelId)) {
                        is YouTubeResult.Success -> {
                            lastLiveStatusCheckMs = now
                            val det = detResult.data
                            cachedMedia[channel.channelId] = ResolvedStreamMedia(
                                hlsManifestUrl = det.hlsManifestUrl,
                                userAgent = det.hlsUserAgent,
                                dashManifestUrl = det.dashManifestUrl,
                                captionTrackUrl = det.captionTrackUrl
                            )

                            channelDao.updateLiveStatus(
                                channelId = channel.channelId,
                                checkedAt = now,
                                isLive = det.isLive,
                                videoId = det.videoId,
                                liveTitle = det.title,
                                liveChatId = det.liveChatSession?.continuation?.take(32),
                                viewers = det.concurrentViewers,
                                error = null
                            )

                            if (det.isLive && !det.videoId.isNullOrBlank()) {
                                if (activeVideoId != det.videoId) {
                                    tracker.reset()
                                    isFirstOkHttpBatch = true
                                    appendLog("OK", "LIVE", "${channel.title} is LIVE: ${det.title ?: det.videoId}")
                                }
                                activeVideoId = det.videoId
                                activeLiveTitle = det.title ?: channel.title
                                liveChatSession = det.liveChatSession
                                embeddedPlayerManager.ensurePlayerLoaded(channel.channelId, det.videoId)
                                updateChannelTelemetry(channel.channelId) {
                                    it.copy(
                                        isLive = true,
                                        activeVideoId = det.videoId,
                                        activeLiveTitle = det.title,
                                        concurrentViewers = det.concurrentViewers,
                                        phase = tracker.phase,
                                        statusNote = "1080p mini-player armed • Watching chat spike"
                                    )
                                }
                            } else {
                                activeVideoId = null
                                activeLiveTitle = null
                                liveChatSession = null
                                isFirstOkHttpBatch = true
                                tracker.reset()
                                embeddedPlayerManager.releaseChannel(channel.channelId)
                                updateChannelTelemetry(channel.channelId) {
                                    it.copy(
                                        isLive = false,
                                        activeVideoId = null,
                                        activeLiveTitle = null,
                                        concurrentViewers = 0L,
                                        phase = ChannelMonitorPhase.OFFLINE,
                                        statusNote = "Offline • re-checking in 40s"
                                    )
                                }
                                delay(40_000L)
                                continue
                            }
                        }

                        is YouTubeResult.Error -> {
                            val playerPlaying = embeddedPlayerManager.playerStatuses.value[channel.channelId]?.isPlaying == true
                            if (!activeVideoId.isNullOrBlank() && playerPlaying) {
                                // Wi-Fi blocked OkHttp, but our embedded 1080p player is LIVE — keep going!
                                lastLiveStatusCheckMs = now
                                embeddedPlayerManager.ensurePlayerLoaded(channel.channelId, activeVideoId!!)
                                delay(4000L)
                                continue
                            }
                            channelDao.updateLiveStatus(
                                channelId = channel.channelId,
                                checkedAt = now,
                                isLive = false,
                                videoId = activeVideoId,
                                liveTitle = activeLiveTitle,
                                liveChatId = null,
                                viewers = 0L,
                                error = detResult.message
                            )
                            updateChannelTelemetry(channel.channelId) {
                                it.copy(
                                    phase = ChannelMonitorPhase.ERROR,
                                    statusNote = "Wi-Fi blocked channel check — paste Live Video link instead"
                                )
                            }
                            appendLog("ERR", "YOUTUBE", "${channel.title}: ${detResult.message}")
                            delay(if (detResult.isRateLimited) 60_000L else 25_000L)
                            continue
                        }
                    }
                }

                // If WebView in-player chat is already delivering pulses, skip OkHttp chat polling!
                if ((System.currentTimeMillis() - lastWebViewChatPulseMs.get()) < 18_000L) {
                    if (tracker.phase == ChannelMonitorPhase.RECORDING) {
                        val elapsed = tracker.getRecordingElapsedSeconds(System.currentTimeMillis())
                        if (elapsed >= latestConfig.maxClipSeconds) {
                            processVelocityBatch(0, System.currentTimeMillis(), isInitialSeed = false, sourceLabel = "Player Chat")
                        }
                    }
                    delay(2000L)
                    continue
                }

                val session = liveChatSession
                if (session == null) {
                    delay(4000L)
                    continue
                }

                when (val chatResult = youTubeRepository.pollLiveChat(session)) {
                    is YouTubeResult.Success -> {
                        val batch = chatResult.data
                        liveChatSession = batch.nextSession
                        val pollNow = System.currentTimeMillis()

                        if ((pollNow - lastWebViewChatPulseMs.get()) >= 15_000L) {
                            processVelocityBatch(
                                messageCount = batch.messages.size,
                                nowMs = pollNow,
                                isInitialSeed = isFirstOkHttpBatch,
                                sourceLabel = "Live Chat"
                            )
                            isFirstOkHttpBatch = false
                        }

                        val sleepMs = if (tracker.phase == ChannelMonitorPhase.RECORDING) {
                            batch.pollingIntervalMillis.coerceIn(2500L, 4000L)
                        } else {
                            batch.pollingIntervalMillis.coerceIn(3000L, 8000L)
                        }
                        delay(sleepMs)
                    }

                    is YouTubeResult.Error -> {
                        appendLog("WARN", "CHAT", "${channel.title}: OkHttp chat paused (${chatResult.message}); relying on in-player chat")
                        if (!chatResult.isRateLimited) {
                            liveChatSession = null
                        }
                        delay(if (chatResult.isRateLimited) 45_000L else 12_000L)
                    }
                }
            }
        } finally {
            autoCutStoppers.remove(channel.channelId)
            webViewChatJob.cancel()
            recordingTickerJob.cancel()
            activeVideoHandle?.stopFlag?.set(true)
            if (activeVideoHandle?.usingScreenCapture == true) {
                val out = screenCaptureManager.stopVideoRecording()
                out?.videoFile?.delete()
                out?.audioFile?.delete()
            }
            _state.update {
                if (it.activeRecording?.channelId == channel.channelId) {
                    it.copy(activeRecording = null)
                } else {
                    it
                }
            }
        }
    }

    fun appendLog(level: String, tag: String, message: String) {
        val entry = EngineLogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        val snapshot = synchronized(logLock) {
            if (logBuffer.size >= 120) logBuffer.removeFirst()
            logBuffer.addLast(entry)
            logBuffer.toList().asReversed()
        }
        _state.update { it.copy(logs = snapshot) }
    }

    private fun updateChannelTelemetry(
        channelId: String,
        transform: (ChannelLiveTelemetry) -> ChannelLiveTelemetry
    ) {
        _state.update { current ->
            val existing = current.channelTelemetry[channelId] ?: ChannelLiveTelemetry(channelId)
            current.copy(
                channelTelemetry = current.channelTelemetry + (channelId to transform(existing))
            )
        }
    }

    private fun refreshResourceSnapshot() {
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt()
        val maxMb = (rt.maxMemory() / (1024L * 1024L)).toInt().coerceAtLeast(128)
        val rate = ThroughputCalculator.clipsPerFiveMinutes(
            completedClipTimestampsMs = pipeline.telemetry.value.completedTimestampsMs,
            monitoringStartedAtMs = _state.value.startedAtMs,
            nowMs = System.currentTimeMillis()
        )
        _state.update {
            it.copy(
                resourceSnapshot = ResourceSnapshot(
                    usedHeapMb = usedMb,
                    maxHeapMb = maxMb,
                    activeWorkers = channelJobs.size,
                    clipsPerFiveMin = rate
                )
            )
        }
    }
}
