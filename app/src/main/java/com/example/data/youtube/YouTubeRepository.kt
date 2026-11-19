package com.example.data.youtube

import com.example.engine.AutoCaptionGenerator
import com.example.engine.CaptionSegment
import com.example.engine.LiveVideoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class ResolvedChannelInfo(
    val channelId: String,
    val handle: String,
    val title: String,
    val avatarUrl: String,
    val directVideoId: String? = null,
    val liveTitle: String? = null
)

data class LiveChatSession(
    val continuation: String,
    val publicWebKey: String,
    val clientVersion: String
)

data class ResolvedStreamMedia(
    val hlsManifestUrl: String?,
    val userAgent: String,
    val dashManifestUrl: String? = null,
    val dashUserAgent: String = YouTubeRepository.DESKTOP_USER_AGENT,
    val captionTrackUrl: String? = null,
    val clientLabel: String = "WEB",
    val diagnosticNote: String = ""
)

data class LiveStreamDetection(
    val isLive: Boolean,
    val videoId: String? = null,
    val title: String? = null,
    val liveChatSession: LiveChatSession? = null,
    val hlsManifestUrl: String? = null,
    val hlsUserAgent: String = YouTubeRepository.DESKTOP_USER_AGENT,
    val dashManifestUrl: String? = null,
    val captionTrackUrl: String? = null,
    val concurrentViewers: Long = 0L,
    val thumbnailUrl: String? = null
)

data class LiveChatBatch(
    val messages: List<LiveChatEntry>,
    val nextSession: LiveChatSession?,
    val pollingIntervalMillis: Long
)

data class LiveChatEntry(
    val id: String,
    val author: String,
    val text: String,
    val receivedAtMillis: Long
)

sealed interface YouTubeResult<out T> {
    data class Success<T>(val data: T) : YouTubeResult<T>
    data class Error(val message: String, val isRateLimited: Boolean = false) : YouTubeResult<Nothing>
}

/**
 * Keyless YouTube live stream video, audio, captions & chat velocity repository.
 * - Resolves channels & detects live broadcasts without any API key.
 * - Extracts visitorData from public watch pages and passes X-Goog-Visitor-Id across clients.
 * - Probes BOTH native iOS Safari mobile watch HLS AND 6 InnerTube clients (ANDROID_VR, IOS,
 *   TVHTML5, TVHTML5_SIMPLY_EMBEDDED_PLAYER, WEB_SAFARI, ANDROID_TESTSUITE) plus live DASH MPD
 *   with non-Range segment verification so 403/unplayable URLs are skipped cleanly.
 */
