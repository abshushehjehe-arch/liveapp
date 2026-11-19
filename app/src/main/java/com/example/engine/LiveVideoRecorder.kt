package com.example.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

data class RecordedRawVideo(
    val videoFile: File? = null,
    val videoSegmentFiles: List<File> = emptyList(),
    val audioFile: File? = null,
    val audioSegmentFiles: List<File> = emptyList(),
    val liveVttCaptions: List<CaptionSegment> = emptyList(),
    val segmentCount: Int = 0,
    val totalBytes: Long = 0L,
    val hasAudioTrack: Boolean = false,
    val sourceLabel: String = "HLS Live Video + Audio"
)

data class SelectedHlsTracks(
    val videoPlaylistUrl: String,
    val audioPlaylistUrl: String?,
    val subtitlePlaylistUrl: String?,
    val isMuxed: Boolean
)

data class ParsedDashLiveMpd(
    val videoInitUrl: String?,
    val videoSegmentUrls: List<String>,
    val audioInitUrl: String?,
    val audioSegmentUrls: List<String>
)

/**
 * Records REAL live broadcast VIDEO + AUDIO + Live WebVTT Subtitles directly from YouTube's
 * live HLS (.m3u8) OR live DASH (.mpd) stream.
 *
 * Key fixes for YouTube live CDN:
 * - Accurate sequence-number deduplication (`sq/1234` and `?sq=1234`) so segments are never skipped.
 * - Multi-variant startup fallback (if 720p returns 403, automatically falls back to 480p/360p).
 * - Supports both muxed MPEG-TS (.ts) and demuxed Video + Audio HLS/DASH fMP4 segments.
 * - Captures live HLS WebVTT spoken subtitles (#EXT-X-MEDIA:TYPE=SUBTITLES) synchronously.
 */
