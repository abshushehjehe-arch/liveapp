package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.prefs.CaptionStyle
import com.example.data.prefs.PipelineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ClipEncodeRequest(
    val clipId: Long,
    val channelTitle: String,
    val streamTitle: String,
    val videoId: String,
    val durationSeconds: Int,
    val spikeMultiplier: Float,
    val peakVelocityPerMin: Float,
    val rawVideoFile: File? = null,
    val videoSegmentFiles: List<File> = emptyList(),
    val rawAudioFile: File? = null,
    val audioSegmentFiles: List<File> = emptyList(),
    val captions: List<CaptionSegment> = emptyList(),
    val config: PipelineConfig
)

data class ClipEncodeResult(
    val filePath: String,
    val contentUri: String,
    val thumbnailPath: String,
    val fileSizeBytes: Long,
    val processingTimeMs: Long,
    val hasAudio: Boolean
)

/**
 * Full-motion 9:16 H.264 + AAC Vertical Video Editor & Encoder.
 * - Decodes real live stream video (single MPEG-TS / MP4 OR multi-segment fMP4 HLS) at smooth 24fps.
 * - Preserves real frame presentation timestamps for crisp audio/video lip-sync.
 * - Muxes original live audio from demuxed HLS audio, ScreenCapture internal audio, OR embedded TS audio,
 *   automatically synthesizing MPEG-4 AudioSpecificConfig (csd-0) and stripping ADTS headers when needed.
 * - Renders a studio-edited 9:16 frame with ambient live backdrop, top "Wait for it" badge, and
 *   burned-in spoken auto-captions (English, Hindi & all Unicode scripts).
 * - Saves directly to Movies/PulseClip via MediaStore so clips appear immediately in Gallery & Clips Vault.
 */
class VerticalClipEncoder(private val context: Context) {

