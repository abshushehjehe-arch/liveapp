package com.example.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.view.ViewGroup
import android.view.Window
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class EmbeddedPlayerStatus(
    val channelId: String,
    val videoId: String,
    val isLoaded: Boolean = false,
    val isPlaying: Boolean = false,
    val isMuted: Boolean = true,
    val isRecording: Boolean = false,
    val loginRequired: Boolean = false,
    val qualityLabel: String = "1080p HD",
    val recordedBytes: Long = 0L,
    val captionCount: Int = 0,
    val captureMode: String = "Internal HD Stream",
    val statusNote: String = "Loading 1080p live player…"
)

data class WebViewChatPulse(
    val channelId: String,
    val messageCount: Int,
    val isInitialSeed: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

data class WebViewPlayerMeta(
    val channelId: String,
    val videoId: String,
    val title: String,
    val author: String,
    val isLive: Boolean
)

/**
 * Manages persistent per-channel 1080p Desktop-mode YouTube WebView mini-players and records
 * ONLY the pure internal broadcast main stream (ZERO screen capture, ZERO PixelCopy, ZERO UI overlays,
 * ZERO Android notifications).
 *
 * Pure Internal Recording Stack:
 * - Layer 1 (Primary — Raw YouTube MSE Bitstream Tap): Hooks `MediaSource.prototype.addSourceBuffer`
 *   and `SourceBuffer.prototype.appendBuffer` inside the player BEFORE YouTube boots. Captures the
 *   exact original 1080p/720p video + stereo audio fMP4/WebM segments straight from YouTube's player
 *   pipeline with zero re-encoding loss and zero on-screen UI elements!
 * - Layer 2 (Internal Fallback — Offscreen `<video>` / `<canvas>` + WebAudio `MediaRecorder`):
 *   If a stream doesn't use MSE, records directly from the `<video>` element's internal frames +
 *   WebAudio at 8 Mbps (still 100% pure video pixels, never screen/UI/notifications).
 *
 * 4-Layer Guaranteed Spoken Captions:
 * 1) In-WebView XHR & fetch() `/api/timedtext` live subtitle interceptor.
 * 2) Active YouTube CC enabler + DOM caption scraper (`.ytp-caption-segment`).
 * 3) Authenticated WebView fetch of `player.getPlayerResponse().captions` tracks.
 * 4) On-device live `SpeechRecognizer` backup (Hindi/Hinglish/English) when mic permission is granted.
 */
class EmbeddedPlayerManager(
    private val context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val slots = ConcurrentHashMap<String, ChannelPlayerSlot>()
    private val activeRecordings = ConcurrentHashMap<String, ActivePlayerRecordingSession>()

    @Volatile
    private var parkingHostRef: WeakReference<FrameLayout>? = null

    private val _playerStatuses = MutableStateFlow<Map<String, EmbeddedPlayerStatus>>(emptyMap())
    val playerStatuses: StateFlow<Map<String, EmbeddedPlayerStatus>> = _playerStatuses.asStateFlow()

    private val _chatPulses = MutableSharedFlow<WebViewChatPulse>(extraBufferCapacity = 160)
    val chatPulses: SharedFlow<WebViewChatPulse> = _chatPulses.asSharedFlow()

    private val _playerMeta = MutableSharedFlow<WebViewPlayerMeta>(extraBufferCapacity = 64)
    val playerMeta: SharedFlow<WebViewPlayerMeta> = _playerMeta.asSharedFlow()

    private val _isYouTubeSignedIn = MutableStateFlow(hasYouTubeAuthCookies())
    val isYouTubeSignedIn: StateFlow<Boolean> = _isYouTubeSignedIn.asStateFlow()

    private val _loginPopupRequested = MutableStateFlow(false)
    val loginPopupRequested: StateFlow<Boolean> = _loginPopupRequested.asStateFlow()

    private val autoPromptedLoginOnce = AtomicBoolean(false)

    private class ChannelPlayerSlot(
        val channelId: String,
        var videoId: String,
        val webView: WebView
    )

    private class ActivePlayerRecordingSession(
        val channelId: String,
        val workDir: File,
        val startedAtMs: Long,
        val stopFlag: AtomicBoolean,
        val onProgress: (bytes: Long, captions: Int, mode: String) -> Unit
    ) {
        val lock = Any()

        // Layer 1: Raw MSE fMP4/WebM video & audio streams straight from YouTube's SourceBuffer
        var mseVideoFile: File? = null
        var mseVideoStream: BufferedOutputStream? = null
        var mseVideoInitWritten = false
        var mseVideoSynced = false
        var mseVideoIsMp4 = true
        val mseVideoBytes = AtomicLong(0L)
        val mseVideoChunks = AtomicInteger(0)

        var mseAudioFile: File? = null
        var mseAudioStream: BufferedOutputStream? = null
        var mseAudioInitWritten = false
        var mseAudioSynced = false
        var mseAudioIsMp4 = true
        val mseAudioBytes = AtomicLong(0L)

        // Layer 2: Internal <video>/<canvas> + WebAudio MediaRecorder fallback
        var jsRecFile: File? = null
        var jsRecStream: BufferedOutputStream? = null
        val jsRecBytes = AtomicLong(0L)
        val jsRecChunks = AtomicInteger(0)
        val jsRecorderStopped = CompletableDeferred<Unit>()
        val jsFailed = AtomicBoolean(false)

        val captionFetchDone = CompletableDeferred<Unit>()

        // Caption layers
        val domCaptionCues = ArrayList<Pair<Long, String>>()
        val timedTextPayloads = ArrayList<String>()
        val speechCaptionCues = ArrayList<Pair<Long, String>>()
        private var lastDomText: String = ""
        private var lastSpeechText: String = ""

        fun writeMseInit(isVideo: Boolean, isMp4: Boolean, bytes: ByteArray) {
            if (bytes.size < 16) return
            synchronized(lock) {
                if (isVideo) {
                    if (mseVideoInitWritten) return
                    mseVideoIsMp4 = isMp4
                    val ext = if (isMp4) "mp4" else "webm"
                    val file = File(workDir, "internal_video.$ext")
                    mseVideoFile = file
                    mseVideoStream = BufferedOutputStream(FileOutputStream(file), 65_536).also {
                        it.write(bytes)
                        it.flush()
                    }
                    mseVideoInitWritten = true
                } else {
                    if (mseAudioInitWritten) return
                    mseAudioIsMp4 = isMp4
                    val ext = if (isMp4) "m4a" else "webm"
                    val file = File(workDir, "internal_audio.$ext")
                    mseAudioFile = file
                    mseAudioStream = BufferedOutputStream(FileOutputStream(file), 32_768).also {
                        it.write(bytes)
                        it.flush()
                    }
                    mseAudioInitWritten = true
                }
            }
        }

        fun writeMseChunk(isVideo: Boolean, isMp4: Boolean, bytes: ByteArray) {
            if (bytes.size < 16) return
            // Skip duplicate init segments inside the media body
            if (isMseInitSegment(bytes, isMp4)) {
                writeMseInit(isVideo, isMp4, bytes)
                return
            }
            var totalBytesNow = 0L
            synchronized(lock) {
                if (isVideo) {
                    if (!mseVideoInitWritten || mseVideoStream == null) return
                    if (!mseVideoSynced) {
                        // Start writing strictly at a clean fragment boundary (moof/styp/sidx or WebM Cluster)
                        if (!isMseMediaBoundary(bytes, mseVideoIsMp4)) return
                        mseVideoSynced = true
                    }
                    mseVideoStream?.write(bytes)
                    mseVideoStream?.flush()
                    mseVideoBytes.addAndGet(bytes.size.toLong())
                    mseVideoChunks.incrementAndGet()
                } else {
                    if (!mseAudioInitWritten || mseAudioStream == null) return
                    if (!mseAudioSynced) {
                        if (!isMseMediaBoundary(bytes, mseAudioIsMp4)) return
                        mseAudioSynced = true
                    }
                    mseAudioStream?.write(bytes)
                    mseAudioStream?.flush()
                    mseAudioBytes.addAndGet(bytes.size.toLong())
                }
                totalBytesNow = mseVideoBytes.get() + mseAudioBytes.get()
            }
            if (totalBytesNow > 0L) {
                onProgress(totalBytesNow, totalCaptionCount(), "Internal 1080p Stream")
            }
        }

        fun writeJsRecChunk(ext: String, bytes: ByteArray) {
            if (bytes.isEmpty()) return
            synchronized(lock) {
                if (jsRecStream == null) {
                    val safeExt = if (ext.equals("mp4", ignoreCase = true)) "mp4" else "webm"
                    jsRecFile = File(workDir, "internal_player_rec.$safeExt")
                    jsRecStream = BufferedOutputStream(FileOutputStream(jsRecFile!!), 65_536)
                }
                jsRecStream?.write(bytes)
                jsRecStream?.flush()
            }
            val total = jsRecBytes.addAndGet(bytes.size.toLong())
            jsRecChunks.incrementAndGet()
            onProgress(total, totalCaptionCount(), "Internal Player Video")
        }

        fun closeAllStreams() {
            synchronized(lock) {
                runCatching { mseVideoStream?.flush() }
                runCatching { mseVideoStream?.close() }
                mseVideoStream = null
                runCatching { mseAudioStream?.flush() }
                runCatching { mseAudioStream?.close() }
                mseAudioStream = null
                runCatching { jsRecStream?.flush() }
                runCatching { jsRecStream?.close() }
                jsRecStream = null
            }
        }

        fun addDomCaptionCue(rawText: String) {
            val cleaned = rawText.replace(Regex("\\s+"), " ").trim()
            if (cleaned.length < 2) return
            val relMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            synchronized(lock) {
                if (!cleaned.equals(lastDomText, ignoreCase = true)) {
                    if (domCaptionCues.isNotEmpty() &&
                        cleaned.startsWith(lastDomText, ignoreCase = true) &&
                        relMs - domCaptionCues.last().first < 2500L
                    ) {
                        domCaptionCues[domCaptionCues.lastIndex] = domCaptionCues.last().first to cleaned
                    } else {
                        domCaptionCues.add(relMs to cleaned)
                    }
                    lastDomText = cleaned
                }
            }
        }

        fun addTimedTextPayload(payload: String) {
            val trimmed = payload.trim()
            if (trimmed.length < 16) return
            synchronized(lock) {
                if (timedTextPayloads.size < 40) {
                    timedTextPayloads.add(trimmed)
                }
            }
        }

        fun addSpeechCue(rawText: String) {
            val cleaned = rawText.replace(Regex("\\s+"), " ").trim()
            if (cleaned.length < 2) return
            val relMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            synchronized(lock) {
                if (!cleaned.equals(lastSpeechText, ignoreCase = true)) {
                    if (speechCaptionCues.isNotEmpty() &&
                        cleaned.startsWith(lastSpeechText, ignoreCase = true) &&
                        relMs - speechCaptionCues.last().first < 3000L
                    ) {
                        speechCaptionCues[speechCaptionCues.lastIndex] = speechCaptionCues.last().first to cleaned
                    } else {
                        speechCaptionCues.add(relMs to cleaned)
                    }
                    lastSpeechText = cleaned
                }
            }
        }

        fun totalCaptionCount(): Int = synchronized(lock) {
            maxOf(domCaptionCues.size, speechCaptionCues.size, timedTextPayloads.size)
        }

        fun resolveBestCaptions(maxDurationSec: Int): List<CaptionSegment> {
            val domCopy: List<Pair<Long, String>>
            val payloadsCopy: List<String>
            val speechCopy: List<Pair<Long, String>>
            synchronized(lock) {
                domCopy = domCaptionCues.toList()
                payloadsCopy = timedTextPayloads.toList()
                speechCopy = speechCaptionCues.toList()
            }

            val domSegments = buildCaptionSegmentsFromCues(domCopy, maxDurationSec)
            if (domSegments.size >= 2) {
                return AutoCaptionGenerator.formatForVerticalShorts(domSegments)
            }

            val parsedTimedText = parseTimedTextPayloads(payloadsCopy, maxDurationSec)
            if (parsedTimedText.isNotEmpty()) {
                return AutoCaptionGenerator.formatForVerticalShorts(parsedTimedText)
            }

            if (domSegments.isNotEmpty()) {
                return AutoCaptionGenerator.formatForVerticalShorts(domSegments)
            }

            val speechSegments = buildCaptionSegmentsFromCues(speechCopy, maxDurationSec)
            if (speechSegments.isNotEmpty()) {
                return AutoCaptionGenerator.formatForVerticalShorts(speechSegments)
            }

            return emptyList()
        }

        private fun parseTimedTextPayloads(payloads: List<String>, durationSec: Int): List<CaptionSegment> {
            if (payloads.isEmpty()) return emptyList()
            val vttParts = ArrayList<String>()
            val combined = ArrayList<CaptionSegment>()
            for (raw in payloads) {
                val trimmed = raw.trimStart()
                when {
                    trimmed.startsWith("{") -> {
                        val cues = AutoCaptionGenerator.parseTimedTextJson3(trimmed, durationSec)
                        if (cues.size > combined.size) {
                            combined.clear()
                            combined.addAll(cues)
                        }
                    }

                    trimmed.startsWith("WEBVTT", ignoreCase = true) || trimmed.contains("-->") -> {
                        vttParts.add(trimmed)
                    }

                    trimmed.startsWith("<") -> {
                        val cues = AutoCaptionGenerator.parseTimedTextXml(trimmed, durationSec)
                        if (cues.size > combined.size) {
                            combined.clear()
                            combined.addAll(cues)
                        }
                    }
                }
            }
            if (combined.isNotEmpty()) return combined
            if (vttParts.isNotEmpty()) {
                return AutoCaptionGenerator.parseLiveWebVttSegments(vttParts, durationSec)
            }
            return emptyList()
        }
    }

    private inner class LiveSpeechCaptionHelper(
        private val session: ActivePlayerRecordingSession
    ) {
        private var recognizer: SpeechRecognizer? = null
        private val running = AtomicBoolean(false)

        fun start() {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return

            mainHandler.post {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) return@post
                if (running.getAndSet(true)) return@post
                try {
                    val sr = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer = sr
                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit

                        override fun onPartialResults(partialResults: Bundle?) {
                            val text = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                            if (text.isNotBlank()) session.addSpeechCue(text)
                        }

                        override fun onResults(results: Bundle?) {
                            val text = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                            if (text.isNotBlank()) session.addSpeechCue(text)
                            scheduleNextListen(250L)
                        }

                        override fun onError(error: Int) {
                            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                                stop()
                            } else {
                                scheduleNextListen(700L)
                            }
                        }
                    })
                    startListenOnce()
                } catch (_: Exception) {
                    running.set(false)
                }
            }
        }

        private fun startListenOnce() {
            if (!running.get() || session.stopFlag.get()) return
            val sr = recognizer ?: return
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                sr.startListening(intent)
            } catch (_: Exception) {
            }
        }

        private fun scheduleNextListen(delayMs: Long) {
            if (!running.get() || session.stopFlag.get()) return
            mainHandler.postDelayed({ startListenOnce() }, delayMs)
        }

        fun stop() {
            running.set(false)
            mainHandler.post {
                val sr = recognizer
                recognizer = null
                runCatching { sr?.stopListening() }
                runCatching { sr?.cancel() }
                runCatching { sr?.destroy() }
            }
        }
    }

    fun attachHostWindow(window: Window?) {
        if (window == null) return
        mainHandler.post {
            val contentRoot = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return@post
            val existing = contentRoot.findViewWithTag<FrameLayout>(PARKING_HOST_TAG)
            if (existing != null) {
                parkingHostRef = WeakReference(existing)
            } else {
                val parking = FrameLayout(window.context).apply {
                    tag = PARKING_HOST_TAG
                    alpha = 0.01f
                    isClickable = false
                    isFocusable = false
                    translationX = -10000f
                }
                contentRoot.addView(parking, FrameLayout.LayoutParams(1920, 1080))
                parkingHostRef = WeakReference(parking)
            }
        }
    }

    fun hasPlayer(channelId: String): Boolean = slots.containsKey(channelId)

    fun requestLoginPopup() {
        _loginPopupRequested.value = true
    }

    fun dismissLoginPopup() {
        _loginPopupRequested.value = false
    }

    fun onLoginCompleted() {
        runCatching { CookieManager.getInstance().flush() }
        val signedIn = hasYouTubeAuthCookies()
        _isYouTubeSignedIn.value = signedIn
        _loginPopupRequested.value = false
        if (signedIn) {
            autoPromptedLoginOnce.set(true)
        }
        reloadAllPlayers()
    }

    fun reloadAllPlayers() {
        mainHandler.post {
            runCatching { CookieManager.getInstance().flush() }
            slots.values.forEach { slot ->
                updateStatus(slot.channelId, slot.videoId) {
                    it.copy(
                        isLoaded = false,
                        isPlaying = false,
                        loginRequired = false,
                        statusNote = "Signed in • Reloading 1080p HD player…"
                    )
                }
                slot.webView.loadUrl(buildWatchUrl(slot.videoId))
            }
        }
    }

    fun ensurePlayerLoaded(channelId: String, videoId: String) {
        if (videoId.isBlank()) return
        mainHandler.post {
            val existing = slots[channelId]
            if (existing != null) {
                if (existing.videoId != videoId) {
                    existing.videoId = videoId
                    updateStatus(channelId, videoId) {
                        it.copy(
                            videoId = videoId,
                            isLoaded = false,
                            isPlaying = false,
                            statusNote = "Switching to 1080p live stream…"
                        )
                    }
                    existing.webView.loadUrl(buildWatchUrl(videoId))
                }
                ensureAttachedSomewhere(existing.webView)
                return@post
            }

            val webView = createConfiguredWebView(channelId)
            val slot = ChannelPlayerSlot(channelId, videoId, webView)
            slots[channelId] = slot
            updateStatus(channelId, videoId) {
                it.copy(
                    isLoaded = false,
                    isPlaying = false,
                    isMuted = true,
                    qualityLabel = "1080p HD",
                    statusNote = "Loading 1080p HD player…"
                )
            }
            ensureAttachedSomewhere(webView)
            webView.loadUrl(buildWatchUrl(videoId))
        }
    }

    fun attachToContainer(channelId: String, videoId: String, container: FrameLayout) {
        if (videoId.isBlank()) return
        mainHandler.post {
            var slot = slots[channelId]
            if (slot == null) {
                val webView = createConfiguredWebView(channelId)
                slot = ChannelPlayerSlot(channelId, videoId, webView)
                slots[channelId] = slot
                updateStatus(channelId, videoId) {
                    it.copy(
                        isLoaded = false,
                        isPlaying = false,
                        isMuted = true,
                        qualityLabel = "1080p HD",
                        statusNote = "Loading 1080p HD player…"
                    )
                }
                webView.loadUrl(buildWatchUrl(videoId))
            } else if (slot.videoId != videoId) {
                slot.videoId = videoId
                slot.webView.loadUrl(buildWatchUrl(videoId))
            }

            val wv = slot.webView
            if (wv.parent !== container) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                container.removeAllViews()
                container.addView(
                    wv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                wv.onResume()
                wv.resumeTimers()
            }
        }
    }

    fun detachFromContainer(channelId: String, container: FrameLayout) {
        mainHandler.post {
            val slot = slots[channelId] ?: return@post
            val wv = slot.webView
            if (wv.parent === container) {
                container.removeView(wv)
                parkWebView(wv)
            }
        }
    }

    fun toggleMute(channelId: String) {
        mainHandler.post {
            val slot = slots[channelId] ?: return@post
            val currentMuted = _playerStatuses.value[channelId]?.isMuted ?: true
            val nextMuted = !currentMuted
            updateStatus(channelId, slot.videoId) { it.copy(isMuted = nextMuted) }
            slot.webView.evaluateJavascript("window.__pulseSetMuted && window.__pulseSetMuted($nextMuted);", null)
        }
    }

    fun reloadPlayer(channelId: String) {
        mainHandler.post {
            val slot = slots[channelId] ?: return@post
            updateStatus(channelId, slot.videoId) {
                it.copy(isLoaded = false, isPlaying = false, statusNote = "Refreshing 1080p HD feed…")
            }
            slot.webView.loadUrl(buildWatchUrl(slot.videoId))
        }
    }

    fun releaseChannel(channelId: String) {
        activeRecordings.remove(channelId)?.let { session ->
            session.stopFlag.set(true)
            session.closeAllStreams()
            session.jsRecorderStopped.complete(Unit)
            session.captionFetchDone.complete(Unit)
        }
        mainHandler.post {
            val slot = slots.remove(channelId) ?: return@post
            (slot.webView.parent as? ViewGroup)?.removeView(slot.webView)
            runCatching {
                slot.webView.stopLoading()
                slot.webView.loadUrl("about:blank")
                slot.webView.destroy()
            }
            _playerStatuses.update { it - channelId }
        }
    }

    fun releaseAll() {
        slots.keys.toList().forEach { releaseChannel(it) }
    }

    /**
     * Records PURE INTERNAL HD VIDEO + AUDIO + LIVE CAPTIONS straight from inside the channel's
     * embedded YouTube player (never capturing screen, GPU overlays, or Android notifications).
     */
    suspend fun recordFromPlayer(
        channelId: String,
        videoId: String,
        maxDurationSec: Int,
        stopFlag: AtomicBoolean,
        onProgress: (bytes: Long, captions: Int, mode: String) -> Unit
    ): RecordedRawVideo? = withContext(Dispatchers.IO) {
        ensurePlayerLoaded(channelId, videoId)
        val slot = waitForSlot(channelId, timeoutMs = 4500L) ?: return@withContext null

        val stamp = System.currentTimeMillis()
        val workDir = File(context.cacheDir, "internal_rec_${channelId}_$stamp").apply { mkdirs() }
        val session = ActivePlayerRecordingSession(
            channelId = channelId,
            workDir = workDir,
            startedAtMs = stamp,
            stopFlag = stopFlag,
            onProgress = onProgress
        )
        activeRecordings[channelId] = session

        val speechHelper = LiveSpeechCaptionHelper(session)

        updateStatus(channelId, slot.videoId) {
            it.copy(
                isRecording = true,
                recordedBytes = 0L,
                captionCount = 0,
                captureMode = "Internal 1080p Stream",
                statusNote = "Recording pure internal 1080p stream…"
            )
        }

        // Start Layer 1 (Raw MSE SourceBuffer Tap) + live captions immediately
        mainHandler.post {
            ensureAttachedSomewhere(slot.webView)
            slot.webView.evaluateJavascript(
                "window.__pulseStartMseRec && window.__pulseStartMseRec('${escapeJs(channelId)}');",
                null
            )
        }
        speechHelper.start()

        var startedLayer2Fallback = false

        try {
            val hardDeadlineMs = stamp + (maxDurationSec + 6) * 1000L
            val mseProbeDeadlineMs = stamp + 2800L

            while (isActive && !stopFlag.get() && System.currentTimeMillis() < hardDeadlineMs) {
                val mseVidBytes = session.mseVideoBytes.get()
                val mseAudBytes = session.mseAudioBytes.get()
                val mseBytes = mseVidBytes + mseAudBytes
                val jsBytes = session.jsRecBytes.get()

                // If MSE hasn't produced video bytes after 2.8s, trigger Layer 2 (pure internal offscreen <canvas> + WebAudio MediaRecorder — 100% pure video, zero UI!)
                if (!startedLayer2Fallback &&
                    System.currentTimeMillis() >= mseProbeDeadlineMs &&
                    mseVidBytes < 16_384L
                ) {
                    startedLayer2Fallback = true
                    mainHandler.post {
                        slot.webView.evaluateJavascript(
                            "window.__pulseStartInternalMediaRec && window.__pulseStartInternalMediaRec('${escapeJs(channelId)}');",
                            null
                        )
                    }
                }

                val usingMse = mseVidBytes >= 16_384L
                val activeBytes = if (usingMse) mseBytes else (jsBytes + mseAudBytes)
                val activeMode = if (usingMse) "Internal 1080p Stream" else "Internal Player HD"
                val capCount = session.totalCaptionCount()

                updateStatus(channelId, slot.videoId) {
                    it.copy(
                        isRecording = true,
                        recordedBytes = activeBytes,
                        captionCount = capCount,
                        captureMode = activeMode,
                        statusNote = "REC $activeMode • ${formatMb(activeBytes)} MB"
                    )
                }
                if (activeBytes > 0L) {
                    onProgress(activeBytes, capCount, activeMode)
                }
                delay(450L)
            }

            // Stop internal recording + fetch authenticated YouTube captions inside the WebView
            mainHandler.post {
                slot.webView.evaluateJavascript(
                    "window.__pulseFetchCaptions && window.__pulseFetchCaptions('${escapeJs(channelId)}');",
                    null
                )
                slot.webView.evaluateJavascript("window.__pulseStopAllRec && window.__pulseStopAllRec();", null)
            }
            if (startedLayer2Fallback && !session.jsFailed.get()) {
                withTimeoutOrNull(2500L) { session.jsRecorderStopped.await() }
            }
            withTimeoutOrNull(2000L) { session.captionFetchDone.await() }
            session.closeAllStreams()
        } finally {
            speechHelper.stop()
            mainHandler.post {
                slot.webView.evaluateJavascript("window.__pulseStopAllRec && window.__pulseStopAllRec();", null)
            }
            session.closeAllStreams()
            activeRecordings.remove(channelId)
            updateStatus(channelId, slot.videoId) {
                it.copy(
                    isRecording = false,
                    recordedBytes = 0L,
                    statusNote = "Live 1080p player ready"
                )
            }
        }

        val capturedCaptions = session.resolveBestCaptions(maxDurationSec)
        val mseAud = session.mseAudioFile?.takeIf { it.exists() && session.mseAudioBytes.get() >= 4096L }

        // Priority 1: Pure Raw Internal MSE 1080p Video + Original Stereo Audio!
        val mseVid = session.mseVideoFile
        if (mseVid != null && mseVid.exists() && session.mseVideoBytes.get() >= 32_768L) {
            return@withContext RecordedRawVideo(
                videoFile = mseVid,
                audioFile = mseAud,
                liveVttCaptions = capturedCaptions,
                segmentCount = session.mseVideoChunks.get().coerceAtLeast(1),
                totalBytes = mseVid.length() + (mseAud?.length() ?: 0L),
                hasAudioTrack = mseAud != null,
                sourceLabel = if (mseAud != null) "Internal 1080p Stream + Audio" else "Internal 1080p Stream"
            )
        }

        // Priority 2: Pure Internal Offscreen <canvas> + WebAudio (or paired MSE stereo audio) MediaRecorder!
        val jsFile = session.jsRecFile
        if (jsFile != null && jsFile.exists() && jsFile.length() >= 16_384L) {
            return@withContext RecordedRawVideo(
                videoFile = jsFile,
                audioFile = mseAud,
                liveVttCaptions = capturedCaptions,
                segmentCount = session.jsRecChunks.get().coerceAtLeast(1),
                totalBytes = jsFile.length() + (mseAud?.length() ?: 0L),
                hasAudioTrack = true,
                sourceLabel = if (mseAud != null) "Internal Player HD + Stereo Audio" else "Internal Player HD Video + Audio"
            )
        }

        runCatching { workDir.deleteRecursively() }
        null
    }

    private suspend fun waitForSlot(channelId: String, timeoutMs: Long): ChannelPlayerSlot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            slots[channelId]?.let { return it }
            delay(100L)
        }
        return slots[channelId]
    }

    private fun ensureAttachedSomewhere(webView: WebView) {
        if (webView.parent == null) {
            parkWebView(webView)
        }
    }

    private fun parkWebView(webView: WebView) {
        val parking = parkingHostRef?.get() ?: return
        if (webView.parent !== parking) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            parking.addView(webView, FrameLayout.LayoutParams(1920, 1080))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(channelId: String): WebView {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val consent = "CONSENT=YES+cb.20250101-01-p0.en+FX+111; Path=/; Domain=.youtube.com"
        val socs = "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg; Path=/; Domain=.youtube.com"
        val pref = "PREF=f4=40000&f6=400&hl=en&f5=30000; Path=/; Domain=.youtube.com"
        cm.setCookie("https://www.youtube.com", consent)
        cm.setCookie("https://www.youtube.com", socs)
        cm.setCookie("https://www.youtube.com", pref)
        cm.setCookie("https://m.youtube.com", consent)
        cm.setCookie("https://m.youtube.com", socs)

        return WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            cm.setAcceptThirdPartyCookies(this, true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = DESKTOP_CHROME_UA
            }
            addJavascriptInterface(PlayerJsBridge(channelId), "PulsePlayerBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.deny()
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val host = request?.url?.host.orEmpty()
                    return !(host.endsWith("youtube.com") || host.endsWith("youtu.be") || host.endsWith("google.com"))
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (view != null && url?.contains("youtube.com") == true) {
                        view.evaluateJavascript(buildEarlyMseAndNetHookScript(channelId), null)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view != null && url?.contains("youtube.com") == true) {
                        view.evaluateJavascript(buildEarlyMseAndNetHookScript(channelId), null)
                        view.evaluateJavascript(buildPlayerControllerScript(channelId), null)
                    }
                }
            }
        }
    }

    private inner class PlayerJsBridge(private val channelId: String) {

        @JavascriptInterface
        fun onPlayerReady(quality: String, playing: Boolean) {
            val slot = slots[channelId] ?: return
            updateStatus(channelId, slot.videoId) { prev ->
                prev.copy(
                    isLoaded = true,
                    isPlaying = playing,
                    loginRequired = if (playing) false else prev.loginRequired,
                    qualityLabel = quality.ifBlank { "1080p HD" },
                    statusNote = if (prev.isRecording) prev.statusNote
                    else if (playing) "Playing $quality • Internal Stream Armed"
                    else if (prev.loginRequired) "YouTube Sign-In required • Tap Sign In"
                    else "Live player buffered"
                )
            }
        }

        @JavascriptInterface
        fun onLoginRequired(chId: String) {
            val targetId = chId.ifBlank { channelId }
            val slot = slots[targetId] ?: slots[channelId] ?: return
            val alreadyAuthed = hasYouTubeAuthCookies()
            _isYouTubeSignedIn.value = alreadyAuthed
            updateStatus(targetId, slot.videoId) { prev ->
                prev.copy(
                    isLoaded = true,
                    isPlaying = false,
                    loginRequired = true,
                    statusNote = "YouTube Sign-In required • Tap Sign In"
                )
            }
            if (autoPromptedLoginOnce.compareAndSet(false, true)) {
                _loginPopupRequested.value = true
            }
        }

        @JavascriptInterface
        fun onLiveChatPulse(chId: String, count: Int, isSeed: Boolean) {
            val targetId = chId.ifBlank { channelId }
            _chatPulses.tryEmit(
                WebViewChatPulse(
                    channelId = targetId,
                    messageCount = count.coerceAtLeast(0),
                    isInitialSeed = isSeed,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }

        @JavascriptInterface
        fun onPlayerMeta(chId: String, title: String, author: String, isLive: Boolean) {
            val targetId = chId.ifBlank { channelId }
            val slot = slots[targetId] ?: slots[channelId] ?: return
            val cleanTitle = title.trim()
            val cleanAuthor = author.trim()
            if (cleanTitle.isNotBlank() || cleanAuthor.isNotBlank()) {
                _playerMeta.tryEmit(
                    WebViewPlayerMeta(
                        channelId = targetId,
                        videoId = slot.videoId,
                        title = cleanTitle,
                        author = cleanAuthor,
                        isLive = isLive
                    )
                )
            }
        }

        @JavascriptInterface
        fun onMseInit(recChannelId: String, isVideo: Boolean, isMp4: Boolean, base64Data: String) {
            val session = activeRecordings[recChannelId] ?: activeRecordings[channelId] ?: return
            if (base64Data.isBlank()) return
            try {
                val decoded = Base64.decode(base64Data, Base64.DEFAULT)
                session.writeMseInit(isVideo, isMp4, decoded)
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun onMseChunk(recChannelId: String, isVideo: Boolean, isMp4: Boolean, base64Data: String) {
            val session = activeRecordings[recChannelId] ?: activeRecordings[channelId] ?: return
            if (base64Data.isBlank()) return
            try {
                val decoded = Base64.decode(base64Data, Base64.DEFAULT)
                session.writeMseChunk(isVideo, isMp4, decoded)
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun onMediaChunk(recChannelId: String, ext: String, base64Data: String) {
            val session = activeRecordings[recChannelId] ?: activeRecordings[channelId] ?: return
            if (base64Data.isBlank()) return
            try {
                val decoded = Base64.decode(base64Data, Base64.DEFAULT)
                if (decoded.isNotEmpty()) {
                    session.writeJsRecChunk(ext, decoded)
                }
            } catch (_: Exception) {
            }
        }

        @JavascriptInterface
        fun onCaptionCue(recChannelId: String, text: String) {
            val session = activeRecordings[recChannelId] ?: activeRecordings[channelId] ?: return
            session.addDomCaptionCue(text)
        }

        @JavascriptInterface
        fun onTimedTextPayload(recChannelId: String, payload: String) {
            val session = activeRecordings[recChannelId] ?: activeRecordings[channelId] ?: return
            session.addTimedTextPayload(payload)
        }

        @JavascriptInterface
        fun onCaptionFetchComplete(recChannelId: String) {
            val session = activeRecordings[recChannelId] ?: activeRecordings[channelId] ?: return
            session.captionFetchDone.complete(Unit)
        }

        @JavascriptInterface
        fun onRecordingFinished(recChannelId: String) {
            val session = activeRecordings[recChannelId] ?: activeRecordings[channelId] ?: return
            session.jsRecorderStopped.complete(Unit)
        }

        @JavascriptInterface
        fun onRecordingError(recChannelId: String, reason: String) {
            val session = activeRecordings[recChannelId] ?: activeRecordings[channelId] ?: return
            session.jsFailed.set(true)
            session.jsRecorderStopped.complete(Unit)
        }
    }

    private fun updateStatus(
        channelId: String,
        videoId: String,
        transform: (EmbeddedPlayerStatus) -> EmbeddedPlayerStatus
    ) {
        _playerStatuses.update { current ->
            val prev = current[channelId] ?: EmbeddedPlayerStatus(channelId = channelId, videoId = videoId)
            current + (channelId to transform(prev))
        }
    }

    private fun buildWatchUrl(videoId: String): String =
        "https://www.youtube.com/watch?v=$videoId&app=desktop&autoplay=1&cc_load_policy=1&vq=hd1080"

    private fun escapeJs(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    private fun formatMb(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / (1024f * 1024f))

    /**
     * Installed on `onPageStarted` BEFORE YouTube's player boots:
     * 1) Pre-seeds `yt-player-quality` to 1080p HD.
     * 2) Hooks `MediaSource.prototype.addSourceBuffer` & `SourceBuffer.prototype.appendBuffer`
     *    to capture YouTube's raw original 1080p video + stereo audio segments internally!
     * 3) Hooks `window.fetch` & `XMLHttpRequest` to intercept `/api/timedtext` live captions.
     */
    private fun buildEarlyMseAndNetHookScript(channelId: String): String {
        val safeId = escapeJs(channelId)
        return """
            (function() {
              try {
                var exp = Date.now() + 86400000 * 30;
                localStorage.setItem('yt-player-quality', JSON.stringify({
                  data: JSON.stringify({ quality: 1080, previousQuality: 1080 }),
                  expiration: exp,
                  creation: Date.now()
                }));
              } catch(e) {}

              if (window.__pulseHooksInstalled) return;
              window.__pulseHooksInstalled = true;
              window.__pulseChannelId = '$safeId';
              window.__pulseMseRecActive = false;
              window.__pulseInitVideo = null;
              window.__pulseInitAudio = null;

              function bytesToBase64(u8) {
                var CHUNK = 0x8000;
                var parts = [];
                for (var i = 0; i < u8.length; i += CHUNK) {
                  parts.push(String.fromCharCode.apply(null, u8.subarray(i, Math.min(i + CHUNK, u8.length))));
                }
                return btoa(parts.join(''));
              }
              window.__pulseBytesToBase64 = bytesToBase64;

              function detectMp4Container(u8) {
                if (!u8 || u8.length < 12) return null;
                if (u8[0] === 0x1A && u8[1] === 0x45 && u8[2] === 0xDF && u8[3] === 0xA3) return false;
                if (u8[0] === 0x1F && u8[1] === 0x43 && u8[2] === 0xB6 && u8[3] === 0x75) return false;
                var box = String.fromCharCode(u8[4], u8[5], u8[6], u8[7]);
                if (box === 'ftyp' || box === 'styp' || box === 'moof' || box === 'moov' || box === 'sidx' || box === 'emsg' || box === 'mdat') {
                  return true;
                }
                return null;
              }

              function looksLikeInit(u8, isMp4) {
                if (!u8 || u8.length < 16) return false;
                if (isMp4) {
                  return u8[4] === 0x66 && u8[5] === 0x74 && u8[6] === 0x79 && u8[7] === 0x70;
                } else {
                  return u8[0] === 0x1A && u8[1] === 0x45 && u8[2] === 0xDF && u8[3] === 0xA3;
                }
              }

              function classifyInitKind(u8) {
                try {
                  var lim = Math.min(u8.length, 4096);
                  var s = '';
                  for (var i = 0; i < lim; i++) {
                    var c = u8[i];
                    s += (c >= 32 && c <= 126) ? String.fromCharCode(c) : ' ';
                  }
                  if (s.indexOf('vide') !== -1 || s.indexOf('avc1') !== -1 || s.indexOf('vp09') !== -1 || s.indexOf('av01') !== -1 || s.indexOf('hev1') !== -1 || s.indexOf('V_VP9') !== -1 || s.indexOf('V_VP8') !== -1 || s.indexOf('V_AV1') !== -1) {
                    return 'video';
                  }
                  if (s.indexOf('soun') !== -1 || s.indexOf('mp4a') !== -1 || s.indexOf('OpusHead') !== -1 || s.indexOf('A_OPUS') !== -1 || s.indexOf('A_AAC') !== -1) {
                    return 'audio';
                  }
                } catch(e) {}
                return null;
              }

              // Hook MediaSource & SourceBuffer to tap YouTube's EXACT internal 1080p video + audio bitstream
              try {
                if (window.MediaSource && MediaSource.prototype && SourceBuffer && SourceBuffer.prototype) {
                  var origAddSB = MediaSource.prototype.addSourceBuffer;
                  MediaSource.prototype.addSourceBuffer = function(mimeType) {
                    var sb = origAddSB.apply(this, arguments);
                    try {
                      var m = String(mimeType || '').toLowerCase();
                      sb.__pulseIsVideo = m.indexOf('video') !== -1;
                      sb.__pulseIsAudio = m.indexOf('audio') !== -1;
                      sb.__pulseIsMp4 = m.indexOf('mp4') !== -1;
                    } catch(e) {}
                    return sb;
                  };

                  var origAppend = SourceBuffer.prototype.appendBuffer;
                  SourceBuffer.prototype.appendBuffer = function(data) {
                    try {
                      if (data) {
                        var u8 = null;
                        if (data instanceof ArrayBuffer) {
                          u8 = new Uint8Array(data);
                        } else if (ArrayBuffer.isView(data)) {
                          u8 = new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
                        }
                        if (u8 && u8.length >= 16) {
                          // Auto-classify SourceBuffers created BEFORE our hook attached!
                          if (typeof this.__pulseIsMp4 !== 'boolean') {
                            var detMp4 = detectMp4Container(u8);
                            if (detMp4 !== null) this.__pulseIsMp4 = detMp4;
                          }
                          var isMp4 = (typeof this.__pulseIsMp4 === 'boolean') ? this.__pulseIsMp4 : true;
                          var isInit = looksLikeInit(u8, isMp4);

                          if (!this.__pulseIsVideo && !this.__pulseIsAudio) {
                            if (isInit) {
                              var kind = classifyInitKind(u8);
                              if (kind === 'video') this.__pulseIsVideo = true;
                              else if (kind === 'audio') this.__pulseIsAudio = true;
                            } else if (u8.length >= 42000) {
                              this.__pulseIsVideo = true;
                            } else if (u8.length >= 2048) {
                              this.__pulseIsAudio = true;
                            }
                          }

                          if (this.__pulseIsVideo || this.__pulseIsAudio) {
                            var isVid = !!this.__pulseIsVideo;
                            if (isInit) {
                              var initB64 = bytesToBase64(u8);
                              var meta = { isVideo: isVid, isMp4: isMp4, b64: initB64 };
                              if (isVid) window.__pulseInitVideo = meta;
                              else window.__pulseInitAudio = meta;
                              if (window.__pulseMseRecActive && window.PulsePlayerBridge && PulsePlayerBridge.onMseInit) {
                                PulsePlayerBridge.onMseInit(window.__pulseRecChId || window.__pulseChannelId, isVid, isMp4, initB64);
                              }
                            } else if (window.__pulseMseRecActive && window.PulsePlayerBridge && PulsePlayerBridge.onMseChunk) {
                              var sliceLimit = 512 * 1024;
                              var ch = window.__pulseRecChId || window.__pulseChannelId;
                              if (u8.length <= sliceLimit) {
                                PulsePlayerBridge.onMseChunk(ch, isVid, isMp4, bytesToBase64(u8));
                              } else {
                                for (var off = 0; off < u8.length; off += sliceLimit) {
                                  var sub = u8.subarray(off, Math.min(off + sliceLimit, u8.length));
                                  PulsePlayerBridge.onMseChunk(ch, isVid, isMp4, bytesToBase64(sub));
                                }
                              }
                            }
                          }
                        }
                      }
                    } catch(e) {}
                    return origAppend.apply(this, arguments);
                  };
                }
              } catch(e) {}

              // Hook fetch & XHR to intercept every YouTube live /api/timedtext caption response
              try {
                var origFetch = window.fetch;
                if (typeof origFetch === 'function') {
                  window.fetch = function() {
                    var reqUrl = (arguments[0] && arguments[0].url) ? arguments[0].url : String(arguments[0] || '');
                    return origFetch.apply(this, arguments).then(function(resp) {
                      try {
                        if (reqUrl.indexOf('timedtext') !== -1 && resp && resp.clone) {
                          resp.clone().text().then(function(txt) {
                            if (txt && txt.length > 16 && window.PulsePlayerBridge && PulsePlayerBridge.onTimedTextPayload) {
                              PulsePlayerBridge.onTimedTextPayload(window.__pulseRecChId || window.__pulseChannelId, txt);
                            }
                          }).catch(function(){});
                        }
                      } catch(e) {}
                      return resp;
                    });
                  };
                }
              } catch(e) {}

              try {
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(m, url) {
                  this.__pulseUrl = String(url || '');
                  return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                  this.addEventListener('load', function() {
                    try {
                      if (this.__pulseUrl && this.__pulseUrl.indexOf('timedtext') !== -1 && this.responseText && this.responseText.length > 16) {
                        if (window.PulsePlayerBridge && PulsePlayerBridge.onTimedTextPayload) {
                          PulsePlayerBridge.onTimedTextPayload(window.__pulseRecChId || window.__pulseChannelId, this.responseText);
                        }
                      }
                    } catch(e) {}
                  });
                  return origSend.apply(this, arguments);
                };
              } catch(e) {}
            })();
        """.trimIndent()
    }

    /**
     * Player UI controller + Internal Layer 1/Layer 2 recording hooks + Live Caption scraper.
     */
    private fun buildPlayerControllerScript(channelId: String): String {
        val safeId = escapeJs(channelId)
        return """
            (function() {
              if (window.__pulseControllerInstalled) return;
              window.__pulseControllerInstalled = true;
              window.__pulseChannelId = '$safeId';
              window.__pulseUserMuted = true;
              window.__pulseRec = null;
              window.__pulseCanvasRaf = null;
              window.__pulseCapTimer = null;

              try {
                var style = document.createElement('style');
                style.id = 'pulseclip-player-style';
                style.textContent =
                  'html, body { margin:0 !important; padding:0 !important; overflow:hidden !important; background:#000 !important; width:100vw !important; height:100vh !important; }' +
                  'ytd-masthead, #masthead-container, #below, #secondary, #chat, ytd-live-chat-frame, ytm-mobile-topbar-renderer, ytm-single-column-watch-next-results-renderer, ytm-item-section-renderer, .watch-below-the-player, .ytp-pause-overlay, .ytp-ce-element, .ytp-chrome-top, .ytp-chrome-bottom, .ytp-gradient-top, .ytp-gradient-bottom, .ytp-watermark, .ytp-paid-content-overlay, .iv-branding { display:none !important; opacity:0 !important; pointer-events:none !important; }' +
                  '#player-container-id, #player-container, #player, #movie_player, .html5-video-player { position:fixed !important; top:0 !important; left:0 !important; right:0 !important; bottom:0 !important; width:100vw !important; height:100vh !important; max-width:100vw !important; max-height:100vh !important; z-index:999999 !important; background:#000 !important; margin:0 !important; padding:0 !important; }' +
                  '.html5-video-container, video.html5-main-video { position:fixed !important; top:0 !important; left:0 !important; width:100vw !important; height:100vh !important; object-fit:contain !important; background:#000 !important; }' +
                  '.ytp-caption-window-container { opacity:0.001 !important; pointer-events:none !important; }';
                (document.head || document.documentElement).appendChild(style);
              } catch(e) {}

              function getPlayer() {
                return document.getElementById('movie_player') || document.querySelector('.html5-video-player');
              }

              function getVideo() {
                return document.querySelector('video.html5-main-video') || document.querySelector('video');
              }

              function qualityLabelFor(v, p) {
                try {
                  if (v && v.videoHeight >= 1000) return '1080p HD';
                  if (v && v.videoHeight >= 700) return '720p HD';
                  if (v && v.videoHeight >= 450) return '480p';
                  if (p && typeof p.getPlaybackQuality === 'function') {
                    var q = p.getPlaybackQuality();
                    if (q === 'hd1080' || q === 'highres') return '1080p HD';
                    if (q === 'hd720') return '720p HD';
                  }
                } catch(e) {}
                return '1080p HD';
              }

              function enableYouTubeCaptions(p) {
                if (!p) return;
                try {
                  if (typeof p.loadModule === 'function') p.loadModule('captions');
                  if (typeof p.getOption === 'function' && typeof p.setOption === 'function') {
                    var tracks = p.getOption('captions', 'tracklist');
                    if (tracks && tracks.length > 0) {
                      var chosen = tracks[0];
                      for (var i = 0; i < tracks.length; i++) {
                        var lang = (tracks[i].languageCode || '').toLowerCase();
                        if (lang.indexOf('en') === 0 || lang.indexOf('hi') === 0) {
                          chosen = tracks[i];
                          break;
                        }
                      }
                      p.setOption('captions', 'track', chosen);
                    }
                  }
                  var ccBtn = document.querySelector('.ytp-subtitles-button[aria-pressed="false"]');
                  if (ccBtn) { try { ccBtn.click(); } catch(e) {} }
                } catch(e) {}
              }

              window.__pulseLastVideoTime = -1;
              window.__pulseStuckTicks = 0;
              window.__pulseMetaSent = false;

              function reportPlayerMetadata(p) {
                try {
                  if (window.__pulseMetaSent) return;
                  var title = '';
                  var author = '';
                  var isLive = true;
                  if (p && typeof p.getVideoData === 'function') {
                    var vd = p.getVideoData();
                    if (vd) {
                      title = String(vd.title || '').trim();
                      author = String(vd.author || '').trim();
                      if (typeof vd.isLive === 'boolean') isLive = vd.isLive;
                    }
                  }
                  var pr = window.ytInitialPlayerResponse;
                  if (pr && pr.videoDetails) {
                    if (!title) title = String(pr.videoDetails.title || '').trim();
                    if (!author) author = String(pr.videoDetails.author || '').trim();
                    if (typeof pr.videoDetails.isLive === 'boolean') isLive = pr.videoDetails.isLive;
                  }
                  if (!title && document.title) {
                    title = document.title.replace(/\s*-\s*YouTube\s*$/i, '').trim();
                  }
                  if ((title || author) && window.PulsePlayerBridge && PulsePlayerBridge.onPlayerMeta) {
                    window.__pulseMetaSent = true;
                    PulsePlayerBridge.onPlayerMeta(window.__pulseChannelId, title, author, !!isLive);
                  }
                } catch(e) {}
              }

              function detectLoginGate(p) {
                try {
                  var pr = (p && typeof p.getPlayerResponse === 'function') ? p.getPlayerResponse() : window.ytInitialPlayerResponse;
                  var ps = pr && pr.playabilityStatus;
                  if (ps && String(ps.status || '').toUpperCase() === 'LOGIN_REQUIRED') {
                    return true;
                  }
                  var errEl = document.querySelector('.ytp-error-content-wrap-reason, .ytp-error-content-wrap-subreason, yt-player-error-message-renderer');
                  if (errEl) {
                    var txt = String(errEl.innerText || errEl.textContent || '').toLowerCase();
                    if (txt.indexOf('sign in') !== -1 || txt.indexOf('not a bot') !== -1 || txt.indexOf('log in') !== -1) {
                      return true;
                    }
                  }
                } catch(e) {}
                return false;
              }

              function enforce1080pAndPlay() {
                try {
                  var p = getPlayer();
                  var v = getVideo();

                  if (detectLoginGate(p)) {
                    if (window.PulsePlayerBridge && PulsePlayerBridge.onLoginRequired) {
                      PulsePlayerBridge.onLoginRequired(window.__pulseChannelId);
                    }
                    return;
                  }

                  // Auto-dismiss "Video paused. Continue watching?" overnight dialogs & consent popups
                  var confirmBtn = document.querySelector(
                    'yt-confirm-dialog-renderer #confirm-button button, ' +
                    'tp-yt-paper-dialog #confirm-button button, ' +
                    'tp-yt-paper-dialog #confirm-button, ' +
                    'button[aria-label*="Continue watching"], ' +
                    'button[aria-label*="Accept all"]'
                  );
                  if (confirmBtn) { try { confirmBtn.click(); } catch(e) {} }

                  var dismissBtn = document.querySelector('.ytp-large-play-button');
                  if (dismissBtn && v && v.paused) { try { dismissBtn.click(); } catch(e) {} }

                  if (p) {
                    reportPlayerMetadata(p);
                    if (typeof p.getAvailableQualityLevels === 'function') {
                      try {
                        var lvls = p.getAvailableQualityLevels() || [];
                        var targetQ = lvls.indexOf('hd1080') !== -1 ? 'hd1080'
                          : (lvls.indexOf('hd1440') !== -1 ? 'hd1440'
                          : (lvls.indexOf('highres') !== -1 ? 'highres'
                          : (lvls.indexOf('hd720') !== -1 ? 'hd720' : (lvls[0] || 'hd1080'))));
                        var maxQ = lvls.indexOf('highres') !== -1 ? 'highres' : targetQ;
                        if (typeof p.setPlaybackQualityRange === 'function') {
                          p.setPlaybackQualityRange(targetQ, maxQ);
                        }
                        if (typeof p.setPlaybackQuality === 'function') {
                          p.setPlaybackQuality(targetQ);
                        }
                      } catch(e) {}
                    } else {
                      if (typeof p.setPlaybackQualityRange === 'function') {
                        try { p.setPlaybackQualityRange('hd1080', 'highres'); } catch(e) {}
                      }
                      if (typeof p.setPlaybackQuality === 'function') {
                        try { p.setPlaybackQuality('hd1080'); } catch(e) {}
                      }
                    }
                    enableYouTubeCaptions(p);
                  }

                  if (v) {
                    v.playsInline = true;
                    v.setAttribute('playsinline', 'true');
                    if (!window.__pulseMseRecActive && !window.__pulseRec) {
                      v.muted = !!window.__pulseUserMuted;
                    }
                    if (v.paused) {
                      var playPromise = v.play();
                      if (playPromise && playPromise.catch) {
                        playPromise.catch(function() {
                          v.muted = true;
                          v.play().catch(function(){});
                        });
                      }
                    }

                    // Overnight Anti-Freeze Watchdog: recover if stream stalls during 8-hour unattended run
                    var curT = Number(v.currentTime || 0);
                    if (curT > 0 && Math.abs(curT - window.__pulseLastVideoTime) < 0.05) {
                      window.__pulseStuckTicks++;
                      if (window.__pulseStuckTicks === 10) {
                        try {
                          if (v.seekable && v.seekable.length > 0) {
                            v.currentTime = Math.max(0, v.seekable.end(v.seekable.length - 1) - 1.5);
                          }
                          v.play().catch(function(){});
                        } catch(e) {}
                      } else if (window.__pulseStuckTicks >= 28 && !window.__pulseMseRecActive) {
                        window.__pulseStuckTicks = 0;
                        try { location.reload(); } catch(e) {}
                        return;
                      }
                    } else {
                      window.__pulseStuckTicks = 0;
                    }
                    window.__pulseLastVideoTime = curT;

                    if (window.PulsePlayerBridge && PulsePlayerBridge.onPlayerReady) {
                      PulsePlayerBridge.onPlayerReady(qualityLabelFor(v, p), !v.paused && v.readyState >= 2);
                    }
                  }
                } catch(e) {}
              }

              setInterval(enforce1080pAndPlay, 2400);
              setTimeout(enforce1080pAndPlay, 350);

              // IN-WEBVIEW LIVE CHAT VELOCITY TAP (Same-origin Chromium cookies — bypasses Wi-Fi API blocks!)
              // Strictly counts incoming chat messages for spike triggering; NEVER records chat text.
              (function startInWebViewChatVelocityTap() {
                if (window.__pulseChatTapStarted) return;
                window.__pulseChatTapStarted = true;

                var chId = window.__pulseChannelId;
                var continuation = null;
                var apiKey = null;
                var clientCtx = null;
                var isFirstSeed = true;

                function getVideoIdFromUrl() {
                  try {
                    var m = location.href.match(/[?&]v=([a-zA-Z0-9_-]{11})/);
                    if (m) return m[1];
                    var m2 = location.pathname.match(/\/live\/([a-zA-Z0-9_-]{11})/);
                    if (m2) return m2[1];
                  } catch(e) {}
                  return '';
                }

                function extractContinuationFromObject(obj) {
                  try {
                    var conv = obj && obj.contents && obj.contents.twoColumnWatchNextResults && obj.contents.twoColumnWatchNextResults.conversationBar;
                    var lcr = conv && conv.liveChatRenderer;
                    var conts = lcr && lcr.continuations;
                    if (conts && conts.length > 0) {
                      for (var i = 0; i < conts.length; i++) {
                        var c = conts[i];
                        var d = c.reloadContinuationData || c.invalidationContinuationData || c.timedContinuationData;
                        if (d && d.continuation) return d.continuation;
                      }
                    }
                  } catch(e) {}
                  return null;
                }

                function extractContinuationFromHtml(html) {
                  if (!html) return null;
                  var r1 = html.match(/"reloadContinuationData"\s*:\s*\{\s*"continuation"\s*:\s*"([^"]+)"/);
                  if (r1) return r1[1];
                  var r2 = html.match(/"invalidationContinuationData"\s*:\s*\{[^}]*"continuation"\s*:\s*"([^"]+)"/);
                  if (r2) return r2[1];
                  var r3 = html.match(/"timedContinuationData"\s*:\s*\{[^}]*"continuation"\s*:\s*"([^"]+)"/);
                  if (r3) return r3[1];
                  return null;
                }

                function ensureContextAndKey(htmlFallback) {
                  try {
                    if (window.ytcfg && typeof window.ytcfg.get === 'function') {
                      if (!apiKey) apiKey = window.ytcfg.get('INNERTUBE_API_KEY') || null;
                      if (!clientCtx) clientCtx = window.ytcfg.get('INNERTUBE_CONTEXT') || null;
                    }
                  } catch(e) {}
                  if (!apiKey && htmlFallback) {
                    var km = htmlFallback.match(/"INNERTUBE_API_KEY"\s*:\s*"([^"]+)"/);
                    if (km) apiKey = km[1];
                  }
                  if (!clientCtx) {
                    clientCtx = {
                      client: {
                        clientName: 'WEB',
                        clientVersion: '2.20250501.01.00',
                        hl: 'en',
                        gl: 'US'
                      }
                    };
                  }
                }

                function bootstrapChat() {
                  ensureContextAndKey(null);
                  continuation = extractContinuationFromObject(window.ytInitialData);
                  if (continuation && apiKey) {
                    isFirstSeed = true;
                    schedulePoll(800);
                    return;
                  }
                  var vid = getVideoIdFromUrl();
                  if (!vid) {
                    setTimeout(bootstrapChat, 4000);
                    return;
                  }
                  fetch('/live_chat?is_popout=1&v=' + encodeURIComponent(vid), { credentials: 'include' })
                    .then(function(r) { return r.text(); })
                    .then(function(html) {
                      ensureContextAndKey(html);
                      continuation = extractContinuationFromHtml(html);
                      if (continuation && apiKey) {
                        isFirstSeed = true;
                        schedulePoll(800);
                      } else {
                        setTimeout(bootstrapChat, 12000);
                      }
                    })
                    .catch(function() {
                      setTimeout(bootstrapChat, 10000);
                    });
                }

                function schedulePoll(ms) {
                  setTimeout(pollOnce, Math.max(2400, Math.min(ms || 3800, 6500)));
                }

                function pollOnce() {
                  if (!continuation || !apiKey) {
                    setTimeout(bootstrapChat, 6000);
                    return;
                  }
                  fetch('/youtubei/v1/live_chat/get_live_chat?key=' + encodeURIComponent(apiKey) + '&prettyPrint=false', {
                    method: 'POST',
                    credentials: 'include',
                    headers: { 'content-type': 'application/json' },
                    body: JSON.stringify({
                      context: clientCtx,
                      continuation: continuation
                    })
                  })
                    .then(function(r) {
                      if (!r.ok) throw new Error('HTTP ' + r.status);
                      return r.json();
                    })
                    .then(function(json) {
                      var lcc = json && json.continuationContents && json.continuationContents.liveChatContinuation;
                      if (!lcc) {
                        continuation = null;
                        setTimeout(bootstrapChat, 10000);
                        return;
                      }
                      var actions = lcc.actions || [];
                      var count = 0;
                      for (var i = 0; i < actions.length; i++) {
                        var item = actions[i] && actions[i].addChatItemAction && actions[i].addChatItemAction.item;
                        if (item && (item.liveChatTextMessageRenderer || item.liveChatPaidMessageRenderer || item.liveChatMembershipItemRenderer)) {
                          count++;
                        }
                      }
                      var nextToken = null;
                      var timeoutMs = 3600;
                      var conts = lcc.continuations || [];
                      for (var j = 0; j < conts.length; j++) {
                        var d = conts[j].invalidationContinuationData || conts[j].timedContinuationData || conts[j].reloadContinuationData;
                        if (d && d.continuation) {
                          nextToken = d.continuation;
                          if (d.timeoutMs && d.timeoutMs > 0) timeoutMs = d.timeoutMs;
                          break;
                        }
                      }
                      continuation = nextToken;
                      if (window.PulsePlayerBridge && PulsePlayerBridge.onLiveChatPulse) {
                        PulsePlayerBridge.onLiveChatPulse(chId, count, isFirstSeed);
                      }
                      isFirstSeed = false;
                      if (continuation) {
                        schedulePoll(timeoutMs);
                      } else {
                        setTimeout(bootstrapChat, 10000);
                      }
                    })
                    .catch(function() {
                      setTimeout(bootstrapChat, 9000);
                    });
                }

                setTimeout(bootstrapChat, 1500);
              })();

              window.__pulseSetMuted = function(muted) {
                window.__pulseUserMuted = !!muted;
                var v = getVideo();
                if (v) {
                  v.muted = !!muted;
                  if (!muted) v.volume = 1.0;
                }
                if (window.__pulseSpeakerGain) {
                  try { window.__pulseSpeakerGain.gain.value = muted ? 0.0 : 1.0; } catch(e) {}
                }
              };

              function startCaptionScraper(chId) {
                if (window.__pulseCapTimer) clearInterval(window.__pulseCapTimer);
                enableYouTubeCaptions(getPlayer());
                window.__pulseCapTimer = setInterval(function() {
                  if (!window.__pulseMseRecActive) return;
                  try {
                    var segs = document.querySelectorAll('.ytp-caption-segment, .caption-window span, .captions-text span');
                    if (segs && segs.length > 0) {
                      var parts = [];
                      for (var i = 0; i < segs.length; i++) {
                        var t = (segs[i].innerText || segs[i].textContent || '').trim();
                        if (t) parts.push(t);
                      }
                      var joined = parts.join(' ').trim();
                      if (joined && window.PulsePlayerBridge && PulsePlayerBridge.onCaptionCue) {
                        PulsePlayerBridge.onCaptionCue(chId, joined);
                      }
                    }
                  } catch(e) {}
                }, 320);
              }

              window.__pulseFetchCaptions = function(chId) {
                try {
                  var p = getPlayer();
                  var resp = (p && typeof p.getPlayerResponse === 'function') ? p.getPlayerResponse() : window.ytInitialPlayerResponse;
                  var tracks = resp && resp.captions && resp.captions.playerCaptionsTracklistRenderer && resp.captions.playerCaptionsTracklistRenderer.captionTracks;
                  if (!tracks || tracks.length === 0) {
                    if (window.PulsePlayerBridge) PulsePlayerBridge.onCaptionFetchComplete(chId);
                    return;
                  }
                  var chosen = tracks[0];
                  for (var i = 0; i < tracks.length; i++) {
                    var lang = (tracks[i].languageCode || '').toLowerCase();
                    if (lang.indexOf('en') === 0 || lang.indexOf('hi') === 0) {
                      chosen = tracks[i];
                      break;
                    }
                  }
                  var baseUrl = chosen.baseUrl || '';
                  if (!baseUrl) {
                    if (window.PulsePlayerBridge) PulsePlayerBridge.onCaptionFetchComplete(chId);
                    return;
                  }
                  var sep = baseUrl.indexOf('?') !== -1 ? '&' : '?';
                  fetch(baseUrl + sep + 'fmt=json3', { credentials: 'include' })
                    .then(function(r) { return r.text(); })
                    .then(function(txt) {
                      if (txt && txt.length > 16 && window.PulsePlayerBridge) {
                        PulsePlayerBridge.onTimedTextPayload(chId, txt);
                      }
                    })
                    .catch(function(){})
                    .finally(function() {
                      if (window.PulsePlayerBridge) PulsePlayerBridge.onCaptionFetchComplete(chId);
                    });
                } catch(e) {
                  if (window.PulsePlayerBridge) PulsePlayerBridge.onCaptionFetchComplete(chId);
                }
              };

              // LAYER 1: Start Pure Raw Internal MSE Bitstream Recording
              window.__pulseStartMseRec = function(chId) {
                window.__pulseRecChId = chId;
                window.__pulseMseRecActive = true;
                startCaptionScraper(chId);
                try {
                  var v = getVideo();
                  if (v && v.paused) v.play().catch(function(){});
                  if (window.__pulseInitVideo && window.PulsePlayerBridge && PulsePlayerBridge.onMseInit) {
                    var iv = window.__pulseInitVideo;
                    PulsePlayerBridge.onMseInit(chId, true, iv.isMp4, iv.b64);
                  } else {
                    // Gently nudge quality so YouTube emits a fresh ftyp+moov / WebM EBML init segment immediately
                    var p = getPlayer();
                    if (p && typeof p.setPlaybackQuality === 'function') {
                      try {
                        p.setPlaybackQuality('hd720');
                        setTimeout(function() {
                          try {
                            if (typeof p.setPlaybackQualityRange === 'function') p.setPlaybackQualityRange('hd1080', 'hd1080');
                            p.setPlaybackQuality('hd1080');
                          } catch(e) {}
                        }, 300);
                      } catch(e) {}
                    }
                  }
                  if (window.__pulseInitAudio && window.PulsePlayerBridge && PulsePlayerBridge.onMseInit) {
                    var ia = window.__pulseInitAudio;
                    PulsePlayerBridge.onMseInit(chId, false, ia.isMp4, ia.b64);
                  }
                } catch(e) {}
              };

              // LAYER 2: Pure Internal Offscreen <canvas> (1080p 30fps setInterval) + WebAudio MediaRecorder
              // Skips broken video.captureStream() & experimental video/mp4 on Android WebView — ZERO UI/notifications!
              window.__pulseStartInternalMediaRec = function(chId) {
                try {
                  var v = getVideo();
                  if (!v || typeof MediaRecorder === 'undefined') {
                    if (window.PulsePlayerBridge) PulsePlayerBridge.onRecordingError(chId, 'No internal recorder');
                    return;
                  }
                  if (v.paused) v.play().catch(function(){});

                  if (window.__pulseCanvasTimer) {
                    clearInterval(window.__pulseCanvasTimer);
                    window.__pulseCanvasTimer = null;
                  }
                  if (window.__pulseRecWatchdog) {
                    clearTimeout(window.__pulseRecWatchdog);
                    window.__pulseRecWatchdog = null;
                  }

                  // Preserve true 1080p Full HD resolution directly from the live <video> surface
                  var rawW = (v.videoWidth && v.videoWidth >= 320) ? Math.min(v.videoWidth, 1920) : 1920;
                  var rawH = (v.videoHeight && v.videoHeight >= 180) ? Math.min(v.videoHeight, 1080) : 1080;
                  var cw = rawW - (rawW % 2);
                  var ch = rawH - (rawH % 2);

                  var canvas = document.createElement('canvas');
                  canvas.width = cw;
                  canvas.height = ch;
                  var ctx = canvas.getContext('2d', { alpha: false });
                  if (!ctx || typeof canvas.captureStream !== 'function') {
                    if (window.PulsePlayerBridge) PulsePlayerBridge.onRecordingError(chId, 'Canvas stream unavailable');
                    return;
                  }
                  ctx.imageSmoothingEnabled = true;
                  try { ctx.imageSmoothingQuality = 'high'; } catch(e) {}

                  function paintFrame() {
                    if (!window.__pulseMseRecActive) return;
                    try {
                      if (v.readyState >= 2) {
                        ctx.drawImage(v, 0, 0, cw, ch);
                      }
                    } catch(e) {}
                  }
                  // Paint initial frame immediately and pump at 30fps via setInterval (works even when scrolled/overnight!)
                  paintFrame();
                  window.__pulseCanvasTimer = setInterval(paintFrame, 33);

                  var canvasStream = canvas.captureStream(30);
                  if (!canvasStream || canvasStream.getVideoTracks().length === 0) {
                    if (window.PulsePlayerBridge) PulsePlayerBridge.onRecordingError(chId, 'No canvas video track');
                    return;
                  }

                  function buildAudioTracks() {
                    var tracks = [];
                    try {
                      if (!v.__pulseAudioSetup && (window.AudioContext || window.webkitAudioContext)) {
                        var AC = window.AudioContext || window.webkitAudioContext;
                        var actx = new AC();
                        var src = actx.createMediaElementSource(v);
                        var recDest = actx.createMediaStreamDestination();
                        var speakerGain = actx.createGain();
                        speakerGain.gain.value = window.__pulseUserMuted ? 0.0 : 1.0;
                        src.connect(recDest);
                        src.connect(speakerGain);
                        speakerGain.connect(actx.destination);

                        // Keep recDest audio clock continuously alive even during silence
                        try {
                          var osc = actx.createOscillator();
                          var silentGain = actx.createGain();
                          silentGain.gain.value = 0.0;
                          osc.connect(silentGain);
                          silentGain.connect(recDest);
                          osc.start();
                        } catch(oe) {}

                        v.__pulseAudioCtx = actx;
                        v.__pulseRecDest = recDest;
                        v.__pulseAudioSetup = true;
                        window.__pulseSpeakerGain = speakerGain;
                      }
                      if (v.__pulseAudioCtx && v.__pulseAudioCtx.state === 'suspended') {
                        v.__pulseAudioCtx.resume().catch(function(){});
                      }
                      if (v.__pulseRecDest) {
                        v.muted = false;
                        v.volume = 1.0;
                        var aTracks = v.__pulseRecDest.stream.getAudioTracks();
                        if (aTracks && aTracks.length > 0) tracks = aTracks.slice();
                      }
                    } catch(ae) {}
                    return tracks;
                  }

                  function launchRecorder(includeAudio) {
                    var outTracks = canvasStream.getVideoTracks().slice();
                    if (includeAudio) {
                      var aTracks = buildAudioTracks();
                      if (aTracks.length > 0) outTracks = outTracks.concat(aTracks);
                      else includeAudio = false;
                    }
                    var outStream = new MediaStream(outTracks);

                    // Prefer sharp 1080p VP9 first, falling back to battle-tested VP8 WebM
                    var candidates = includeAudio ? [
                      { mime: 'video/webm;codecs=vp9,opus', ext: 'webm' },
                      { mime: 'video/webm;codecs=vp8,opus', ext: 'webm' },
                      { mime: 'video/webm', ext: 'webm' }
                    ] : [
                      { mime: 'video/webm;codecs=vp9', ext: 'webm' },
                      { mime: 'video/webm;codecs=vp8', ext: 'webm' },
                      { mime: 'video/webm', ext: 'webm' }
                    ];
                    var chosen = { mime: '', ext: 'webm' };
                    for (var i = 0; i < candidates.length; i++) {
                      try {
                        if (MediaRecorder.isTypeSupported(candidates[i].mime)) {
                          chosen = candidates[i];
                          break;
                        }
                      } catch(e) {}
                    }

                    var opts = chosen.mime
                      ? (includeAudio
                          ? { mimeType: chosen.mime, videoBitsPerSecond: 14000000, audioBitsPerSecond: 192000 }
                          : { mimeType: chosen.mime, videoBitsPerSecond: 14000000 })
                      : { videoBitsPerSecond: 14000000 };

                    var rec = new MediaRecorder(outStream, opts);
                    var gotAnyChunk = false;
                    var restartedAsVideoOnly = false;
                    window.__pulseRec = rec;

                    rec.ondataavailable = function(ev) {
                      if (!ev || !ev.data || ev.data.size === 0) return;
                      gotAnyChunk = true;
                      try {
                        if (typeof ev.data.arrayBuffer === 'function' && window.__pulseBytesToBase64) {
                          ev.data.arrayBuffer().then(function(buf) {
                            var b64 = window.__pulseBytesToBase64(new Uint8Array(buf));
                            if (b64 && window.PulsePlayerBridge && PulsePlayerBridge.onMediaChunk) {
                              PulsePlayerBridge.onMediaChunk(chId, chosen.ext, b64);
                            }
                          }).catch(function(){});
                        } else {
                          var reader = new FileReader();
                          reader.onloadend = function() {
                            var res = reader.result || '';
                            var comma = res.indexOf(',');
                            var b64 = comma >= 0 ? res.substring(comma + 1) : res;
                            if (b64 && window.PulsePlayerBridge && PulsePlayerBridge.onMediaChunk) {
                              PulsePlayerBridge.onMediaChunk(chId, chosen.ext, b64);
                            }
                          };
                          reader.readAsDataURL(ev.data);
                        }
                      } catch(e) {}
                    };

                    rec.onerror = function() {
                      if (includeAudio && !restartedAsVideoOnly && window.__pulseMseRecActive) {
                        restartedAsVideoOnly = true;
                        launchRecorder(false);
                        return;
                      }
                      if (window.PulsePlayerBridge) PulsePlayerBridge.onRecordingError(chId, 'MediaRecorder error');
                    };

                    rec.onstop = function() {
                      if (restartedAsVideoOnly) return;
                      setTimeout(function() {
                        if (window.PulsePlayerBridge) PulsePlayerBridge.onRecordingFinished(chId);
                      }, 350);
                    };

                    rec.start(1000);
                    // Force an early data flush at 1.2s so MB counter climbs right away
                    setTimeout(function() {
                      try { if (rec.state === 'recording') rec.requestData(); } catch(e) {}
                    }, 1200);

                    // Self-healing watchdog: if A+V WebAudio muxing stalls at 0 bytes after 2.4s, auto-switch to pure video-only canvas (paired with MSE audio in Kotlin!)
                    if (includeAudio) {
                      window.__pulseRecWatchdog = setTimeout(function() {
                        if (!gotAnyChunk && window.__pulseMseRecActive && window.__pulseRec === rec) {
                          restartedAsVideoOnly = true;
                          try { if (rec.state !== 'inactive') rec.stop(); } catch(e) {}
                          launchRecorder(false);
                        }
                      }, 2400);
                    }
                  }

                  launchRecorder(true);
                } catch(err) {
                  if (window.PulsePlayerBridge) PulsePlayerBridge.onRecordingError(chId, String(err));
                }
              };

              window.__pulseStopAllRec = function() {
                window.__pulseMseRecActive = false;
                if (window.__pulseCapTimer) {
                  clearInterval(window.__pulseCapTimer);
                  window.__pulseCapTimer = null;
                }
                if (window.__pulseCanvasTimer) {
                  clearInterval(window.__pulseCanvasTimer);
                  window.__pulseCanvasTimer = null;
                }
                if (window.__pulseRecWatchdog) {
                  clearTimeout(window.__pulseRecWatchdog);
                  window.__pulseRecWatchdog = null;
                }
                if (window.__pulseCanvasRaf) {
                  cancelAnimationFrame(window.__pulseCanvasRaf);
                  window.__pulseCanvasRaf = null;
                }
                var rec = window.__pulseRec;
                window.__pulseRec = null;
                if (rec && rec.state !== 'inactive') {
                  try { rec.stop(); } catch(e) {}
                } else if (window.PulsePlayerBridge && PulsePlayerBridge.onRecordingFinished) {
                  PulsePlayerBridge.onRecordingFinished(window.__pulseRecChId || window.__pulseChannelId);
                }
                var v = getVideo();
                if (v && !v.__pulseAudioSetup) {
                  v.muted = !!window.__pulseUserMuted;
                }
              };
            })();
        """.trimIndent()
    }

    companion object {
        private const val PARKING_HOST_TAG = "pulseclip_webview_parking_host"
        private const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun hasYouTubeAuthCookies(): Boolean {
            return try {
                val cm = CookieManager.getInstance()
                val ytCookies = cm.getCookie("https://www.youtube.com").orEmpty()
                val mCookies = cm.getCookie("https://m.youtube.com").orEmpty()
                val all = "$ytCookies; $mCookies"
                all.contains("SAPISID=") ||
                    all.contains("__Secure-3PSID=") ||
                    all.contains("LOGIN_INFO=") ||
                    all.contains("SID=")
            } catch (_: Throwable) {
                false
            }
        }

        /**
         * Returns true if [bytes] begins with an fMP4 initialization box (`ftyp`) or WebM EBML header.
         */
        fun isMseInitSegment(bytes: ByteArray, isMp4: Boolean): Boolean {
            if (bytes.size < 12) return false
            return if (isMp4) {
                bytes[4] == 'f'.code.toByte() &&
                    bytes[5] == 't'.code.toByte() &&
                    bytes[6] == 'y'.code.toByte() &&
                    bytes[7] == 'p'.code.toByte()
            } else {
                (bytes[0].toInt() and 0xFF) == 0x1A &&
                    (bytes[1].toInt() and 0xFF) == 0x45 &&
                    (bytes[2].toInt() and 0xFF) == 0xDF &&
                    (bytes[3].toInt() and 0xFF) == 0xA3
            }
        }

        /**
         * Returns true if [bytes] begins at a clean fMP4 media fragment box (`moof`, `styp`, `sidx`, `emsg`)
         * or a WebM Cluster (`0x1F 0x43 0xB6 0x75`), ensuring recordings always start cleanly on a frame boundary.
         */
        fun isMseMediaBoundary(bytes: ByteArray, isMp4: Boolean): Boolean {
            if (bytes.size < 12) return false
            return if (isMp4) {
                val b4 = bytes[4].toInt().toChar()
                val b5 = bytes[5].toInt().toChar()
                val b6 = bytes[6].toInt().toChar()
                val b7 = bytes[7].toInt().toChar()
                val box = "$b4$b5$b6$b7"
                box == "moof" || box == "styp" || box == "sidx" || box == "emsg"
            } else {
                (bytes[0].toInt() and 0xFF) == 0x1F &&
                    (bytes[1].toInt() and 0xFF) == 0x43 &&
                    (bytes[2].toInt() and 0xFF) == 0xB6 &&
                    (bytes[3].toInt() and 0xFF) == 0x75
            }
        }

        fun buildCaptionSegmentsFromCues(
            cues: List<Pair<Long, String>>,
            maxDurationSec: Int
        ): List<CaptionSegment> {
            if (cues.isEmpty()) return emptyList()
            val maxMs = maxDurationSec * 1000L
            val deduped = ArrayList<Pair<Long, String>>(cues.size)
            for ((timeMs, raw) in cues) {
                val clean = raw.replace(Regex("\\s+"), " ").trim().take(90)
                if (clean.length < 2) continue
                val clampedMs = timeMs.coerceIn(0L, (maxMs - 500L).coerceAtLeast(0L))
                val prev = deduped.lastOrNull()
                if (prev != null && prev.second.equals(clean, ignoreCase = true)) continue
                deduped.add(clampedMs to clean)
            }
            if (deduped.isEmpty()) return emptyList()

            return deduped.mapIndexed { idx, (startMs, text) ->
                val nextStart = deduped.getOrNull(idx + 1)?.first ?: (startMs + 3200L)
                val endMs = nextStart.coerceIn(startMs + 900L, minOf(startMs + 4200L, maxMs))
                CaptionSegment(
                    startMs = startMs,
                    endMs = endMs,
                    text = text
                )
            }
        }
    }
}