class LiveVideoRecorder(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    suspend fun recordHlsStream(
        hlsManifestUrl: String,
        userAgent: String,
        channelId: String,
        stopFlag: AtomicBoolean,
        maxDurationSec: Int,
        onSegmentRecorded: (segments: Int, bytes: Long, hasAudio: Boolean) -> Unit = { _, _, _ -> }
    ): RecordedRawVideo? = withContext(Dispatchers.IO) {
        val stamp = System.currentTimeMillis()
        val workDir = File(context.cacheDir, "raw_live_${channelId}_$stamp").apply { mkdirs() }
        val masterText = downloadText(hlsManifestUrl, userAgent) ?: return@withContext null

        val rankedTracks = selectRankedStreamTracks(masterText, hlsManifestUrl).ifEmpty {
            listOf(
                SelectedHlsTracks(
                    videoPlaylistUrl = hlsManifestUrl,
                    audioPlaylistUrl = null,
                    subtitlePlaylistUrl = null,
                    isMuxed = true
                )
            )
        }

        // Pick the first variant whose media playlist actually returns downloadable segments
        val tracks = pickWorkingVariant(rankedTracks, masterText, hlsManifestUrl, userAgent)
            ?: rankedTracks.first()

        val videoSegments = ArrayList<File>()
        val audioSegments = ArrayList<File>()
        val vttPayloads = ArrayList<String>()

        var videoTsFile: File? = null
        var videoTsStream: BufferedOutputStream? = null
        var videoIsTs: Boolean? = null
        var videoInitBytes: ByteArray? = null

        var audioTsFile: File? = null
        var audioTsStream: BufferedOutputStream? = null
        var audioIsTs: Boolean? = null
        var audioInitBytes: ByteArray? = null

        val seenVideo = LinkedHashSet<String>(128)
        val seenAudio = LinkedHashSet<String>(128)
        val seenSubs = LinkedHashSet<String>(64)

        var videoSegCount = 0
        var audioSegCount = 0
        var totalBytes = 0L
        val startedMs = System.currentTimeMillis()
        val hardDeadlineMs = startedMs + (maxDurationSec + 8) * 1000L

        try {
            while (isActive && !stopFlag.get() && System.currentTimeMillis() < hardDeadlineMs) {
                var targetSleepMs = 1500L

                // 1) Fetch & append new LIVE VIDEO segments
                val videoPlaylist = downloadText(tracks.videoPlaylistUrl, userAgent)
                if (videoPlaylist != null) {
                    val parsed = parseMediaPlaylist(videoPlaylist, tracks.videoPlaylistUrl)
                    targetSleepMs = (parsed.targetDurationSec * 500L).coerceIn(900L, 2200L)

                    if (videoInitBytes == null && !parsed.initMapUrl.isNullOrBlank()) {
                        videoInitBytes = downloadBytes(parsed.initMapUrl, userAgent)
                    }

                    val candidates = if (seenVideo.isEmpty() && parsed.segmentUrls.size > 3) {
                        parsed.segmentUrls.takeLast(3)
                    } else {
                        parsed.segmentUrls
                    }

                    for (segUrl in candidates) {
                        if (stopFlag.get()) break
                        val key = segmentIdentityKey(segUrl)
                        if (seenVideo.add(key)) {
                            val bytes = downloadBytes(segUrl, userAgent)
                            if (bytes != null && bytes.size > 1024) {
                                if (videoIsTs == null) {
                                    videoIsTs = isMpegTsBytes(bytes)
                                    if (videoIsTs == true) {
                                        videoTsFile = File(workDir, "video_stream.ts")
                                        videoTsStream = BufferedOutputStream(FileOutputStream(videoTsFile), 65_536)
                                    }
                                }
                                if (videoIsTs == true) {
                                    videoTsStream?.write(bytes)
                                    videoTsStream?.flush()
                                } else {
                                    val segFile = File(workDir, String.format("vseg_%04d.mp4", videoSegCount))
                                    FileOutputStream(segFile).use { out ->
                                        videoInitBytes?.let { init ->
                                            if (!startsWithFtyp(bytes)) out.write(init)
                                        }
                                        out.write(bytes)
                                    }
                                    videoSegments.add(segFile)
                                }
                                totalBytes += bytes.size
                                videoSegCount++
                                onSegmentRecorded(
                                    videoSegCount,
                                    totalBytes,
                                    tracks.isMuxed || audioSegCount > 0
                                )
                            }
                        }
                    }
                }

                // 2) Fetch & append new LIVE AUDIO segments (if demuxed HLS)
                if (!tracks.audioPlaylistUrl.isNullOrBlank() && !stopFlag.get()) {
                    val audioPlaylist = downloadText(tracks.audioPlaylistUrl, userAgent)
                    if (audioPlaylist != null) {
                        val parsedAudio = parseMediaPlaylist(audioPlaylist, tracks.audioPlaylistUrl)
                        if (audioInitBytes == null && !parsedAudio.initMapUrl.isNullOrBlank()) {
                            audioInitBytes = downloadBytes(parsedAudio.initMapUrl, userAgent)
                        }

                        val audioCandidates = if (seenAudio.isEmpty() && parsedAudio.segmentUrls.size > 3) {
                            parsedAudio.segmentUrls.takeLast(3)
                        } else {
                            parsedAudio.segmentUrls
                        }

                        for (segUrl in audioCandidates) {
                            if (stopFlag.get()) break
                            val key = segmentIdentityKey(segUrl)
                            if (seenAudio.add(key)) {
                                val bytes = downloadBytes(segUrl, userAgent)
                                if (bytes != null && bytes.size > 256) {
                                    if (audioIsTs == null) {
                                        audioIsTs = isMpegTsBytes(bytes)
                                        if (audioIsTs == true) {
                                            audioTsFile = File(workDir, "audio_stream.ts")
                                            audioTsStream = BufferedOutputStream(FileOutputStream(audioTsFile), 32_768)
                                        }
                                    }
                                    if (audioIsTs == true) {
                                        audioTsStream?.write(bytes)
                                        audioTsStream?.flush()
                                    } else {
                                        val segFile = File(workDir, String.format("aseg_%04d.m4a", audioSegCount))
                                        FileOutputStream(segFile).use { out ->
                                            audioInitBytes?.let { init ->
                                                if (!startsWithFtyp(bytes)) out.write(init)
                                            }
                                            out.write(bytes)
                                        }
                                        audioSegments.add(segFile)
                                    }
                                    totalBytes += bytes.size
                                    audioSegCount++
                                    onSegmentRecorded(videoSegCount, totalBytes, true)
                                }
                            }
                        }
                    }
                }

                // 3) Fetch live HLS WebVTT spoken subtitle segments if advertised
                if (!tracks.subtitlePlaylistUrl.isNullOrBlank() && !stopFlag.get()) {
                    val subPlaylist = downloadText(tracks.subtitlePlaylistUrl, userAgent)
                    if (subPlaylist != null) {
                        val parsedSub = parseMediaPlaylist(subPlaylist, tracks.subtitlePlaylistUrl)
                        val subCandidates = if (seenSubs.isEmpty() && parsedSub.segmentUrls.size > 3) {
                            parsedSub.segmentUrls.takeLast(3)
                        } else {
                            parsedSub.segmentUrls
                        }
                        for (subUrl in subCandidates) {
                            val key = segmentIdentityKey(subUrl)
                            if (seenSubs.add(key)) {
                                val vtt = downloadText(subUrl, userAgent)
                                if (!vtt.isNullOrBlank()) {
                                    vttPayloads.add(vtt)
                                }
                            }
                        }
                    }
                }

                delay(targetSleepMs)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { videoTsStream?.flush() }
            runCatching { videoTsStream?.close() }
            runCatching { audioTsStream?.flush() }
            runCatching { audioTsStream?.close() }
        }

        val hasVideo = (videoTsFile != null && videoTsFile.length() > 16_384L) || videoSegments.isNotEmpty()
        if (!hasVideo || videoSegCount == 0) {
            runCatching { workDir.deleteRecursively() }
            return@withContext null
        }

        val hasSeparateAudio = (audioTsFile != null && audioTsFile.length() > 2048L) || audioSegments.isNotEmpty()
        val parsedVtt = AutoCaptionGenerator.parseLiveWebVttSegments(vttPayloads, maxDurationSec)

        RecordedRawVideo(
            videoFile = videoTsFile,
            videoSegmentFiles = videoSegments,
            audioFile = audioTsFile,
            audioSegmentFiles = audioSegments,
            liveVttCaptions = parsedVtt,
            segmentCount = videoSegCount,
            totalBytes = totalBytes,
            hasAudioTrack = tracks.isMuxed || hasSeparateAudio,
            sourceLabel = if (hasSeparateAudio) "HLS Video + Audio" else "HLS Live Stream"
        )
    }

    /**
     * Records REAL live H.264 video + AAC audio segments directly from a YouTube Live DASH MPD
     * when HLS is absent or blocked.
     */
    suspend fun recordDashStream(
        dashManifestUrl: String,
        userAgent: String,
        channelId: String,
        stopFlag: AtomicBoolean,
        maxDurationSec: Int,
        onSegmentRecorded: (segments: Int, bytes: Long, hasAudio: Boolean) -> Unit = { _, _, _ -> }
    ): RecordedRawVideo? = withContext(Dispatchers.IO) {
        val stamp = System.currentTimeMillis()
        val workDir = File(context.cacheDir, "raw_dash_${channelId}_$stamp").apply { mkdirs() }
        val videoSegments = ArrayList<File>()
        val audioSegments = ArrayList<File>()
        val seenVideo = LinkedHashSet<String>(128)
        val seenAudio = LinkedHashSet<String>(128)
        var videoInitBytes: ByteArray? = null
        var audioInitBytes: ByteArray? = null
        var videoSegCount = 0
        var audioSegCount = 0
        var totalBytes = 0L
        val hardDeadlineMs = System.currentTimeMillis() + (maxDurationSec + 8) * 1000L

        try {
            while (isActive && !stopFlag.get() && System.currentTimeMillis() < hardDeadlineMs) {
                val mpdXml = downloadText(dashManifestUrl, userAgent)
                if (mpdXml != null) {
                    val parsed = parseDashLiveMpd(mpdXml, dashManifestUrl)
                    if (parsed != null) {
                        if (videoInitBytes == null && !parsed.videoInitUrl.isNullOrBlank()) {
                            videoInitBytes = downloadBytes(parsed.videoInitUrl, userAgent)
                        }
                        if (audioInitBytes == null && !parsed.audioInitUrl.isNullOrBlank()) {
                            audioInitBytes = downloadBytes(parsed.audioInitUrl, userAgent)
                        }

                        val vCandidates = if (seenVideo.isEmpty() && parsed.videoSegmentUrls.size > 3) {
                            parsed.videoSegmentUrls.takeLast(3)
                        } else {
                            parsed.videoSegmentUrls
                        }
                        for (vUrl in vCandidates) {
                            if (stopFlag.get()) break
                            val key = segmentIdentityKey(vUrl)
                            if (seenVideo.add(key)) {
                                val bytes = downloadBytes(vUrl, userAgent)
                                if (bytes != null && bytes.size > 1024) {
                                    val segFile = File(workDir, String.format("dvseg_%04d.mp4", videoSegCount))
                                    FileOutputStream(segFile).use { out ->
                                        videoInitBytes?.let { init ->
                                            if (!startsWithFtyp(bytes)) out.write(init)
                                        }
                                        out.write(bytes)
                                    }
                                    videoSegments.add(segFile)
                                    totalBytes += bytes.size
                                    videoSegCount++
                                    onSegmentRecorded(videoSegCount, totalBytes, audioSegCount > 0)
                                }
                            }
                        }

                        val aCandidates = if (seenAudio.isEmpty() && parsed.audioSegmentUrls.size > 3) {
                            parsed.audioSegmentUrls.takeLast(3)
                        } else {
                            parsed.audioSegmentUrls
                        }
                        for (aUrl in aCandidates) {
                            if (stopFlag.get()) break
                            val key = segmentIdentityKey(aUrl)
                            if (seenAudio.add(key)) {
                                val bytes = downloadBytes(aUrl, userAgent)
                                if (bytes != null && bytes.size > 256) {
                                    val segFile = File(workDir, String.format("daseg_%04d.m4a", audioSegCount))
                                    FileOutputStream(segFile).use { out ->
                                        audioInitBytes?.let { init ->
                                            if (!startsWithFtyp(bytes)) out.write(init)
                                        }
                                        out.write(bytes)
                                    }
                                    audioSegments.add(segFile)
                                    totalBytes += bytes.size
                                    audioSegCount++
                                    onSegmentRecorded(videoSegCount, totalBytes, true)
                                }
                            }
                        }
                    }
                }
                delay(1600L)
            }
        } catch (_: Exception) {
        }

        if (videoSegments.isEmpty()) {
            runCatching { workDir.deleteRecursively() }
            return@withContext null
        }

        RecordedRawVideo(
            videoSegmentFiles = videoSegments,
            audioSegmentFiles = audioSegments,
            segmentCount = videoSegCount,
            totalBytes = totalBytes,
            hasAudioTrack = audioSegments.isNotEmpty(),
            sourceLabel = "DASH Video + Audio"
        )
    }

    private fun pickWorkingVariant(
        ranked: List<SelectedHlsTracks>,
        masterText: String,
        masterUrl: String,
        userAgent: String
    ): SelectedHlsTracks? {
        for (candidate in ranked.take(5)) {
            val playlist = if (candidate.videoPlaylistUrl == masterUrl) {
                masterText
            } else {
                downloadText(candidate.videoPlaylistUrl, userAgent)
            } ?: continue
            val parsed = parseMediaPlaylist(playlist, candidate.videoPlaylistUrl)
            val testUrl = parsed.segmentUrls.lastOrNull() ?: continue
            if (isUrlDownloadable(testUrl, userAgent)) {
                return candidate
            }
        }
        return null
    }

    private fun isUrlDownloadable(url: String, userAgent: String): Boolean {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val buf = ByteArray(128)
                (resp.body?.byteStream()?.read(buf) ?: 0) > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun downloadText(url: String, userAgent: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadBytes(url: String, userAgent: String): ByteArray? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    data class ParsedMediaPlaylist(
        val initMapUrl: String?,
        val segmentUrls: List<String>,
        val targetDurationSec: Int
    )

    companion object {
        private val resolutionRegex = Regex("""RESOLUTION=\d+x(\d+)""")
        private val bandwidthRegex = Regex("""BANDWIDTH=(\d+)""")
        private val codecsRegex = Regex("""CODECS="([^"]+)"""")
        private val audioGroupAttrRegex = Regex("""AUDIO="([^"]+)"""")
        private val groupIdAttrRegex = Regex("""GROUP-ID="([^"]+)"""")
        private val languageAttrRegex = Regex("""LANGUAGE="([^"]+)"""")
        private val uriAttrRegex = Regex("""URI="([^"]+)"""")
        private val targetDurRegex = Regex("""#EXT-X-TARGETDURATION:(\d+)""")
        private val sqPathRegex = Regex("""/sq/(\d+)""")
        private val sqQueryRegex = Regex("""[?&]sq=(\d+)""")

        private val adaptationSetRegex = Regex(
            """<AdaptationSet([^>]*)>(.*?)</AdaptationSet>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val representationRegex = Regex(
            """<Representation([^>]*)>(.*?)</Representation>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        private val baseUrlTagRegex = Regex("""<BaseURL[^>]*>(.*?)</BaseURL>""", setOf(RegexOption.DOT_MATCHES_ALL))
        private val initSourceUrlRegex = Regex("""<Initialization[^>]*sourceURL="([^"]+)"""")
        private val segmentUrlMediaRegex = Regex("""<SegmentURL[^>]*media="([^"]+)"""")
        private val heightAttrRegex = Regex("""height="(\d+)"""")
        private val dashCodecsAttrRegex = Regex("""codecs="([^"]+)"""")

        fun isMpegTsBytes(bytes: ByteArray): Boolean {
            if (bytes.size < 189) return bytes.isNotEmpty() && bytes[0] == 0x47.toByte()
            return bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte()
        }

        private fun startsWithFtyp(bytes: ByteArray): Boolean {
            return bytes.size >= 8 &&
                bytes[4] == 'f'.code.toByte() &&
                bytes[5] == 't'.code.toByte() &&
                bytes[6] == 'y'.code.toByte() &&
                bytes[7] == 'p'.code.toByte()
        }

        /**
         * Returns HLS stream track options ranked from best to fallback (preferring H.264 avc1
         * muxed 720p/480p/360p, then demuxed H.264+AAC) so startup can fall back if one tier 403s.
         */
        fun selectRankedStreamTracks(masterPlaylist: String, baseUrl: String): List<SelectedHlsTracks> {
            if (!masterPlaylist.contains("#EXT-X-STREAM-INF")) return emptyList()
            val lines = masterPlaylist.lines().map { it.trim() }.filter { it.isNotEmpty() }

            val audioGroups = LinkedHashMap<String, String>()
            var preferredSubtitleUrl: String? = null
            var fallbackSubtitleUrl: String? = null

            for (line in lines) {
                if (!line.startsWith("#EXT-X-MEDIA:")) continue
                val uri = uriAttrRegex.find(line)?.groupValues?.getOrNull(1)?.let { resolveUri(baseUrl, it) }
                    ?: continue
                when {
                    line.contains("TYPE=AUDIO") -> {
                        val groupId = groupIdAttrRegex.find(line)?.groupValues?.getOrNull(1) ?: "default"
                        val isDefault = line.contains("DEFAULT=YES")
                        if (!audioGroups.containsKey(groupId) || isDefault) {
                            audioGroups[groupId] = uri
                        }
                    }

                    line.contains("TYPE=SUBTITLES") -> {
                        val lang = languageAttrRegex.find(line)?.groupValues?.getOrNull(1).orEmpty()
                        if (lang.startsWith("en", ignoreCase = true) || lang.startsWith("hi", ignoreCase = true)) {
                            if (preferredSubtitleUrl == null) preferredSubtitleUrl = uri
                        } else if (fallbackSubtitleUrl == null) {
                            fallbackSubtitleUrl = uri
                        }
                    }
                }
            }

            data class Variant(
                val url: String,
                val height: Int,
                val bandwidth: Long,
                val isAvc: Boolean,
                val audioGroup: String?
            )

            val variants = ArrayList<Variant>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF") && i + 1 < lines.size) {
                    val uriLine = lines[i + 1]
                    if (!uriLine.startsWith("#")) {
                        val height = resolutionRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        val bw = bandwidthRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                        val codecs = codecsRegex.find(line)?.groupValues?.getOrNull(1).orEmpty()
                        val isAvc = codecs.isEmpty() || codecs.contains("avc1", ignoreCase = true)
                        val audioGroup = audioGroupAttrRegex.find(line)?.groupValues?.getOrNull(1)
                        variants.add(
                            Variant(
                                url = resolveUri(baseUrl, uriLine),
                                height = height,
                                bandwidth = bw,
                                isAvc = isAvc,
                                audioGroup = audioGroup
                            )
                        )
                    }
                    i += 2
                } else {
                    i++
                }
            }

            if (variants.isEmpty()) return emptyList()

            val videoPool = variants.filter { it.height > 0 }.ifEmpty { variants }
            val subUrl = preferredSubtitleUrl ?: fallbackSubtitleUrl

            val ranked = videoPool.sortedWith(
                compareByDescending<Variant> { it.isAvc }
                    .thenByDescending { it.audioGroup == null || !audioGroups.containsKey(it.audioGroup) }
                    .thenByDescending { it.height in 720..1080 }
                    .thenBy { kotlin.math.abs(it.height - 1080) }
            )

            return ranked.map { chosen ->
                val pairedAudio = chosen.audioGroup?.let { audioGroups[it] }
                    ?: if (chosen.audioGroup != null) audioGroups.values.firstOrNull() else null
                SelectedHlsTracks(
                    videoPlaylistUrl = chosen.url,
                    audioPlaylistUrl = pairedAudio,
                    subtitlePlaylistUrl = subUrl,
                    isMuxed = pairedAudio == null
                )
            }
        }

        fun selectStreamTracks(masterPlaylist: String, baseUrl: String): SelectedHlsTracks? {
            return selectRankedStreamTracks(masterPlaylist, baseUrl).firstOrNull()
        }

        fun selectBestVariantUrl(masterPlaylist: String, baseUrl: String): String? {
            return selectStreamTracks(masterPlaylist, baseUrl)?.videoPlaylistUrl
        }

        fun parseMediaPlaylist(playlistText: String, baseUrl: String): ParsedMediaPlaylist {
            var initMap: String? = null
            val segments = ArrayList<String>()
            var targetDur = 2

            for (rawLine in playlistText.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                when {
                    line.startsWith("#EXT-X-TARGETDURATION:") -> {
                        targetDur = targetDurRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 2
                    }

                    line.startsWith("#EXT-X-MAP:") -> {
                        val uri = uriAttrRegex.find(line)?.groupValues?.getOrNull(1)
                        if (!uri.isNullOrBlank()) {
                            initMap = resolveUri(baseUrl, uri)
                        }
                    }

                    !line.startsWith("#") -> {
                        segments.add(resolveUri(baseUrl, line))
                    }
                }
            }
            return ParsedMediaPlaylist(
                initMapUrl = initMap,
                segmentUrls = segments,
                targetDurationSec = targetDur
            )
        }

        /**
         * Parses YouTube Live DASH MPD XML to extract H.264 (avc1) video segments and AAC (mp4a)
         * audio segments along with their initialization URLs.
         */
        fun parseDashLiveMpd(mpdXml: String, mpdUrl: String): ParsedDashLiveMpd? {
            if (!mpdXml.contains("<MPD", ignoreCase = true)) return null

            data class DashTrack(
                val isVideo: Boolean,
                val height: Int,
                val isPreferredCodec: Boolean,
                val initUrl: String?,
                val segmentUrls: List<String>
            )

            val videoTracks = ArrayList<DashTrack>()
            val audioTracks = ArrayList<DashTrack>()

            adaptationSetRegex.findAll(mpdXml).forEach { setMatch ->
                val setAttrs = setMatch.groupValues[1]
                val setBody = setMatch.groupValues[2]
                val isVideo = setAttrs.contains("video/", ignoreCase = true)
                val isAudio = setAttrs.contains("audio/", ignoreCase = true)
                if (!isVideo && !isAudio) return@forEach

                representationRegex.findAll(setBody).forEach { repMatch ->
                    val repAttrs = repMatch.groupValues[1]
                    val repBody = repMatch.groupValues[2]
                    val codecs = dashCodecsAttrRegex.find(repAttrs)?.groupValues?.getOrNull(1).orEmpty()
                    val height = heightAttrRegex.find(repAttrs)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val base = baseUrlTagRegex.find(repBody)?.groupValues?.getOrNull(1)
                        ?.trim()
                        ?.replace("&amp;", "&")
                        ?.ifBlank { null }
                        ?: mpdUrl
                    val initRel = initSourceUrlRegex.find(repBody)?.groupValues?.getOrNull(1)
                        ?.replace("&amp;", "&")
                    val initFull = initRel?.let { resolveDashSegmentUrl(base, it) }
                    val segs = segmentUrlMediaRegex.findAll(repBody).map { m ->
                        resolveDashSegmentUrl(base, m.groupValues[1].replace("&amp;", "&"))
                    }.toList()

                    if (segs.isNotEmpty()) {
                        val preferredCodec = if (isVideo) {
                            codecs.contains("avc1", ignoreCase = true) || codecs.isBlank()
                        } else {
                            codecs.contains("mp4a", ignoreCase = true) || codecs.isBlank()
                        }
                        val track = DashTrack(isVideo, height, preferredCodec, initFull, segs)
                        if (isVideo) videoTracks.add(track) else audioTracks.add(track)
                    }
                }
            }

            val bestVideo = videoTracks
                .sortedWith(
                    compareByDescending<DashTrack> { it.isPreferredCodec }
                        .thenByDescending { it.height in 720..1080 }
                        .thenBy { kotlin.math.abs(it.height - 1080) }
                )
                .firstOrNull() ?: return null

            val bestAudio = audioTracks
                .sortedByDescending { it.isPreferredCodec }
                .firstOrNull()

            return ParsedDashLiveMpd(
                videoInitUrl = bestVideo.initUrl,
                videoSegmentUrls = bestVideo.segmentUrls,
                audioInitUrl = bestAudio?.initUrl,
                audioSegmentUrls = bestAudio?.segmentUrls.orEmpty()
            )
        }

        private fun resolveDashSegmentUrl(baseUrl: String, mediaPath: String): String {
            if (mediaPath.startsWith("http://") || mediaPath.startsWith("https://")) return mediaPath
            return if (baseUrl.endsWith("/")) "$baseUrl$mediaPath" else "$baseUrl$mediaPath"
        }

        private fun resolveUri(baseUrl: String, relativeOrAbsolute: String): String {
            return if (relativeOrAbsolute.startsWith("http://") || relativeOrAbsolute.startsWith("https://")) {
                relativeOrAbsolute
            } else {
                runCatching { URI(baseUrl).resolve(relativeOrAbsolute).toString() }.getOrDefault(relativeOrAbsolute)
            }
        }

        fun segmentIdentityKey(url: String): String {
            sqPathRegex.find(url)?.groupValues?.getOrNull(1)?.let { return "sq:$it" }
            sqQueryRegex.find(url)?.groupValues?.getOrNull(1)?.let { return "sq:$it" }
            return url
        }
    }
}