    suspend fun encodeVerticalClip(
        request: ClipEncodeRequest,
        onProgress: suspend (percent: Int, stage: String) -> Unit
    ): ClipEncodeResult = withContext(Dispatchers.Default) {
        val startWallMs = System.currentTimeMillis()
        val profile = request.config.videoProfile
        val outW = (profile.width / 16) * 16
        val outH = (profile.height / 16) * 16
        val targetFps = profile.fps.coerceIn(24, 30)
        val frameDurationUs = 1_000_000L / targetFps
        val maxClipUs = request.durationSeconds * 1_000_000L
        val formattedCaptions = if (request.config.autoCaptionsEnabled) {
            AutoCaptionGenerator.formatForVerticalShorts(request.captions, maxWordsPerChunk = 5)
        } else {
            emptyList()
        }

        val videoSources = buildList {
            if (request.rawVideoFile != null && request.rawVideoFile.exists() && request.rawVideoFile.length() > 4096L) {
                add(request.rawVideoFile)
            }
            addAll(request.videoSegmentFiles.filter { it.exists() && it.length() > 1024L })
        }
        if (videoSources.isEmpty()) {
            throw IllegalStateException("No real live video stream recorded. Attach Screen + Audio Bridge in Pipeline tab if this stream is DRM-restricted.")
        }

        val audioSources = buildList {
            if (request.rawAudioFile != null && request.rawAudioFile.exists() && request.rawAudioFile.length() > 1024L) {
                add(request.rawAudioFile)
            }
            addAll(request.audioSegmentFiles.filter { it.exists() && it.length() > 512L })
            addAll(videoSources)
        }.distinct()

        onProgress(3, "Preparing 9:16 video + audio editor…")

        val tempDir = File(context.cacheDir, "pulseclip_encode").apply { mkdirs() }
        val safeChannel = request.channelTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(20)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "PulseClip_${safeChannel}_${stamp}.mp4"
        val tempMp4 = File(tempDir, fileName)
        if (tempMp4.exists()) tempMp4.delete()

        val renderer = VerticalVideoFrameRenderer(
            width = outW,
            height = outH,
            overlayText = request.config.overlayText.ifBlank { "Wait for it" },
            autoCaptionsEnabled = request.config.autoCaptionsEnabled && formattedCaptions.isNotEmpty(),
            captionStyle = request.config.captionStyle
        )

        val thumbDir = File(context.filesDir, "clip_thumbs").apply { mkdirs() }
        val thumbFile = File(thumbDir, "thumb_${request.clipId}_${stamp}.jpg")

        // Probe AAC audio track directly; if recorded WebM/container has Opus/Vorbis audio, transcode to AAC-LC!
        var transcodedAudioFile: File? = null
        var effectiveAudioSources = audioSources
        var audioProbe = probeCompatibleAudioTrack(effectiveAudioSources)
        if (audioProbe == null) {
            onProgress(4, "Converting live player audio to AAC…")
            val candidateTranscode = File(tempDir, "transcoded_aac_${request.clipId}_${stamp}.m4a")
            val transcoded = transcodeFirstNonAacAudioToM4a(
                sources = audioSources,
                outM4a = candidateTranscode,
                maxDurationUs = maxClipUs + 2_000_000L
            )
            if (transcoded != null && transcoded.exists() && transcoded.length() > 2048L) {
                transcodedAudioFile = transcoded
                effectiveAudioSources = listOf(transcoded)
                audioProbe = probeCompatibleAudioTrack(effectiveAudioSources)
            }
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null
        var muxerStarted = false
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var encodedFrameCount = 0L
        var lastMuxedVideoPtsUs = -1L
        var thumbSaved = false
        var audioSamplesMuxed = 0

        val submittedPtsQueue = ArrayDeque<Long>(64)

        try {
            fun buildAvcFormat(highProfile: Boolean): MediaFormat =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, profile.bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                    if (highProfile) {
                        setInteger(
                            MediaFormat.KEY_PROFILE,
                            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                        )
                        setInteger(
                            MediaFormat.KEY_LEVEL,
                            MediaCodecInfo.CodecProfileLevel.AVCLevel42
                        )
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    }
                }

            val highCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = try {
                highCodec.configure(buildAvcFormat(highProfile = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                highCodec
            } catch (_: Exception) {
                runCatching { highCodec.release() }
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(buildAvcFormat(highProfile = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            }
            inputSurface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(tempMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val encBufInfo = MediaCodec.BufferInfo()

            fun drainVideoEncoder(endOfStream: Boolean) {
                val timeoutUs = if (endOfStream) 10_000L else 0L
                while (true) {
                    val outIdx = encoder!!.dequeueOutputBuffer(encBufInfo, timeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }

                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                videoTrackIndex = muxer!!.addTrack(encoder!!.outputFormat)
                                if (audioProbe != null) {
                                    audioTrackIndex = runCatching {
                                        muxer!!.addTrack(audioProbe.cleanMuxerFormat)
                                    }.getOrDefault(-1)
                                }
                                muxer!!.start()
                                muxerStarted = true
                            }
                        }

                        outIdx >= 0 -> {
                            val data = encoder!!.getOutputBuffer(outIdx)
                            if (data != null && encBufInfo.size > 0 && muxerStarted) {
                                val isConfig = (encBufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (!isConfig) {
                                    data.position(encBufInfo.offset)
                                    data.limit(encBufInfo.offset + encBufInfo.size)
                                    val desiredPts = submittedPtsQueue.removeFirstOrNull()
                                        ?: (encodedFrameCount * frameDurationUs)
                                    val monotonicPts = if (lastMuxedVideoPtsUs >= 0L && desiredPts <= lastMuxedVideoPtsUs) {
                                        lastMuxedVideoPtsUs + 1000L
                                    } else {
                                        desiredPts
                                    }
                                    encBufInfo.presentationTimeUs = monotonicPts
                                    muxer!!.writeSampleData(videoTrackIndex, data, encBufInfo)
                                    lastMuxedVideoPtsUs = monotonicPts
                                    encodedFrameCount++
                                }
                            }
                            encoder!!.releaseOutputBuffer(outIdx, false)
                            if ((encBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                }
            }

            var renderedFrames = 0L
            var lastSubmittedPtsUs = -1L
            var lastPct = 4

            suspend fun submitCompositeFrame(sourceBitmap: Bitmap, timeUs: Long) {
                val monotonicPts = if (lastSubmittedPtsUs >= 0L && timeUs <= lastSubmittedPtsUs) {
                    lastSubmittedPtsUs + frameDurationUs
                } else {
                    timeUs.coerceAtLeast(0L)
                }
                lastSubmittedPtsUs = monotonicPts

                val timeMs = monotonicPts / 1000L
                val activeCaption = formattedCaptions.firstOrNull { seg ->
                    timeMs in seg.startMs..(seg.endMs + 200L)
                }

                if (!thumbSaved) {
                    saveThumbnail(renderer, sourceBitmap, activeCaption ?: formattedCaptions.firstOrNull(), thumbFile, outW, outH)
                    thumbSaved = true
                }

                submittedPtsQueue.addLast(monotonicPts)
                val canvas = runCatching { inputSurface!!.lockHardwareCanvas() }
                    .getOrElse { inputSurface!!.lockCanvas(null) }
                try {
                    renderer.drawFrame(
                        canvas = canvas,
                        sourceBitmap = sourceBitmap,
                        activeCaption = activeCaption
                    )
                } finally {
                    inputSurface!!.unlockCanvasAndPost(canvas)
                }

                renderedFrames++
                drainVideoEncoder(endOfStream = false)

                val pct = (6 + ((monotonicPts * 78L) / maxClipUs.coerceAtLeast(1_000_000L))).toInt().coerceIn(6, 84)
                if (pct - lastPct >= 2) {
                    lastPct = pct
                    val capNote = if (formattedCaptions.isNotEmpty()) " + ${formattedCaptions.size} captions" else ""
                    onProgress(pct, "Editing ${outW}×${outH} HD$capNote ($pct%)…")
                }
            }

            // Decode live video across all recorded sources (single TS/MP4 or multi-segment fMP4)
            onProgress(6, "Decoding live video frames…")
            var cumulativeOffsetUs = 0L
            for (srcFile in videoSources) {
                if (cumulativeOffsetUs >= maxClipUs) break
                val decodedSpanUs = decodeVideoFileViaMediaCodec(
                    rawVideoFile = srcFile,
                    timelineOffsetUs = cumulativeOffsetUs,
                    frameDurationUs = frameDurationUs,
                    maxDurationUs = maxClipUs,
                    onFrame = { bmp, absPtsUs -> submitCompositeFrame(bmp, absPtsUs) }
                )
                if (decodedSpanUs > 0L) {
                    cumulativeOffsetUs += decodedSpanUs + frameDurationUs
                }
            }

            // Fallback frame extraction via MediaMetadataRetriever if MediaCodec yielded too few frames
            if (renderedFrames < 8L) {
                onProgress(12, "Extracting live video frames…")
                var retrieverOffsetUs = 0L
                for (srcFile in videoSources) {
                    if (retrieverOffsetUs >= maxClipUs) break
                    val spanUs = decodeVideoViaMetadataRetriever(
                        rawVideoFile = srcFile,
                        timelineOffsetUs = retrieverOffsetUs,
                        fps = 24,
                        remainingMaxUs = (maxClipUs - retrieverOffsetUs).coerceAtLeast(1_000_000L),
                        onFrame = { bmp, absPtsUs -> submitCompositeFrame(bmp, absPtsUs) }
                    )
                    if (spanUs > 0L) {
                        retrieverOffsetUs += spanUs + frameDurationUs
                    }
                }
            }

            if (renderedFrames < 3L) {
                throw IllegalStateException("Live video frames could not be decoded. Try attaching Screen + Audio Bridge in Pipeline tab.")
            }

            onProgress(86, "Finalizing 9:16 H.264 video…")
            encoder.signalEndOfInputStream()
            drainVideoEncoder(endOfStream = true)

            val finalVideoDurationUs = (lastMuxedVideoPtsUs + frameDurationUs).coerceAtLeast(encodedFrameCount * frameDurationUs)
            if (muxerStarted && audioTrackIndex >= 0 && audioProbe != null && finalVideoDurationUs > 0L) {
                onProgress(90, "Muxing original live audio track…")
                audioSamplesMuxed = copyAudioSourcesToMuxer(
                    audioSources = effectiveAudioSources,
                    muxer = muxer,
                    muxerAudioTrackIndex = audioTrackIndex,
                    maxDurationUs = finalVideoDurationUs
                )
            }

            onProgress(94, "Writing edited MP4 container…")
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            encoder = null
            try { inputSurface.release() } catch (_: Exception) {}
            inputSurface = null

            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()
            muxer = null

            if (!tempMp4.exists() || tempMp4.length() < 8192L) {
                throw IllegalStateException("Encoded MP4 file was empty.")
            }

            onProgress(97, "Saving clip to Movies/PulseClip…")
            val saved = saveToLocalDeviceStorage(tempMp4, fileName)
            val elapsedMs = System.currentTimeMillis() - startWallMs
            val audioLabel = if (audioSamplesMuxed > 0) "Video + Audio" else "Video"
            onProgress(100, "Saved ($audioLabel) to ${saved.filePath}")

            ClipEncodeResult(
                filePath = saved.filePath,
                contentUri = saved.contentUri,
                thumbnailPath = if (thumbFile.exists()) thumbFile.absolutePath else "",
                fileSizeBytes = saved.fileSizeBytes,
                processingTimeMs = elapsedMs,
                hasAudio = audioSamplesMuxed > 0
            )
        } finally {
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            if (tempMp4.exists()) tempMp4.delete()
            if (transcodedAudioFile?.exists() == true) transcodedAudioFile.delete()
        }
    }

    /**
     * Decodes a video file sequentially with MediaCodec and returns the decoded duration span in microseconds.
     */
    private suspend fun decodeVideoFileViaMediaCodec(
        rawVideoFile: File,
        timelineOffsetUs: Long,
        frameDurationUs: Long,
        maxDurationUs: Long,
        onFrame: suspend (Bitmap, Long) -> Unit
    ): Long {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var reusableBitmap: Bitmap? = null
        var argbBuffer: IntArray? = null

        return try {
            extractor.setDataSource(rawVideoFile.absolutePath)
            var videoTrack = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    videoFormat = fmt
                    break
                }
            }
            if (videoTrack < 0 || videoFormat == null) return 0L

            extractor.selectTrack(videoTrack)
            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: return 0L
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(videoFormat, null, null, 0)
                start()
            }

            val decBufInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var firstPtsUs = -1L
            var lastRelPtsUs = 0L
            var nextSamplePtsUs = 0L
            var stallTries = 0

            while (!outputDone && stallTries < 200) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(decBufInfo, 10_000L)
                if (outIdx >= 0) {
                    stallTries = 0
                    val isEos = (decBufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (decBufInfo.size > 0) {
                        if (firstPtsUs < 0L) firstPtsUs = decBufInfo.presentationTimeUs
                        val relPtsUs = (decBufInfo.presentationTimeUs - firstPtsUs).coerceAtLeast(0L)
                        val absPtsUs = timelineOffsetUs + relPtsUs

                        if (absPtsUs > maxDurationUs) {
                            decoder.releaseOutputBuffer(outIdx, false)
                            lastRelPtsUs = relPtsUs
                            break
                        }

                        if (relPtsUs >= nextSamplePtsUs) {
                            val image = runCatching { decoder.getOutputImage(outIdx) }.getOrNull()
                            if (image != null && image.format == ImageFormat.YUV_420_888) {
                                // Respect decoder cropRect (prevents 1088px macroblock padding artifacts) and keep 100% 1080p/1440p detail
                                val crop = image.cropRect
                                val cropLeft = crop.left.coerceIn(0, image.width - 1)
                                val cropTop = crop.top.coerceIn(0, image.height - 1)
                                val cropW = crop.width().coerceIn(1, image.width - cropLeft)
                                val cropH = crop.height().coerceIn(1, image.height - cropTop)
                                val step = if (cropW > 2560) 2 else 1
                                val dstW = (cropW / step).coerceAtLeast(1)
                                val dstH = (cropH / step).coerceAtLeast(1)
                                if (reusableBitmap == null || reusableBitmap!!.width != dstW || reusableBitmap!!.height != dstH) {
                                    reusableBitmap?.recycle()
                                    reusableBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
                                    argbBuffer = IntArray(dstW * dstH)
                                }
                                yuv420ImageToBitmapFast(
                                    image = image,
                                    cropLeft = cropLeft,
                                    cropTop = cropTop,
                                    useBt709 = cropW >= 1200,
                                    step = step,
                                    bitmap = reusableBitmap!!,
                                    outArgb = argbBuffer!!
                                )
                                image.close()
                                onFrame(reusableBitmap!!, absPtsUs)
                                lastRelPtsUs = relPtsUs
                                nextSamplePtsUs = relPtsUs + frameDurationUs
                            } else {
                                image?.close()
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (isEos) outputDone = true
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    stallTries++
                }
            }
            lastRelPtsUs
        } catch (_: Exception) {
            0L
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            reusableBitmap?.recycle()
        }
    }

    private suspend fun decodeVideoViaMetadataRetriever(
        rawVideoFile: File,
        timelineOffsetUs: Long,
        fps: Int,
        remainingMaxUs: Long,
        onFrame: suspend (Bitmap, Long) -> Unit
    ): Long {
        val retriever = MediaMetadataRetriever()
        var lastUs = 0L
        try {
            retriever.setDataSource(rawVideoFile.absolutePath)
            val metaDurMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: (remainingMaxUs / 1000L)
            val usableDurUs = (metaDurMs * 1000L).coerceAtMost(remainingMaxUs)
            val stepUs = 1_000_000L / fps.coerceAtLeast(10)
            var tUs = 0L
            while (tUs < usableDurUs) {
                val frame = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    onFrame(frame, timelineOffsetUs + tUs)
                    frame.recycle()
                    lastUs = tUs
                }
                tUs += stepUs
            }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        return lastUs
    }

    private data class AudioTrackProbe(val cleanMuxerFormat: MediaFormat)

    /**
     * Probes the first valid AAC audio track across all audio/video sources.
     * Crucially synthesizes MPEG-4 AudioSpecificConfig (`csd-0`) when MediaExtractor omits it
     * on MPEG-TS (.ts) streams, and clears KEY_IS_ADTS so MediaMuxer writes a valid MP4 audio track.
     */
    private fun probeCompatibleAudioTrack(sources: List<File>): AudioTrackProbe? {
        for (file in sources) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime.startsWith("audio/mp4a")) {
                        val sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } else {
                            44_100
                        }
                        val channelCount = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } else {
                            2
                        }

                        val cleanFmt = MediaFormat.createAudioFormat(
                            MediaFormat.MIMETYPE_AUDIO_AAC,
                            sampleRate,
                            channelCount
                        )

                        // Preserve existing csd-0 OR synthesize it from the first ADTS packet / format metadata
                        val csd0 = if (fmt.containsKey("csd-0")) {
                            fmt.getByteBuffer("csd-0")?.duplicate()
                        } else {
                            extractOrBuildAacCsd0(extractor, i, sampleRate, channelCount)
                        }
                        if (csd0 != null) {
                            cleanFmt.setByteBuffer("csd-0", csd0)
                            cleanFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                            if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                cleanFmt.setInteger(MediaFormat.KEY_BIT_RATE, fmt.getInteger(MediaFormat.KEY_BIT_RATE))
                            }
                            return AudioTrackProbe(cleanFmt)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { extractor.release() }
            }
        }
        return null
    }

    /**
     * Decodes a non-AAC audio track (such as audio/opus or audio/vorbis from WebView MediaRecorder WebM)
     * into 16-bit PCM via MediaCodec and re-encodes it into a clean AAC-LC .m4a file for MP4 muxing.
     */
    private fun transcodeFirstNonAacAudioToM4a(
        sources: List<File>,
        outM4a: File,
        maxDurationUs: Long
    ): File? {
        for (file in sources) {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            var encoder: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var muxerStarted = false
            var outTrackIdx = -1
            try {
                extractor.setDataSource(file.absolutePath)
                var audioTrack = -1
                var inFormat: MediaFormat? = null
                var inMime: String? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/") && mime != MediaFormat.MIMETYPE_AUDIO_AAC && !mime.startsWith("audio/mp4a")) {
                        audioTrack = i
                        inFormat = fmt
                        inMime = mime
                        break
                    }
                }
                if (audioTrack < 0 || inFormat == null || inMime == null) continue

                extractor.selectTrack(audioTrack)
                val sampleRate = if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceIn(8000, 48_000)
                } else {
                    48_000
                }
                val channelCount = if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                } else {
                    2
                }

                decoder = MediaCodec.createDecoderByType(inMime).apply {
                    configure(inFormat, null, null, 0)
                    start()
                }

                val encFmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    sampleRate,
                    channelCount
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32_768)
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
                if (outM4a.exists()) outM4a.delete()
                muxer = MediaMuxer(outM4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val decInfo = MediaCodec.BufferInfo()
                val encInfo = MediaCodec.BufferInfo()
                var extractorDone = false
                var decoderDone = false
                var encoderInputEos = false
                var encoderDone = false
                var firstPtsUs = -1L
                var totalPcmFrames = 0L
                var samplesWritten = 0
                var stallLoops = 0

                fun drainAacEncoder(eos: Boolean) {
                    val timeout = if (eos) 10_000L else 0L
                    while (true) {
                        val idx = encoder!!.dequeueOutputBuffer(encInfo, timeout)
                        when {
                            idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) break
                            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!muxerStarted) {
                                    outTrackIdx = muxer!!.addTrack(encoder!!.outputFormat)
                                    muxer!!.start()
                                    muxerStarted = true
                                }
                            }

                            idx >= 0 -> {
                                val data = encoder!!.getOutputBuffer(idx)
                                val isCfg = (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (data != null && encInfo.size > 0 && muxerStarted && !isCfg) {
                                    data.position(encInfo.offset)
                                    data.limit(encInfo.offset + encInfo.size)
                                    muxer!!.writeSampleData(outTrackIdx, data, encInfo)
                                    samplesWritten++
                                }
                                encoder!!.releaseOutputBuffer(idx, false)
                                if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    encoderDone = true
                                    break
                                }
                            }
                        }
                    }
                }

                while (!encoderDone && stallLoops < 300) {
                    if (!extractorDone) {
                        val inIdx = decoder.dequeueInputBuffer(5_000L)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                val rawPts = extractor.sampleTime.coerceAtLeast(0L)
                                if (firstPtsUs < 0L) firstPtsUs = rawPts
                                val relPts = (rawPts - firstPtsUs).coerceAtLeast(0L)
                                if (relPts > maxDurationUs) {
                                    decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    extractorDone = true
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, size, relPts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    if (!decoderDone) {
                        val decOutIdx = decoder.dequeueOutputBuffer(decInfo, 5_000L)
                        if (decOutIdx >= 0) {
                            stallLoops = 0
                            val pcmBuf = decoder.getOutputBuffer(decOutIdx)
                            val isDecEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (pcmBuf != null && decInfo.size > 0) {
                                pcmBuf.position(decInfo.offset)
                                pcmBuf.limit(decInfo.offset + decInfo.size)
                                val pcmBytes = ByteArray(decInfo.size)
                                pcmBuf.get(pcmBytes)

                                var consumed = 0
                                while (consumed < pcmBytes.size) {
                                    val encInIdx = encoder.dequeueInputBuffer(10_000L)
                                    if (encInIdx >= 0) {
                                        val encInBuf = encoder.getInputBuffer(encInIdx)!!
                                        encInBuf.clear()
                                        val chunk = minOf(pcmBytes.size - consumed, encInBuf.remaining())
                                        encInBuf.put(pcmBytes, consumed, chunk)
                                        val ptsUs = (totalPcmFrames * 1_000_000L) / sampleRate
                                        totalPcmFrames += chunk / (2 * channelCount)
                                        encoder.queueInputBuffer(encInIdx, 0, chunk, ptsUs, 0)
                                        consumed += chunk
                                    }
                                    drainAacEncoder(eos = false)
                                }
                            }
                            decoder.releaseOutputBuffer(decOutIdx, false)
                            if (isDecEos) decoderDone = true
                        } else if (decOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER && extractorDone) {
                            stallLoops++
                        }
                    }

                    if (decoderDone && !encoderInputEos) {
                        val encInIdx = encoder.dequeueInputBuffer(10_000L)
                        if (encInIdx >= 0) {
                            val ptsUs = (totalPcmFrames * 1_000_000L) / sampleRate
                            encoder.queueInputBuffer(encInIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInputEos = true
                        }
                    }

                    drainAacEncoder(eos = encoderInputEos)
                }

                if (samplesWritten > 0 && muxerStarted) {
                    runCatching { muxer.stop() }
                    muxerStarted = false
                    return outM4a
                }
            } catch (_: Exception) {
            } finally {
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { encoder?.stop() }
                runCatching { encoder?.release() }
                if (muxerStarted) runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
                runCatching { extractor.release() }
            }
        }
        return null
    }

    private fun extractOrBuildAacCsd0(
        extractor: MediaExtractor,
        trackIndex: Int,
        fallbackSampleRate: Int,
        fallbackChannels: Int
    ): ByteBuffer {
        var profileObjType = 2 // AAC-LC
        var freqIdx = sampleRateToAdtsIndex(fallbackSampleRate)
        var chanCfg = fallbackChannels.coerceIn(1, 7)

        try {
            extractor.selectTrack(trackIndex)
            val probeBuf = ByteBuffer.allocate(16_384)
            val n = extractor.readSampleData(probeBuf, 0)
            if (n > 7 &&
                (probeBuf.get(0).toInt() and 0xFF) == 0xFF &&
                (probeBuf.get(1).toInt() and 0xF0) == 0xF0
            ) {
                val b2 = probeBuf.get(2).toInt() and 0xFF
                val b3 = probeBuf.get(3).toInt() and 0xFF
                profileObjType = ((b2 ushr 6) and 0x03) + 1
                freqIdx = (b2 ushr 2) and 0x0F
                chanCfg = ((b2 and 0x01) shl 2) or ((b3 ushr 6) and 0x03)
            }
        } catch (_: Exception) {
        }

        val byte0 = ((profileObjType shl 3) or (freqIdx ushr 1)).toByte()
        val byte1 = (((freqIdx and 0x01) shl 7) or (chanCfg shl 3)).toByte()
        return ByteBuffer.wrap(byteArrayOf(byte0, byte1))
    }

    private fun sampleRateToAdtsIndex(sampleRate: Int): Int = when (sampleRate) {
        96000 -> 0
        88200 -> 1
        64000 -> 2
        48000 -> 3
        44100 -> 4
        32000 -> 5
        24000 -> 6
        22050 -> 7
        16000 -> 8
        12000 -> 9
        11025 -> 10
        8000 -> 11
        else -> 4 // 44100Hz default
    }

    /**
     * Copies AAC audio samples across one or more recorded files into MediaMuxer with ADTS header
     * stripping and strict monotonic presentation timestamps.
     */
    private fun copyAudioSourcesToMuxer(
        audioSources: List<File>,
        muxer: MediaMuxer,
        muxerAudioTrackIndex: Int,
        maxDurationUs: Long
    ): Int {
        val buffer = ByteBuffer.allocate(128 * 1024)
        val info = MediaCodec.BufferInfo()
        var timelineOffsetUs = 0L
        var lastWrittenPtsUs = -1L
        var samplesWritten = 0

        for (file in audioSources) {
            if (timelineOffsetUs >= maxDurationUs) break
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                var audioTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime.startsWith("audio/mp4a")) {
                        audioTrack = i
                        break
                    }
                }
                if (audioTrack < 0) continue

                extractor.selectTrack(audioTrack)
                var firstFilePts = -1L
                var maxFileRelPts = 0L

                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val rawPts = extractor.sampleTime.coerceAtLeast(0L)
                    if (firstFilePts < 0L) firstFilePts = rawPts
                    val relPts = (rawPts - firstFilePts).coerceAtLeast(0L)
                    val absPts = timelineOffsetUs + relPts
                    if (absPts > maxDurationUs) break

                    // Strip 7 or 9-byte ADTS header if present
                    var offset = 0
                    var writeSize = size
                    if (size > 7 &&
                        (buffer.get(0).toInt() and 0xFF) == 0xFF &&
                        (buffer.get(1).toInt() and 0xF0) == 0xF0
                    ) {
                        val protectionAbsent = (buffer.get(1).toInt() and 0x01) == 1
                        val headerLen = if (protectionAbsent) 7 else 9
                        if (size > headerLen) {
                            offset = headerLen
                            writeSize = size - headerLen
                        }
                    }

                    val monotonicPts = if (lastWrittenPtsUs >= 0L && absPts <= lastWrittenPtsUs) {
                        lastWrittenPtsUs + 21_333L
                    } else {
                        absPts
                    }
                    if (monotonicPts > maxDurationUs) break

                    info.offset = offset
                    info.size = writeSize
                    info.presentationTimeUs = monotonicPts
                    info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    muxer.writeSampleData(muxerAudioTrackIndex, buffer, info)
                    lastWrittenPtsUs = monotonicPts
                    maxFileRelPts = maxOf(maxFileRelPts, relPts)
                    samplesWritten++
                    extractor.advance()
                }
                if (maxFileRelPts > 0L) {
                    timelineOffsetUs += maxFileRelPts + 23_220L
                }
            } catch (_: Exception) {
            } finally {
                runCatching { extractor.release() }
            }
        }
        return samplesWritten
    }

    private fun yuv420ImageToBitmapFast(
        image: Image,
        cropLeft: Int,
        cropTop: Int,
        useBt709: Boolean,
        step: Int,
        bitmap: Bitmap,
        outArgb: IntArray
    ) {
        val dstW = bitmap.width
        val dstH = bitmap.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        // HD BT.709 matrix for 720p/1080p YouTube streams; BT.601 for SD
        val rvCoeff = if (useBt709) 1836 else 1634
        val gvCoeff = if (useBt709) 545 else 833
        val guCoeff = if (useBt709) 218 else 400
        val buCoeff = if (useBt709) 2165 else 2066

        var outIdx = 0
        for (dj in 0 until dstH) {
            val j = cropTop + dj * step
            val yRowOffset = j * yRowStride
            val uvRow = j shr 1
            val uRowOffset = uvRow * uRowStride
            val vRowOffset = uvRow * vRowStride

            for (di in 0 until dstW) {
                val i = cropLeft + di * step
                val yVal = (yBuf.get(yRowOffset + i * yPixelStride).toInt() and 0xFF) - 16
                val uvCol = i shr 1
                val uVal = (uBuf.get(uRowOffset + uvCol * uPixelStride).toInt() and 0xFF) - 128
                val vVal = (vBuf.get(vRowOffset + uvCol * vPixelStride).toInt() and 0xFF) - 128

                val y1192 = if (yVal < 0) 0 else 1192 * yVal
                var r = (y1192 + rvCoeff * vVal) shr 10
                var g = (y1192 - gvCoeff * vVal - guCoeff * uVal) shr 10
                var b = (y1192 + buCoeff * uVal) shr 10

                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                outArgb[outIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap.setPixels(outArgb, 0, dstW, 0, 0, dstW, dstH)
    }

    private data class SavedFileDescriptor(
        val filePath: String,
        val contentUri: String,
        val fileSizeBytes: Long
    )

    private fun saveToLocalDeviceStorage(sourceMp4: File, fileName: String): SavedFileDescriptor {
        val sizeBytes = sourceMp4.length()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/PulseClip")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        sourceMp4.inputStream().use { input -> input.copyTo(out) }
                    }
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    resolver.update(uri, doneValues, null, null)
                    return SavedFileDescriptor(
                        filePath = "Movies/PulseClip/$fileName",
                        contentUri = uri.toString(),
                        fileSizeBytes = sizeBytes
                    )
                }
            } catch (_: Exception) {
            }
        }

        val moviesRoot = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val destDir = File(moviesRoot, "PulseClip").apply { mkdirs() }
        val destFile = File(destDir, fileName)
        sourceMp4.copyTo(destFile, overwrite = true)
        return SavedFileDescriptor(
            filePath = destFile.absolutePath,
            contentUri = android.net.Uri.fromFile(destFile).toString(),
            fileSizeBytes = destFile.length()
        )
    }

    private fun saveThumbnail(
        renderer: VerticalVideoFrameRenderer,
        sourceBitmap: Bitmap,
        caption: CaptionSegment?,
        thumbFile: File,
        width: Int,
        height: Int
    ) {
        val thumbW = (width / 2).coerceAtLeast(270)
        val thumbH = (height / 2).coerceAtLeast(480)
        val bmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.save()
        canvas.scale(thumbW.toFloat() / width.toFloat(), thumbH.toFloat() / height.toFloat())
        renderer.drawFrame(canvas, sourceBitmap, caption)
        canvas.restore()
        FileOutputStream(thumbFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bmp.recycle()
    }
}