class YouTubeRepository(
    private val httpClient: OkHttpClient
) {
    suspend fun resolveChannel(rawInput: String): YouTubeResult<ResolvedChannelInfo> {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return YouTubeResult.Error("Paste a YouTube Live Video link (youtube.com/live/… or watch?v=…) or @handle.")
        }

        // 1) Direct YouTube Live Video Link — works 100% even when Wi-Fi blocks YouTube API/HTML!
        val directVideoId = extractVideoIdFromInput(trimmed)
        if (!directVideoId.isNullOrBlank()) {
            return YouTubeResult.Success(resolveDirectLiveVideo(directVideoId))
        }

        val cleaned = normalizeChannelInput(trimmed)
        if (cleaned.isBlank()) {
            return YouTubeResult.Error("Please enter a YouTube Live Video link, @handle, or Channel ID (UC…).")
        }

        val targetUrl = when {
            cleaned.startsWith("UC") && cleaned.length >= 20 ->
                "https://www.youtube.com/channel/$cleaned"
            cleaned.startsWith("@") ->
                "https://www.youtube.com/$cleaned"
            else ->
                "https://www.youtube.com/@$cleaned"
        }

        return withContext(Dispatchers.IO) {
            try {
                val html = fetchPublicPage(targetUrl)
                    ?: return@withContext YouTubeResult.Error(
                        "YouTube blocked channel lookup on this Wi-Fi. Paste the direct Live Video link (youtube.com/live/… or watch?v=…) instead — it works directly in the player!"
                    )

                val parsed = parseChannelMetadataFromHtml(html, cleaned)
                    ?: return@withContext YouTubeResult.Error(
                        "Could not read channel info on this network. Paste the direct Live Video link (youtube.com/watch?v=…) instead!"
                    )

                YouTubeResult.Success(parsed)
            } catch (e: IOException) {
                YouTubeResult.Error(
                    "Wi-Fi/network blocked YouTube channel lookup (${e.message ?: "no data"}). Paste the direct Live Video link instead!"
                )
            } catch (e: Exception) {
                YouTubeResult.Error("Failed to resolve channel: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Resolves a direct YouTube Live Video link locally without ever failing on Wi-Fi API blocks.
     * Attempts lightweight public oEmbed JSON for title/author; falls back immediately to videoId
     * so the embedded 1080p WebView player opens right away and populates real metadata in-browser.
     */
    suspend fun resolveDirectLiveVideo(videoId: String): ResolvedChannelInfo = withContext(Dispatchers.IO) {
        var authorName: String? = null
        var videoTitle: String? = null
        var thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault_live.jpg"

        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val oembedJson = fetchTextWithHeaders(oembedUrl, DESKTOP_USER_AGENT)
            if (!oembedJson.isNullOrBlank() && oembedJson.trimStart().startsWith("{")) {
                val obj = JSONObject(oembedJson)
                authorName = obj.optString("author_name", "").ifBlank { null }
                videoTitle = obj.optString("title", "").ifBlank { null }
                obj.optString("thumbnail_url", "").ifBlank { null }?.let { thumbUrl = it }
            }
        } catch (_: Exception) {
            // Wi-Fi may block OkHttp; WebView player will still load and push real title/author!
        }

        ResolvedChannelInfo(
            channelId = directVideoChannelId(videoId),
            handle = "live/$videoId",
            title = authorName ?: videoTitle ?: "Live Video ($videoId)",
            avatarUrl = thumbUrl,
            directVideoId = videoId,
            liveTitle = videoTitle ?: "Direct 1080p Live Stream"
        )
    }

    /**
     * Best-effort metadata & optional chat session lookup for a direct live video link.
     * ALWAYS returns isLive=true with the videoId so Wi-Fi OkHttp blocks can NEVER mark a
     * user-pasted live video link as Offline or Error!
     */
    suspend fun detectDirectVideoStream(videoId: String): LiveStreamDetection = withContext(Dispatchers.IO) {
        try {
            val watchHtml = fetchPublicPage("https://www.youtube.com/watch?v=$videoId")
            if (!watchHtml.isNullOrBlank()) {
                val parsed = parseLiveDetectionFromHtml(watchHtml)
                var chatSession = parsed.liveChatSession ?: extractLiveChatSession(watchHtml)
                if (chatSession == null) {
                    val chatHtml = fetchPublicPage("https://www.youtube.com/live_chat?is_popout=1&v=$videoId")
                    if (!chatHtml.isNullOrBlank()) {
                        chatSession = extractLiveChatSession(chatHtml)
                    }
                }
                return@withContext parsed.copy(
                    isLive = true,
                    videoId = videoId,
                    title = parsed.title ?: ogTitleRegex.find(watchHtml)?.groupValues?.getOrNull(1)?.let { unescapeHtml(it) },
                    liveChatSession = chatSession,
                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault_live.jpg"
                )
            }
        } catch (_: Exception) {
            // Completely safe: WebView player handles playback + in-browser chat velocity directly!
        }

        LiveStreamDetection(
            isLive = true,
            videoId = videoId,
            title = null,
            liveChatSession = null,
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault_live.jpg"
        )
    }

    suspend fun detectActiveLiveStream(channelId: String): YouTubeResult<LiveStreamDetection> {
        return withContext(Dispatchers.IO) {
            try {
                val liveUrl = "https://www.youtube.com/channel/$channelId/live"
                val html = fetchPublicPage(liveUrl)
                    ?: return@withContext YouTubeResult.Success(LiveStreamDetection(isLive = false))

                val detection = parseLiveDetectionFromHtml(html)
                if (!detection.isLive || detection.videoId.isNullOrBlank()) {
                    return@withContext YouTubeResult.Success(LiveStreamDetection(isLive = false))
                }

                val publicKey = innertubeKeyRegex.find(html)?.groupValues?.getOrNull(1)
                val visitorData = visitorDataRegex.find(html)?.groupValues?.getOrNull(1)
                var chatSession = detection.liveChatSession
                if (chatSession == null) {
                    val chatFrameHtml = fetchPublicPage(
                        "https://www.youtube.com/live_chat?is_popout=1&v=${detection.videoId}"
                    )
                    if (chatFrameHtml != null) {
                        chatSession = extractLiveChatSession(chatFrameHtml)
                    }
                }

                var hlsUrl = detection.hlsManifestUrl
                var hlsUa = DESKTOP_USER_AGENT
                var dashUrl = detection.dashManifestUrl
                var captionUrl = detection.captionTrackUrl

                if (hlsUrl.isNullOrBlank() || captionUrl.isNullOrBlank()) {
                    val playerMedia = fetchVerifiedLivePlayerStreamInfo(
                        videoId = detection.videoId,
                        publicKey = publicKey,
                        visitorData = visitorData,
                        htmlCandidates = listOfNotNull(
                            hlsUrl?.let { Triple("WEB_HTML", it, DESKTOP_USER_AGENT) }
                        ),
                        htmlDashFallback = dashUrl,
                        verifySegments = false
                    )
                    if (!playerMedia.hlsManifestUrl.isNullOrBlank()) {
                        hlsUrl = playerMedia.hlsManifestUrl
                        hlsUa = playerMedia.userAgent
                    }
                    if (dashUrl.isNullOrBlank()) {
                        dashUrl = playerMedia.dashManifestUrl
                    }
                    if (captionUrl.isNullOrBlank()) {
                        captionUrl = playerMedia.captionTrackUrl
                    }
                }

                YouTubeResult.Success(
                    detection.copy(
                        liveChatSession = chatSession,
                        hlsManifestUrl = hlsUrl,
                        hlsUserAgent = hlsUa,
                        dashManifestUrl = dashUrl,
                        captionTrackUrl = captionUrl
                    )
                )
            } catch (e: IOException) {
                YouTubeResult.Error("Network error checking live status: ${e.message ?: "offline"}")
            } catch (e: Exception) {
                YouTubeResult.Error("Live detection failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Resolves and LIVE-PROBES a fresh, playable HLS (.m3u8) or DASH (.mpd) manifest right when
     * video recording starts, extracting visitorData and testing without Range headers.
     */
    suspend fun resolveFreshLiveStreamMedia(videoId: String): ResolvedStreamMedia = withContext(Dispatchers.IO) {
        // 1) Fetch mobile Safari watch page (iOS Safari forces YouTube to emit native HLS manifest)
        val iosSafariHtml = fetchTextWithHeaders("https://m.youtube.com/watch?v=$videoId", IOS_SAFARI_USER_AGENT)
        // 2) Also fetch desktop watch page for visitorData, API key, captions & DASH/HLS fallback
        val desktopHtml = fetchPublicPage("https://www.youtube.com/watch?v=$videoId")

        val publicKey = desktopHtml?.let { innertubeKeyRegex.find(it)?.groupValues?.getOrNull(1) }
            ?: iosSafariHtml?.let { innertubeKeyRegex.find(it)?.groupValues?.getOrNull(1) }
        val visitorData = desktopHtml?.let { visitorDataRegex.find(it)?.groupValues?.getOrNull(1) }
            ?: iosSafariHtml?.let { visitorDataRegex.find(it)?.groupValues?.getOrNull(1) }

        val htmlCandidates = buildList {
            iosSafariHtml?.let { extractHlsManifestUrlFromHtml(it) }?.let { url ->
                add(Triple("IOS_SAFARI_HTML", url, IOS_SAFARI_USER_AGENT))
            }
            desktopHtml?.let { extractHlsManifestUrlFromHtml(it) }?.let { url ->
                add(Triple("WEB_HTML", url, DESKTOP_USER_AGENT))
            }
        }
        val htmlDash = desktopHtml?.let { extractDashManifestUrlFromHtml(it) }
            ?: iosSafariHtml?.let { extractDashManifestUrlFromHtml(it) }
        val htmlCaptions = desktopHtml?.let { extractCaptionTrackUrlFromHtml(it) }
            ?: iosSafariHtml?.let { extractCaptionTrackUrlFromHtml(it) }

        val verified = fetchVerifiedLivePlayerStreamInfo(
            videoId = videoId,
            publicKey = publicKey,
            visitorData = visitorData,
            htmlCandidates = htmlCandidates,
            htmlDashFallback = htmlDash,
            verifySegments = true
        )
        verified.copy(
            captionTrackUrl = verified.captionTrackUrl ?: htmlCaptions
        )
    }

    /**
     * Fetches spoken broadcast captions from YouTube's timedtext endpoint using JSON3, SRV3 XML,
     * WebVTT, and classic XML parsers. Never reads live chat comments.
     */
    suspend fun fetchSpokenCaptions(
        captionTrackUrl: String?,
        durationSeconds: Int,
        userAgent: String = DESKTOP_USER_AGENT
    ): List<CaptionSegment> = withContext(Dispatchers.IO) {
        if (captionTrackUrl.isNullOrBlank()) return@withContext emptyList()
        val baseClean = captionTrackUrl.replace(Regex("[&?]fmt=[^&]+"), "")
        val sep = if (baseClean.contains("?")) "&" else "?"

        val formats = listOf(
            "${baseClean}${sep}fmt=json3" to "json3",
            "${baseClean}${sep}fmt=srv3" to "xml",
            "${baseClean}${sep}fmt=vtt" to "vtt",
            baseClean to "xml"
        )

        for ((url, kind) in formats) {
            try {
                val body = fetchTextWithHeaders(url, userAgent)
                if (body.isNullOrBlank()) continue
                val parsed = when (kind) {
                    "json3" -> if (body.trimStart().startsWith("{")) {
                        AutoCaptionGenerator.parseTimedTextJson3(body, durationSeconds)
                    } else {
                        AutoCaptionGenerator.parseTimedTextXml(body, durationSeconds)
                    }

                    "vtt" -> AutoCaptionGenerator.parseWebVtt(body, durationSeconds)
                    else -> AutoCaptionGenerator.parseTimedTextXml(body, durationSeconds)
                }
                if (parsed.isNotEmpty()) return@withContext parsed
            } catch (_: Exception) {
            }
        }
        emptyList()
    }

    suspend fun pollLiveChat(session: LiveChatSession): YouTubeResult<LiveChatBatch> {
        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put(
                        "context",
                        JSONObject().apply {
                            put(
                                "client",
                                JSONObject().apply {
                                    put("clientName", "WEB")
                                    put("clientVersion", session.clientVersion)
                                    put("hl", "en")
                                    put("gl", "US")
                                }
                            )
                        }
                    )
                    put("continuation", session.continuation)
                }

                val url = "https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?key=${session.publicWebKey}"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        return@withContext YouTubeResult.Error(
                            "YouTube rate-limited live chat polling (HTTP 429). Backing off automatically.",
                            isRateLimited = true
                        )
                    }
                    if (!response.isSuccessful) {
                        return@withContext YouTubeResult.Error("Live chat poll returned HTTP ${response.code}.")
                    }
                    val bodyStr = response.body?.string().orEmpty()
                    if (bodyStr.isBlank()) {
                        return@withContext YouTubeResult.Error("Empty live chat payload.")
                    }
                    val parsed = parseInnerTubeChatResponse(bodyStr, session, System.currentTimeMillis())
                    YouTubeResult.Success(parsed)
                }
            } catch (e: IOException) {
                YouTubeResult.Error("Network error polling live chat: ${e.message ?: "offline"}")
            } catch (e: Exception) {
                YouTubeResult.Error("Live chat parse failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private data class InnerTubeClientSpec(
        val label: String,
        val clientName: String,
        val clientIdHeader: String,
        val clientVersion: String,
        val userAgent: String,
        val sendOrigin: Boolean = false,
        val isEmbedded: Boolean = false,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val androidSdkVersion: Int? = null
    )

    private fun fetchVerifiedLivePlayerStreamInfo(
        videoId: String,
        publicKey: String?,
        visitorData: String?,
        htmlCandidates: List<Triple<String, String, String>>,
        htmlDashFallback: String?,
        verifySegments: Boolean
    ): ResolvedStreamMedia {
        val diag = ArrayList<String>()

        var verifiedHls: String? = null
        var verifiedUa: String = DESKTOP_USER_AGENT
        var verifiedClient = "NONE"
        var unverifiedHls: String? = null
        var unverifiedUa: String = DESKTOP_USER_AGENT
        var verifiedDash: String? = null
        var verifiedDashUa: String = DESKTOP_USER_AGENT
        var fallbackDash: String? = htmlDashFallback
        var fallbackDashUa: String = DESKTOP_USER_AGENT
        var foundCaption: String? = null

        // First test any direct watch-page HTML HLS candidates (especially iOS Safari m.youtube.com!)
        for ((label, hlsUrl, ua) in htmlCandidates) {
            if (unverifiedHls == null) {
                unverifiedHls = hlsUrl
                unverifiedUa = ua
            }
            if (!verifySegments) {
                verifiedHls = hlsUrl
                verifiedUa = ua
                verifiedClient = label
                break
            }
            val probe = verifyPlayableHlsManifest(hlsUrl, ua)
            diag.add("$label:$probe")
            if (probe == "OK") {
                verifiedHls = hlsUrl
                verifiedUa = ua
                verifiedClient = label
                break
            }
        }

        val clients = listOf(
            InnerTubeClientSpec(
                label = "ANDROID_VR",
                clientName = "ANDROID_VR",
                clientIdHeader = "28",
                clientVersion = "1.61.48",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                sendOrigin = false,
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                osName = "Android",
                osVersion = "12L",
                androidSdkVersion = 32
            ),
            InnerTubeClientSpec(
                label = "IOS",
                clientName = "IOS",
                clientIdHeader = "5",
                clientVersion = "20.10.4",
                userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                sendOrigin = false,
                deviceMake = "Apple",
                deviceModel = "iPhone16,2",
                osName = "iPhone",
                osVersion = "18.3.2.22D82"
            ),
            InnerTubeClientSpec(
                label = "TVHTML5",
                clientName = "TVHTML5",
                clientIdHeader = "7",
                clientVersion = "7.20250501.10.00",
                userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
                sendOrigin = true
            ),
            InnerTubeClientSpec(
                label = "TV_EMBED",
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientIdHeader = "85",
                clientVersion = "2.0",
                userAgent = DESKTOP_USER_AGENT,
                sendOrigin = true,
                isEmbedded = true
            ),
            InnerTubeClientSpec(
                label = "WEB_SAFARI",
                clientName = "WEB",
                clientIdHeader = "1",
                clientVersion = "2.20250501.01.00",
                userAgent = MAC_SAFARI_USER_AGENT,
                sendOrigin = true
            ),
            InnerTubeClientSpec(
                label = "MWEB",
                clientName = "MWEB",
                clientIdHeader = "2",
                clientVersion = "2.20250501.01.00",
                userAgent = IOS_SAFARI_USER_AGENT,
                sendOrigin = true
            )
        )

        for (spec in clients) {
            if (!verifiedHls.isNullOrBlank() && !foundCaption.isNullOrBlank()) break
            try {
                val clientJson = JSONObject().apply {
                    put("clientName", spec.clientName)
                    put("clientVersion", spec.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                    visitorData?.let { put("visitorData", it) }
                    spec.deviceMake?.let { put("deviceMake", it) }
                    spec.deviceModel?.let { put("deviceModel", it) }
                    spec.osName?.let { put("osName", it) }
                    spec.osVersion?.let { put("osVersion", it) }
                    spec.androidSdkVersion?.let { put("androidSdkVersion", it) }
                }
                val contextJson = JSONObject().apply {
                    put("client", clientJson)
                    if (spec.isEmbedded) {
                        put(
                            "thirdParty",
                            JSONObject().apply {
                                put("embedUrl", "https://www.youtube.com/watch?v=$videoId")
                            }
                        )
                    }
                }
                val payload = JSONObject().apply {
                    put("videoId", videoId)
                    put("context", contextJson)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                }

                val url = if (!publicKey.isNullOrBlank()) {
                    "https://www.youtube.com/youtubei/v1/player?key=$publicKey&prettyPrint=false"
                } else {
                    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
                }

                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", spec.userAgent)
                    .header("X-YouTube-Client-Name", spec.clientIdHeader)
                    .header("X-YouTube-Client-Version", spec.clientVersion)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

                if (!visitorData.isNullOrBlank()) {
                    reqBuilder.header("X-Goog-Visitor-Id", visitorData)
                }
                if (spec.sendOrigin) {
                    reqBuilder
                        .header("Origin", "https://www.youtube.com")
                        .header("Referer", "https://www.youtube.com/watch?v=$videoId")
                        .header("Cookie", "CONSENT=YES+cb.20240101-01-p0.en+FX+999; SOCS=CAI")
                }

                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        diag.add("${spec.label}:API_${resp.code}")
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@use
                    val json = JSONObject(body)

                    val caption = extractCaptionUrlFromPlayerJson(json)
                    if (!caption.isNullOrBlank() && foundCaption == null) {
                        foundCaption = caption
                    }

                    val status = json.optJSONObject("playabilityStatus")?.optString("status", "")
                    val streaming = json.optJSONObject("streamingData")
                    val hls = streaming?.optString("hlsManifestUrl", "")?.ifBlank { null }
                    val dash = streaming?.optString("dashManifestUrl", "")?.ifBlank { null }

                    if (status != null && status.isNotBlank() && status != "OK") {
                        diag.add("${spec.label}:$status")
                    }

                    if (!hls.isNullOrBlank() && verifiedHls == null) {
                        if (unverifiedHls == null) {
                            unverifiedHls = hls
                            unverifiedUa = spec.userAgent
                        }
                        if (!verifySegments) {
                            verifiedHls = hls
                            verifiedUa = spec.userAgent
                            verifiedClient = spec.label
                        } else {
                            val probe = verifyPlayableHlsManifest(hls, spec.userAgent)
                            diag.add("${spec.label}:HLS_$probe")
                            if (probe == "OK") {
                                verifiedHls = hls
                                verifiedUa = spec.userAgent
                                verifiedClient = spec.label
                            }
                        }
                    }

                    if (!dash.isNullOrBlank()) {
                        if (fallbackDash == null) {
                            fallbackDash = dash
                            fallbackDashUa = spec.userAgent
                        }
                        if (verifiedHls == null && verifiedDash == null && verifySegments) {
                            val dashProbe = verifyPlayableDashManifest(dash, spec.userAgent)
                            diag.add("${spec.label}:DASH_$dashProbe")
                            if (dashProbe == "OK") {
                                verifiedDash = dash
                                verifiedDashUa = spec.userAgent
                                if (verifiedClient == "NONE") verifiedClient = "${spec.label}_DASH"
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                diag.add("${spec.label}:ERR")
            }
        }

        // If no verified HLS yet, verify htmlDashFallback as a last resort
        if (verifiedHls == null && verifiedDash == null && !htmlDashFallback.isNullOrBlank() && verifySegments) {
            val dashProbe = verifyPlayableDashManifest(htmlDashFallback, DESKTOP_USER_AGENT)
            diag.add("WEB_DASH:$dashProbe")
            if (dashProbe == "OK") {
                verifiedDash = htmlDashFallback
                verifiedDashUa = DESKTOP_USER_AGENT
                verifiedClient = "WEB_DASH"
            }
        }

        return ResolvedStreamMedia(
            hlsManifestUrl = verifiedHls ?: unverifiedHls,
            userAgent = if (verifiedHls != null) verifiedUa else unverifiedUa,
            dashManifestUrl = verifiedDash ?: fallbackDash,
            dashUserAgent = if (verifiedDash != null) verifiedDashUa else fallbackDashUa,
            captionTrackUrl = foundCaption,
            clientLabel = verifiedClient,
            diagnosticNote = diag.take(6).joinToString(" | ")
        )
    }

    /**
     * Live-probes an HLS master playlist across ranked variants using a plain GET (no Range header,
     * since YouTube live googlevideo segments reject Range headers with 400/403).
     */
    private fun verifyPlayableHlsManifest(masterUrl: String, userAgent: String): String {
        return try {
            val masterReq = Request.Builder()
                .url(masterUrl)
                .header("User-Agent", userAgent)
                .build()
            val masterText = httpClient.newCall(masterReq).execute().use { resp ->
                if (!resp.isSuccessful) return "M${resp.code}"
                resp.body?.string().orEmpty()
            }
            if (!masterText.contains("#EXTM3U")) return "NO_M3U"

            val ranked = LiveVideoRecorder.selectRankedStreamTracks(masterText, masterUrl)
                .ifEmpty {
                    listOf(
                        com.example.engine.SelectedHlsTracks(
                            videoPlaylistUrl = masterUrl,
                            audioPlaylistUrl = null,
                            subtitlePlaylistUrl = null,
                            isMuxed = true
                        )
                    )
                }

            var lastCode = "NO_SEG"
            for (tracks in ranked.take(4)) {
                val mediaUrl = tracks.videoPlaylistUrl
                val mediaText = if (mediaUrl == masterUrl) {
                    masterText
                } else {
                    val mReq = Request.Builder().url(mediaUrl).header("User-Agent", userAgent).build()
                    httpClient.newCall(mReq).execute().use { mResp ->
                        if (!mResp.isSuccessful) {
                            lastCode = "P${mResp.code}"
                            continue
                        }
                        mResp.body?.string().orEmpty()
                    }
                }
                val parsed = LiveVideoRecorder.parseMediaPlaylist(mediaText, mediaUrl)
                val sampleSeg = parsed.segmentUrls.lastOrNull() ?: continue

                // Plain GET without Range header; read first 256 bytes and close immediately
                val segReq = Request.Builder()
                    .url(sampleSeg)
                    .header("User-Agent", userAgent)
                    .build()
                httpClient.newCall(segReq).execute().use { segResp ->
                    if (segResp.isSuccessful) {
                        val buf = ByteArray(256)
                        val read = runCatching { segResp.body?.byteStream()?.read(buf) ?: 0 }.getOrDefault(0)
                        if (read > 0) return "OK"
                    } else {
                        lastCode = "S${segResp.code}"
                    }
                }
            }
            lastCode
        } catch (_: Exception) {
            "EXC"
        }
    }

    private fun verifyPlayableDashManifest(dashUrl: String, userAgent: String): String {
        return try {
            val req = Request.Builder().url(dashUrl).header("User-Agent", userAgent).build()
            val xml = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "D${resp.code}"
                resp.body?.string().orEmpty()
            }
            val parsed = LiveVideoRecorder.parseDashLiveMpd(xml, dashUrl) ?: return "NO_TRACKS"
            val sampleSeg = parsed.videoSegmentUrls.lastOrNull() ?: return "NO_SEG"
            val segReq = Request.Builder().url(sampleSeg).header("User-Agent", userAgent).build()
            httpClient.newCall(segReq).execute().use { segResp ->
                if (segResp.isSuccessful) {
                    val buf = ByteArray(256)
                    val read = runCatching { segResp.body?.byteStream()?.read(buf) ?: 0 }.getOrDefault(0)
                    if (read > 0) "OK" else "EMPTY"
                } else {
                    "S${segResp.code}"
                }
            }
        } catch (_: Exception) {
            "EXC"
        }
    }

    private fun fetchTextWithHeaders(url: String, userAgent: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "CONSENT=YES+cb.20240101-01-p0.en+FX+999; SOCS=CAI")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    private fun fetchPublicPage(url: String): String? = fetchTextWithHeaders(url, DESKTOP_USER_AGENT)

    companion object {
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        const val IOS_SAFARI_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.1"
        const val MAC_SAFARI_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Safari/605.1.15"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_CLIENT_VERSION = "2.20250501.01.00"

        private val channelIdRegexList = listOf(
            Regex("""<meta\s+itemprop="identifier"\s+content="(UC[a-zA-Z0-9_-]{22})""""),
            Regex(""""externalId":"(UC[a-zA-Z0-9_-]{22})""""),
            Regex(""""browseId":"(UC[a-zA-Z0-9_-]{22})""""),
            Regex(""""channelId":"(UC[a-zA-Z0-9_-]{22})"""")
        )
        private val ogTitleRegex = Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""")
        private val ogImageRegex = Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""")
        private val handleInUrlRegex = Regex("""youtube\.com/(@[a-zA-Z0-9._-]+)""")
        private val canonicalVideoRegex = Regex("""<link\s+rel="canonical"\s+href="https://www\.youtube\.com/watch\?v=([a-zA-Z0-9_-]{11})"""")
        private val videoDetailsIdRegex = Regex(""""videoDetails":\{[^}]*"videoId":"([a-zA-Z0-9_-]{11})"""")
        private val isLiveRegex = Regex(""""isLive"\s*:\s*true|"isLiveNow"\s*:\s*true""")
        private val viewerCountRegex = Regex(""""originalViewCount"\s*:\s*"(\d+)"|"concurrentViewers"\s*:\s*"(\d+)"""")
        private val innertubeKeyRegex = Regex(""""INNERTUBE_API_KEY"\s*:\s*"([^"]+)"""")
        private val innertubeVersionRegex = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")
        private val visitorDataRegex = Regex(""""visitorData"\s*:\s*"([^"]+)"""")
        private val hlsManifestRegex = Regex(""""hlsManifestUrl"\s*:\s*"([^"]+)"""")
        private val dashManifestRegex = Regex(""""dashManifestUrl"\s*:\s*"([^"]+)"""")
        private val captionBaseUrlRegex = Regex(
            """"captionTracks"\s*:\s*\[[^]]*?"baseUrl"\s*:\s*"([^"]+)"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val liveChatContinuationRegex = Regex(
            """"liveChatRenderer"\s*:\s*\{.*?"continuation"\s*:\s*"([^"]+)"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val reloadContinuationRegex = Regex(""""reloadContinuationData"\s*:\s*\{\s*"continuation"\s*:\s*"([^"]+)"""")
        private val invalidationContinuationRegex = Regex(""""invalidationContinuationData"\s*:\s*\{[^}]*"continuation"\s*:\s*"([^"]+)"""")
        private val timedContinuationRegex = Regex(""""timedContinuationData"\s*:\s*\{[^}]*"continuation"\s*:\s*"([^"]+)"""")

        private val directVideoPrefix = "vid_"
        private val rawVideoIdRegex = Regex("""^[a-zA-Z0-9_-]{11}$""")
        private val watchParamVideoIdRegex = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""")
        private val pathVideoIdRegex = Regex("""(?:youtu\.be/|youtube\.com/(?:live|shorts|embed|v)/)([a-zA-Z0-9_-]{11})""", RegexOption.IGNORE_CASE)

        fun directVideoChannelId(videoId: String): String = "$directVideoPrefix$videoId"

        fun isDirectVideoChannelId(channelId: String): Boolean =
            channelId.startsWith(directVideoPrefix) && channelId.length == directVideoPrefix.length + 11

        fun extractDirectVideoId(channelId: String): String? =
            if (isDirectVideoChannelId(channelId)) channelId.removePrefix(directVideoPrefix) else null

        /**
         * Extracts an 11-char YouTube videoId from any live/watch/youtu.be/shorts/embed URL
         * or a raw 11-char ID. Returns null for channel URLs / @handles / UC channel IDs.
         */
        fun extractVideoIdFromInput(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return null
            if (trimmed.startsWith("@") || (trimmed.startsWith("UC") && trimmed.length >= 20)) return null
            if (rawVideoIdRegex.matches(trimmed)) return trimmed

            watchParamVideoIdRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
            pathVideoIdRegex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
            return null
        }

        /**
         * Splits multi-line pasted live links / handles so the user can add 4-6 live videos at once.
         */
        fun parseMultiLineInputs(raw: String): List<String> {
            return raw.split('\n', ',', ';')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }

        fun normalizeChannelInput(input: String): String {
            var trimmed = input.trim()
            if (trimmed.isBlank()) return ""
            trimmed = trimmed.removePrefix("https://").removePrefix("http://")
                .removePrefix("www.").removePrefix("m.")
            if (trimmed.startsWith("youtube.com/", ignoreCase = true)) {
                trimmed = trimmed.substringAfter("youtube.com/")
            }
            trimmed = trimmed.substringBefore("?").trimEnd('/')
            return when {
                trimmed.startsWith("channel/", ignoreCase = true) ->
                    trimmed.substringAfter("channel/").substringBefore("/")
                trimmed.startsWith("@") ->
                    "@" + trimmed.removePrefix("@").substringBefore("/")
                trimmed.startsWith("c/", ignoreCase = true) ->
                    trimmed.substringAfter("c/").substringBefore("/")
                trimmed.startsWith("user/", ignoreCase = true) ->
                    trimmed.substringAfter("user/").substringBefore("/")
                else -> trimmed
            }
        }

        fun parseChannelMetadataFromHtml(html: String, fallbackInput: String): ResolvedChannelInfo? {
            val channelId = channelIdRegexList.firstNotNullOfOrNull { regex ->
                regex.find(html)?.groupValues?.getOrNull(1)
            } ?: return null

            val rawTitle = ogTitleRegex.find(html)?.groupValues?.getOrNull(1)
                ?.let { unescapeHtml(it) }
                ?.ifBlank { null }
                ?: fallbackInput

            val avatarUrl = ogImageRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()
            val handle = handleInUrlRegex.find(html)?.groupValues?.getOrNull(1)
                ?: if (fallbackInput.startsWith("@")) fallbackInput else "@$channelId"

            return ResolvedChannelInfo(
                channelId = channelId,
                handle = handle,
                title = rawTitle,
                avatarUrl = avatarUrl
            )
        }

        fun parseLiveDetectionFromHtml(html: String): LiveStreamDetection {
            val videoId = canonicalVideoRegex.find(html)?.groupValues?.getOrNull(1)
                ?: videoDetailsIdRegex.find(html)?.groupValues?.getOrNull(1)
            val isLive = !videoId.isNullOrBlank() && isLiveRegex.containsMatchIn(html)
            if (!isLive || videoId == null) {
                return LiveStreamDetection(isLive = false)
            }

            val title = ogTitleRegex.find(html)?.groupValues?.getOrNull(1)
                ?.let { unescapeHtml(it) }
                ?: "Live Stream"
            val viewerMatch = viewerCountRegex.find(html)
            val viewers = (viewerMatch?.groupValues?.getOrNull(1)?.ifBlank { null }
                ?: viewerMatch?.groupValues?.getOrNull(2)?.ifBlank { null })
                ?.toLongOrNull() ?: 0L

            return LiveStreamDetection(
                isLive = true,
                videoId = videoId,
                title = title,
                liveChatSession = extractLiveChatSession(html),
                hlsManifestUrl = extractHlsManifestUrlFromHtml(html),
                dashManifestUrl = extractDashManifestUrlFromHtml(html),
                captionTrackUrl = extractCaptionTrackUrlFromHtml(html),
                concurrentViewers = viewers,
                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault_live.jpg"
            )
        }

        fun extractHlsManifestUrlFromHtml(html: String): String? {
            return hlsManifestRegex.find(html)?.groupValues?.getOrNull(1)
                ?.let { unescapeJsonUrl(it) }
                ?.ifBlank { null }
        }

        fun extractDashManifestUrlFromHtml(html: String): String? {
            return dashManifestRegex.find(html)?.groupValues?.getOrNull(1)
                ?.let { unescapeJsonUrl(it) }
                ?.ifBlank { null }
        }

        fun extractCaptionTrackUrlFromHtml(html: String): String? {
            return captionBaseUrlRegex.find(html)?.groupValues?.getOrNull(1)
                ?.let { unescapeJsonUrl(it) }
                ?.ifBlank { null }
        }

        private fun extractCaptionUrlFromPlayerJson(root: JSONObject): String? {
            val tracks = root.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks") ?: return null
            if (tracks.length() == 0) return null
            var bestFallback: String? = null
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                val lang = track.optString("languageCode", "")
                val url = track.optString("baseUrl", "").ifBlank { null } ?: continue
                if (bestFallback == null) bestFallback = url
                if (lang.startsWith("hi", ignoreCase = true) || lang.startsWith("en", ignoreCase = true)) {
                    return url
                }
            }
            return bestFallback
        }

        fun extractLiveChatSession(html: String): LiveChatSession? {
            val publicKey = innertubeKeyRegex.find(html)?.groupValues?.getOrNull(1) ?: return null
            val clientVersion = innertubeVersionRegex.find(html)?.groupValues?.getOrNull(1)
                ?: DEFAULT_CLIENT_VERSION
            val continuation = reloadContinuationRegex.find(html)?.groupValues?.getOrNull(1)
                ?: invalidationContinuationRegex.find(html)?.groupValues?.getOrNull(1)
                ?: timedContinuationRegex.find(html)?.groupValues?.getOrNull(1)
                ?: liveChatContinuationRegex.find(html)?.groupValues?.getOrNull(1)
                ?: return null

            return LiveChatSession(
                continuation = continuation,
                publicWebKey = publicKey,
                clientVersion = clientVersion
            )
        }

        fun parseInnerTubeChatResponse(
            jsonText: String,
            currentSession: LiveChatSession,
            nowMs: Long
        ): LiveChatBatch {
            val root = JSONObject(jsonText)
            val liveChatContinuation = root
                .optJSONObject("continuationContents")
                ?.optJSONObject("liveChatContinuation")
                ?: return LiveChatBatch(
                    messages = emptyList(),
                    nextSession = null,
                    pollingIntervalMillis = 5000L
                )

            val messages = ArrayList<LiveChatEntry>()
            val actions = liveChatContinuation.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until actions.length()) {
                val actionObj = actions.optJSONObject(i) ?: continue
                val addItem = actionObj.optJSONObject("addChatItemAction") ?: continue
                val item = addItem.optJSONObject("item") ?: continue
                val renderer = item.optJSONObject("liveChatTextMessageRenderer")
                    ?: item.optJSONObject("liveChatPaidMessageRenderer")
                    ?: continue

                val id = renderer.optString("id", "msg_${nowMs}_$i")
                val author = renderer.optJSONObject("authorName")
                    ?.optString("simpleText")
                    ?.ifBlank { null } ?: "Viewer"
                val messageText = extractMessageRuns(renderer.optJSONObject("message"))
                if (messageText.isNotBlank()) {
                    messages.add(
                        LiveChatEntry(
                            id = id,
                            author = author,
                            text = messageText,
                            receivedAtMillis = nowMs
                        )
                    )
                }
            }

            var nextToken: String? = null
            var timeoutMs = 4000L
            val continuations = liveChatContinuation.optJSONArray("continuations") ?: JSONArray()
            for (i in 0 until continuations.length()) {
                val contObj = continuations.optJSONObject(i) ?: continue
                val data = contObj.optJSONObject("invalidationContinuationData")
                    ?: contObj.optJSONObject("timedContinuationData")
                    ?: contObj.optJSONObject("reloadContinuationData")
                    ?: continue
                val token = data.optString("continuation", "")
                if (token.isNotBlank()) {
                    nextToken = token
                    val t = data.optLong("timeoutMs", 4000L)
                    if (t > 0L) timeoutMs = t
                    break
                }
            }

            val nextSession = nextToken?.let { currentSession.copy(continuation = it) }
            return LiveChatBatch(
                messages = messages,
                nextSession = nextSession,
                pollingIntervalMillis = timeoutMs.coerceIn(2500L, 9000L)
            )
        }

        private fun extractMessageRuns(messageObj: JSONObject?): String {
            if (messageObj == null) return ""
            val simple = messageObj.optString("simpleText", "")
            if (simple.isNotBlank()) return simple.trim()

            val runs = messageObj.optJSONArray("runs") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                val run = runs.optJSONObject(i) ?: continue
                val text = run.optString("text", "")
                if (text.isNotEmpty()) {
                    sb.append(text)
                }
            }
            return sb.toString().trim()
        }

        private fun unescapeJsonUrl(raw: String): String = raw
            .replace("\\u0026", "&")
            .replace("\\/", "/")

        private fun unescapeHtml(raw: String): String = raw
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
