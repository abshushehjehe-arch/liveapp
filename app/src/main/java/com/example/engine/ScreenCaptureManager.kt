package com.example.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class ScreenRecordingOutput(
    val videoFile: File,
    val audioFile: File?
)

/**
 * Optional MediaProjection continuous H.264 video + internal AudioPlaybackCapture AAC audio bridge.
 * When enabled, captures BOTH smooth 24fps live video AND clean internal YouTube playback audio
 * (Android 10+ with RECORD_AUDIO permission) during a 30-60s recording window.
 */
class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var activeVideoFile: File? = null
    private var activeAudioFile: File? = null
    private var audioCaptureStopFlag: AtomicBoolean? = null
    private var audioCaptureThread: Thread? = null
    private val lock = Any()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun startCapture(resultCode: Int, data: Intent): Boolean {
        stopCapture()
        return synchronized(lock) {
            try {
                val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = manager.getMediaProjection(resultCode, data) ?: return false
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopCapture()
                    }
                }, null)
                mediaProjection = projection
                _isActive.value = true
                true
            } catch (_: Exception) {
                stopCapture()
                false
            }
        }
    }

    /**
     * Starts real continuous H.264 MP4 video recording AND internal playback AAC audio capture
     * (on Android 10+ when RECORD_AUDIO permission is granted).
     */
    fun startVideoRecording(outputVideoFile: File): Boolean = synchronized(lock) {
        val projection = mediaProjection ?: return false
        stopVideoRecordingInternal()

        return try {
            val metrics = context.resources.displayMetrics
            val width = (metrics.widthPixels / 2).coerceIn(360, 720).let { it - (it % 16) }.coerceAtLeast(368)
            val height = (metrics.heightPixels / 2).coerceIn(640, 1280).let { it - (it % 16) }.coerceAtLeast(640)
            val density = metrics.densityDpi

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(24)
                setVideoEncodingBitRate(3_000_000)
                setOutputFile(outputVideoFile.absolutePath)
                prepare()
            }

            val display = projection.createVirtualDisplay(
                "PulseClipVideoRecorder",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )

            recorder.start()
            mediaRecorder = recorder
            virtualDisplay = display
            activeVideoFile = outputVideoFile

            // Simultaneously capture internal media playback audio on Android 10+ if permitted
            val audioOut = File(outputVideoFile.parentFile, outputVideoFile.nameWithoutExtension + "_audio.m4a")
            if (startInternalAudioCapture(projection, audioOut)) {
                activeAudioFile = audioOut
            }
            true
        } catch (_: Exception) {
            stopVideoRecordingInternal()
            false
        }
    }

    /**
     * Stops active video + internal audio recording and returns the completed files if valid.
     */
    fun stopVideoRecording(): ScreenRecordingOutput? = synchronized(lock) {
        val vid = activeVideoFile
        val aud = activeAudioFile
        stopVideoRecordingInternal()
        if (vid != null && vid.exists() && vid.length() > 16_384L) {
            val validAudio = if (aud != null && aud.exists() && aud.length() > 2048L) aud else null
            ScreenRecordingOutput(videoFile = vid, audioFile = validAudio)
        } else {
            runCatching { aud?.delete() }
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startInternalAudioCapture(projection: MediaProjection, outAudioFile: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return false

        return try {
            val sampleRate = 44_100
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding).coerceAtLeast(8192)

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                return false
            }

            val stopFlag = AtomicBoolean(false)
            audioCaptureStopFlag = stopFlag

            val thread = Thread({
                recordPcmToAacM4a(audioRecord, outAudioFile, sampleRate, 2, stopFlag)
            }, "PulseClipAudioCapture")
            audioCaptureThread = thread
            thread.start()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun recordPcmToAacM4a(
        audioRecord: AudioRecord,
        outAudioFile: File,
        sampleRate: Int,
        channelCount: Int,
        stopFlag: AtomicBoolean
    ) {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1

        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(outAudioFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            audioRecord.startRecording()

            val pcmBuf = ByteArray(4096)
            val bufInfo = MediaCodec.BufferInfo()
            var totalSamples = 0L

            fun drain(eos: Boolean) {
                val timeoutUs = if (eos) 10_000L else 0L
                while (true) {
                    val outIdx = codec!!.dequeueOutputBuffer(bufInfo, timeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = muxer!!.addTrack(codec!!.outputFormat)
                                muxer!!.start()
                                muxerStarted = true
                            }
                        }

                        outIdx >= 0 -> {
                            val data = codec!!.getOutputBuffer(outIdx)
                            val isConfig = (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (data != null && bufInfo.size > 0 && muxerStarted && !isConfig) {
                                data.position(bufInfo.offset)
                                data.limit(bufInfo.offset + bufInfo.size)
                                muxer!!.writeSampleData(trackIndex, data, bufInfo)
                            }
                            codec!!.releaseOutputBuffer(outIdx, false)
                            if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                }
            }

            while (!stopFlag.get()) {
                val read = audioRecord.read(pcmBuf, 0, pcmBuf.size)
                if (read > 0) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val copyLen = minOf(read, inBuf.remaining())
                        inBuf.put(pcmBuf, 0, copyLen)
                        val ptsUs = (totalSamples * 1_000_000L) / sampleRate
                        totalSamples += (copyLen / (2 * channelCount))
                        codec.queueInputBuffer(inIdx, 0, copyLen, ptsUs, 0)
                    }
                    drain(eos = false)
                }
            }

            val eosIdx = codec.dequeueInputBuffer(10_000L)
            if (eosIdx >= 0) {
                val ptsUs = (totalSamples * 1_000_000L) / sampleRate
                codec.queueInputBuffer(eosIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(eos = true)
        } catch (_: Exception) {
        } finally {
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }

    private fun stopVideoRecordingInternal() {
        audioCaptureStopFlag?.set(true)
        runCatching { audioCaptureThread?.join(1500L) }
        audioCaptureStopFlag = null
        audioCaptureThread = null

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        activeVideoFile = null
        activeAudioFile = null
    }

    fun stopCapture() = synchronized(lock) {
        _isActive.value = false
        stopVideoRecordingInternal()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }
}