private class VerticalVideoFrameRenderer(
    private val width: Int,
    private val height: Int,
    private val overlayText: String,
    private val autoCaptionsEnabled: Boolean,
    private val captionStyle: CaptionStyle
) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val solidBgPaint = Paint().apply { color = Color.rgb(11, 13, 18) }
    private val dimPaint = Paint().apply { color = Color.argb(205, 8, 10, 14) }
    private val frameBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = (width * 0.004f).coerceAtLeast(2.5f)
    }
    private val hookPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 255, 59, 92)
    }
    private val hookTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = width * 0.066f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val captionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val captionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = (width * 0.004f).coerceAtLeast(2f)
    }
    private val captionTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = width * 0.056f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(width * 0.012f, 0f, width * 0.006f, Color.argb(210, 0, 0, 0))
    }
    private val captionStrokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = width * 0.016f
        textSize = width * 0.056f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    fun drawFrame(
        canvas: Canvas,
        sourceBitmap: Bitmap?,
        activeCaption: CaptionSegment?
    ) {
        if (sourceBitmap == null || sourceBitmap.isRecycled) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), solidBgPaint)
        } else {
            val srcW = sourceBitmap.width
            val srcH = sourceBitmap.height

            if (srcH > srcW) {
                // Already portrait -> crisp full-bleed 9:16
                drawCenterCrop(canvas, sourceBitmap, RectF(0f, 0f, width.toFloat(), height.toFloat()))
            } else {
                // Landscape 16:9 YouTube live stream -> ambient darkened backdrop + razor-sharp centered 16:9 hero video
                drawCenterCrop(canvas, sourceBitmap, RectF(0f, 0f, width.toFloat(), height.toFloat()))
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

                val aspect = srcH.toFloat() / srcW.toFloat().coerceAtLeast(1f)
                // Exact native 1:1 16:9 aspect ratio — zero zoom/crop blur, matching the live 1080p player 1:1
                val videoH = (width * aspect).coerceIn(height * 0.31f, height * 0.52f)
                val top = (height - videoH) * 0.44f
                val dstRect = RectF(0f, top, width.toFloat(), top + videoH)
                drawCenterCrop(canvas, sourceBitmap, dstRect)
                canvas.drawLine(0f, top, width.toFloat(), top, frameBorderPaint)
                canvas.drawLine(0f, top + videoH, width.toFloat(), top + videoH, frameBorderPaint)
            }
        }

        // Top Hook Overlay ("WAIT FOR IT")
        val hookCenterY = height * 0.135f
        val hookUpper = overlayText.uppercase(Locale.US)
        val hookWidth = (hookTextPaint.measureText(hookUpper) + width * 0.14f)
            .coerceIn(width * 0.46f, width * 0.88f)
        val hookHeight = width * 0.13f
        val hookRect = RectF(
            (width - hookWidth) / 2f,
            hookCenterY - hookHeight / 2f,
            (width + hookWidth) / 2f,
            hookCenterY + hookHeight / 2f
        )
        canvas.drawRoundRect(hookRect, hookHeight * 0.35f, hookHeight * 0.35f, hookPillPaint)
        val textBaseline = hookCenterY - (hookTextPaint.descent() + hookTextPaint.ascent()) / 2f
        canvas.drawText(hookUpper, width / 2f, textBaseline, hookTextPaint)

        // Burned-in Viral Shorts/Reels Spoken Auto-Captions (English, Hindi & all Unicode scripts; never chat)
        if (autoCaptionsEnabled && activeCaption != null && activeCaption.text.isNotBlank()) {
            drawSpokenCaption(canvas, activeCaption.text)
        }
    }

    private fun drawCenterCrop(canvas: Canvas, bmp: Bitmap, dst: RectF) {
        val dstRatio = dst.width() / dst.height().coerceAtLeast(1f)
        val srcRatio = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)

        val cropW: Int
        val cropH: Int
        if (srcRatio > dstRatio) {
            cropH = bmp.height
            cropW = (bmp.height * dstRatio).toInt().coerceIn(1, bmp.width)
        } else {
            cropW = bmp.width
            cropH = (bmp.width / dstRatio).toInt().coerceIn(1, bmp.height)
        }
        val left = (bmp.width - cropW) / 2
        val top = (bmp.height - cropH) / 2
        canvas.drawBitmap(bmp, Rect(left, top, left + cropW, top + cropH), dst, bgPaint)
    }

    private fun drawSpokenCaption(canvas: Canvas, text: String) {
        val maxTextWidth = (width * 0.84f).toInt()
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, captionTextPaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .build()

        val blockW = width * 0.90f
        val padY = width * 0.036f
        val blockH = layout.height + padY * 2f
        val blockTop = height * 0.725f
        val blockRect = RectF(
            (width - blockW) / 2f,
            blockTop,
            (width + blockW) / 2f,
            blockTop + blockH
        )

        when (captionStyle) {
            CaptionStyle.DYNAMIC_PILL -> {
                captionBgPaint.color = Color.argb(232, 12, 16, 26)
                canvas.drawRoundRect(blockRect, width * 0.042f, width * 0.042f, captionBgPaint)
                canvas.drawRoundRect(blockRect, width * 0.042f, width * 0.042f, captionBorderPaint)
            }

            CaptionStyle.BROADCAST_BAR -> {
                captionBgPaint.color = Color.argb(238, 8, 10, 14)
                canvas.drawRect(0f, blockTop, width.toFloat(), blockTop + blockH, captionBgPaint)
                canvas.drawLine(0f, blockTop, width.toFloat(), blockTop, captionBorderPaint)
            }

            CaptionStyle.BOLD_OUTLINE -> Unit
        }

        canvas.save()
        canvas.translate((width - maxTextWidth) / 2f, blockTop + padY)
        // Always draw subtle crisp stroke underneath text for razor-sharp Reels legibility
        val strokeLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, captionStrokePaint, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(3)
            .build()
        strokeLayout.draw(canvas)
        layout.draw(canvas)
        canvas.restore()
    }
}
