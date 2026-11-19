package com.example.engine

import com.example.data.local.ChannelDao
import com.example.data.local.ClipDao
import com.example.data.local.ClipStatus
import com.example.data.local.RecordedClipEntity
import com.example.data.prefs.PipelineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class PipelineQueueTelemetry(
    val queuedCount: Int = 0,
    val activeClipId: Long? = null,
    val activeChannelTitle: String? = null,
    val activeProgressPercent: Int = 0,
    val activeStageLabel: String = "",
    val lastSavedClipId: Long? = null,
    val lastSavedChannelTitle: String? = null,
    val lastSavedFilePath: String? = null,
    val lastErrorMessage: String? = null,
    val completedTimestampsMs: List<Long> = emptyList()
)

/**
 * Automated 9:16 video + audio + captions processing pipeline with strict bounded concurrency
 * (Semaphore(1)) and real-time stage/percentage progress reporting for the UI progress bar.
 */
class ClipProcessingPipeline(
    private val encoder: VerticalClipEncoder,
    private val clipDao: ClipDao,
    private val channelDao: ChannelDao,
    private val onLog: (level: String, tag: String, message: String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val encodeSemaphore = Semaphore(1)

    private val _telemetry = MutableStateFlow(PipelineQueueTelemetry())
    val telemetry: StateFlow<PipelineQueueTelemetry> = _telemetry.asStateFlow()

    fun enqueueRecordedVideo(
        channelId: String,
        channelTitle: String,
        videoId: String,
        streamTitle: String,
        durationSeconds: Int,
        baselineVelocity: Float,
        peakVelocity: Float,
        spikeMultiplier: Float,
        recorded: RecordedRawVideo,
        spokenCaptions: List<CaptionSegment>,
        config: PipelineConfig
    ) {
        scope.launch {
            val captionsSummary = AutoCaptionGenerator.summarizeCaptions(spokenCaptions)
            val profile = config.videoProfile

            val clipId = clipDao.insertClip(
                RecordedClipEntity(
                    channelId = channelId,
                    channelTitle = channelTitle,
                    videoId = videoId,
                    streamTitle = streamTitle,
                    durationSeconds = durationSeconds,
                    width = profile.width,
                    height = profile.height,
                    aspectRatio = "9:16",
                    overlayText = config.overlayText.ifBlank { "Wait for it" },
                    captionsSummary = captionsSummary,
                    captionCount = spokenCaptions.size,
                    peakVelocityPerMin = peakVelocity,
                    baselineVelocityPerMin = baselineVelocity,
                    spikeMultiplier = spikeMultiplier,
                    status = ClipStatus.QUEUED,
                    progressPercent = 1
                )
            )

            _telemetry.value = _telemetry.value.copy(
                queuedCount = _telemetry.value.queuedCount + 1,
                lastErrorMessage = null
            )
            onLog(
                "INFO",
                "PIPELINE",
                String.format(
                    "Queued %ds clip #%d (%s • %.2f MB • %d captions) for %s -> 9:16",
                    durationSeconds,
                    clipId,
                    recorded.sourceLabel,
                    recorded.totalBytes / (1024f * 1024f),
                    spokenCaptions.size,
                    channelTitle
                )
            )

            processJob(
                ClipEncodeRequest(
                    clipId = clipId,
                    channelTitle = channelTitle,
                    streamTitle = streamTitle,
                    videoId = videoId,
                    durationSeconds = durationSeconds,
                    spikeMultiplier = spikeMultiplier,
                    peakVelocityPerMin = peakVelocity,
                    rawVideoFile = recorded.videoFile,
                    videoSegmentFiles = recorded.videoSegmentFiles,
                    rawAudioFile = recorded.audioFile,
                    audioSegmentFiles = recorded.audioSegmentFiles,
                    captions = spokenCaptions,
                    config = config
                ),
                channelId = channelId
            )
        }
    }

    fun reportCaptureError(channelTitle: String, reason: String) {
        _telemetry.value = _telemetry.value.copy(
            lastErrorMessage = "$channelTitle: $reason"
        )
        onLog("ERR", "VIDEO", "$channelTitle: $reason")
    }

    fun dismissLastStatusBanner() {
        _telemetry.value = _telemetry.value.copy(
            lastSavedClipId = null,
            lastSavedChannelTitle = null,
            lastSavedFilePath = null,
            lastErrorMessage = null
        )
    }

    private suspend fun processJob(request: ClipEncodeRequest, channelId: String) {
        encodeSemaphore.withPermit {
            _telemetry.value = _telemetry.value.copy(
                activeClipId = request.clipId,
                activeChannelTitle = request.channelTitle,
                activeProgressPercent = 2,
                activeStageLabel = "Starting 9:16 video + audio editor…",
                lastErrorMessage = null
            )
            clipDao.updateProgress(request.clipId, ClipStatus.ENCODING, 2)

            try {
                val result = encoder.encodeVerticalClip(request) { pct, stage ->
                    clipDao.updateProgress(request.clipId, ClipStatus.ENCODING, pct)
                    _telemetry.value = _telemetry.value.copy(
                        activeProgressPercent = pct,
                        activeStageLabel = stage
                    )
                }

                clipDao.markSaved(
                    id = request.clipId,
                    filePath = result.filePath,
                    contentUri = result.contentUri,
                    thumbnailPath = result.thumbnailPath,
                    fileSizeBytes = result.fileSizeBytes,
                    processingTimeMs = result.processingTimeMs
                )
                channelDao.incrementClipCount(channelId)

                val now = System.currentTimeMillis()
                val updatedHistory = (_telemetry.value.completedTimestampsMs + now)
                    .filter { now - it <= 15 * 60_000L }

                _telemetry.value = _telemetry.value.copy(
                    queuedCount = (_telemetry.value.queuedCount - 1).coerceAtLeast(0),
                    activeClipId = null,
                    activeChannelTitle = null,
                    activeProgressPercent = 0,
                    activeStageLabel = "",
                    lastSavedClipId = request.clipId,
                    lastSavedChannelTitle = request.channelTitle,
                    lastSavedFilePath = result.filePath,
                    lastErrorMessage = null,
                    completedTimestampsMs = updatedHistory
                )

                val sizeMb = result.fileSizeBytes / (1024f * 1024f)
                val avBadge = if (result.hasAudio) "Video+Audio" else "Video"
                onLog(
                    "OK",
                    "EXPORT",
                    String.format(
                        "Saved 9:16 %s clip #%d (%.2f MB • %d captions) to %s in %.1fs",
                        avBadge,
                        request.clipId,
                        sizeMb,
                        request.captions.size,
                        result.filePath,
                        result.processingTimeMs / 1000f
                    )
                )
            } catch (e: Exception) {
                val errMsg = e.message ?: "Hardware video encoder error"
                clipDao.updateProgress(
                    id = request.clipId,
                    status = ClipStatus.FAILED,
                    progress = 0,
                    error = errMsg
                )
                _telemetry.value = _telemetry.value.copy(
                    queuedCount = (_telemetry.value.queuedCount - 1).coerceAtLeast(0),
                    activeClipId = null,
                    activeChannelTitle = null,
                    activeProgressPercent = 0,
                    activeStageLabel = "",
                    lastErrorMessage = "${request.channelTitle}: $errMsg"
                )
                onLog("ERR", "PIPELINE", "Clip #${request.clipId} failed: $errMsg")
            } finally {
                val parentDirs = HashSet<java.io.File>()
                request.rawVideoFile?.let { f ->
                    f.parentFile?.let { parentDirs.add(it) }
                    runCatching { f.delete() }
                }
                request.rawAudioFile?.let { f ->
                    f.parentFile?.let { parentDirs.add(it) }
                    runCatching { f.delete() }
                }
                request.videoSegmentFiles.forEach { f ->
                    f.parentFile?.let { parentDirs.add(it) }
                    runCatching { f.delete() }
                }
                request.audioSegmentFiles.forEach { f ->
                    f.parentFile?.let { parentDirs.add(it) }
                    runCatching { f.delete() }
                }
                parentDirs.forEach { dir ->
                    if (dir.name.startsWith("player_rec_") ||
                        dir.name.startsWith("hls_rec_") ||
                        dir.name.startsWith("dash_rec_") ||
                        dir.name.startsWith("screen_rec_")
                    ) {
                        runCatching { dir.deleteRecursively() }
                    }
                }
            }
        }
    }
}
