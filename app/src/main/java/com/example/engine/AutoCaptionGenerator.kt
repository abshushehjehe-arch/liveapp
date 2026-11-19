package com.example.engine

import org.json.JSONArray
import org.json.JSONObject

data class CaptionSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/**
 * Parses spoken audio auto-captions directly from YouTube's broadcast caption tracks:
 * - HLS Live WebVTT (.vtt) subtitle segments
 * - YouTube TimedText JSON3
 * - YouTube TimedText SRV3 XML (<p t="..." d="..."><s>...</s></p>)
 * - YouTube TimedText Classic XML (<text start="..." dur="...">...</text>)
 * Never uses live chat comments.
 */
object AutoCaptionGenerator {

    private val whitespaceRegex = Regex("\\s+")
    private val xmlTextCueRegex = Regex(
        """<text\s+start="([0-9.]+)"(?:\s+dur="([0-9.]+)")?[^>]*>(.*?)</text>""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val srv3ParagraphRegex = Regex(
        """<p\s+[^>]*t="(\d+)"(?:[^>]*d="(\d+)")?[^>]*>(.*?)</p>""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val vttTimingRegex = Regex(
        """(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})"""
    )
    private val htmlTagRegex = Regex("<[^>]+>")

    /**
     * Parses YouTube timedtext JSON3 payload and extracts the trailing [durationSeconds]
     * spoken caption cues aligned to 0..durationMs of the recorded clip.
     */
    fun parseTimedTextJson3(jsonText: String, durationSeconds: Int): List<CaptionSegment> {
        if (jsonText.isBlank()) return emptyList()
        return try {
            val root = JSONObject(jsonText)
            val events = root.optJSONArray("events") ?: JSONArray()
            val rawCues = ArrayList<CaptionSegment>()

            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val tStart = ev.optLong("tStartMs", -1L)
                if (tStart < 0L) continue
                val dDuration = ev.optLong("dDurationMs", 2800L).coerceIn(1000L, 6500L)
                val segs = ev.optJSONArray("segs") ?: continue
                val sb = StringBuilder()
                for (j in 0 until segs.length()) {
                    val seg = segs.optJSONObject(j) ?: continue
                    val utf8 = seg.optString("utf8", "")
                    if (utf8.isNotEmpty() && utf8 != "\n") {
                        sb.append(utf8)
                    }
                }
                val clean = sb.toString().replace(whitespaceRegex, " ").trim()
                if (clean.length >= 2) {
                    rawCues.add(
                        CaptionSegment(
                            startMs = tStart,
                            endMs = tStart + dDuration,
                            text = clean.take(84)
                        )
                    )
                }
            }
            alignTrailingWindow(rawCues, durationSeconds)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Parses YouTube timedtext XML payloads — supports both:
     * 1) SRV3 live ASR XML: <p t="12345" d="3000"><s>word</s></p>
     * 2) Classic XML: <text start="12.3" dur="3.0">phrase</text>
     */
    fun parseTimedTextXml(xmlText: String, durationSeconds: Int): List<CaptionSegment> {
        if (xmlText.isBlank()) return emptyList()
        val rawCues = ArrayList<CaptionSegment>()

        // 1) Try SRV3 <p t="..." d="..."> format first (most common for YouTube Live ASR)
        srv3ParagraphRegex.findAll(xmlText).forEach { match ->
            val startMs = match.groupValues[1].toLongOrNull() ?: return@forEach
            val durMs = (match.groupValues[2].toLongOrNull() ?: 2800L).coerceIn(1000L, 6500L)
            val body = cleanCaptionText(match.groupValues[3])
            if (body.length >= 2) {
                rawCues.add(CaptionSegment(startMs = startMs, endMs = startMs + durMs, text = body.take(84)))
            }
        }

        // 2) Classic <text start="..." dur="..."> format
        if (rawCues.isEmpty()) {
            xmlTextCueRegex.findAll(xmlText).forEach { match ->
                val startSec = match.groupValues[1].toDoubleOrNull() ?: return@forEach
                val durSec = match.groupValues[2].toDoubleOrNull() ?: 2.8
                val body = cleanCaptionText(match.groupValues[3])
                if (body.length >= 2) {
                    val startMs = (startSec * 1000.0).toLong()
                    val endMs = startMs + (durSec * 1000.0).toLong().coerceIn(1000L, 6500L)
                    rawCues.add(CaptionSegment(startMs = startMs, endMs = endMs, text = body.take(84)))
                }
            }
        }

        return alignTrailingWindow(rawCues, durationSeconds)
    }

    /**
     * Parses a WebVTT (.vtt) payload into CaptionSegments aligned to 0..durationMs.
     */
    fun parseWebVtt(vttText: String, durationSeconds: Int): List<CaptionSegment> {
        if (vttText.isBlank()) return emptyList()
        val raw = parseWebVttRaw(vttText)
        return alignTrailingWindow(raw, durationSeconds)
    }

    /**
     * Parses multiple chronologically downloaded HLS live WebVTT segments into a single
     * clip-aligned CaptionSegment timeline (0..durationSeconds*1000).
     */
    fun parseLiveWebVttSegments(vttSegments: List<String>, durationSeconds: Int): List<CaptionSegment> {
        if (vttSegments.isEmpty()) return emptyList()
        val allRaw = ArrayList<CaptionSegment>()
        for (vtt in vttSegments) {
            allRaw.addAll(parseWebVttRaw(vtt))
        }
        if (allRaw.isEmpty()) return emptyList()
        val sorted = allRaw.sortedBy { it.startMs }
        val firstStart = sorted.first().startMs
        val clipDurationMs = durationSeconds * 1000L
        val normalized = sorted.mapNotNull { seg ->
            val relStart = (seg.startMs - firstStart).coerceAtLeast(0L)
            if (relStart >= clipDurationMs) return@mapNotNull null
            val relEnd = (seg.endMs - firstStart).coerceIn(relStart + 900L, clipDurationMs)
            CaptionSegment(startMs = relStart, endMs = relEnd, text = seg.text)
        }
        return deduplicateRollingCues(normalized)
    }

    private fun parseWebVttRaw(vttText: String): List<CaptionSegment> {
        val cues = ArrayList<CaptionSegment>()
        val lines = vttText.replace("\r\n", "\n").lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val match = vttTimingRegex.find(line)
            if (match != null) {
                val startMs = parseVttTimestampMs(
                    match.groupValues[1],
                    match.groupValues[2],
                    match.groupValues[3],
                    match.groupValues[4]
                )
                val endMs = parseVttTimestampMs(
                    match.groupValues[5],
                    match.groupValues[6],
                    match.groupValues[7],
                    match.groupValues[8]
                ).coerceAtLeast(startMs + 1000L)

                val textBuilder = StringBuilder()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    if (textBuilder.isNotEmpty()) textBuilder.append(' ')
                    textBuilder.append(lines[i].trim())
                    i++
                }
                val clean = cleanCaptionText(textBuilder.toString())
                if (clean.length >= 2) {
                    cues.add(CaptionSegment(startMs = startMs, endMs = endMs, text = clean.take(84)))
                }
            } else {
                i++
            }
        }
        return cues
    }

    private fun parseVttTimestampMs(h: String, m: String, s: String, ms: String): Long {
        val hours = h.toLongOrNull() ?: 0L
        val mins = m.toLongOrNull() ?: 0L
        val secs = s.toLongOrNull() ?: 0L
        val millis = ms.toLongOrNull() ?: 0L
        return ((hours * 3600L + mins * 60L + secs) * 1000L) + millis
    }

    private fun alignTrailingWindow(
        cues: List<CaptionSegment>,
        durationSeconds: Int
    ): List<CaptionSegment> {
        if (cues.isEmpty()) return emptyList()
        val clipDurationMs = durationSeconds * 1000L
        val latestEnd = cues.maxOf { it.endMs }
        val windowStart = (latestEnd - clipDurationMs).coerceAtLeast(0L)

        val aligned = cues
            .filter { it.endMs >= windowStart }
            .sortedBy { it.startMs }
            .map { seg ->
                val relStart = (seg.startMs - windowStart).coerceIn(0L, clipDurationMs)
                val relEnd = (seg.endMs - windowStart).coerceIn(relStart + 900L, clipDurationMs)
                CaptionSegment(startMs = relStart, endMs = relEnd, text = seg.text)
            }
        return deduplicateRollingCues(aligned)
    }

    private fun deduplicateRollingCues(cues: List<CaptionSegment>): List<CaptionSegment> {
        if (cues.isEmpty()) return emptyList()
        val result = ArrayList<CaptionSegment>(cues.size)
        for (cue in cues) {
            val prev = result.lastOrNull()
            if (prev != null && prev.text.equals(cue.text, ignoreCase = true)) {
                result[result.lastIndex] = prev.copy(endMs = maxOf(prev.endMs, cue.endMs))
            } else {
                result.add(cue)
            }
        }
        return result
    }

    /**
     * Splits long subtitle sentences into punchy 3–5 word Reels/Shorts-style caption chunks
     * distributed evenly across each cue's timestamp window so burned-in captions read cleanly.
     */
    fun formatForVerticalShorts(
        segments: List<CaptionSegment>,
        maxWordsPerChunk: Int = 5
    ): List<CaptionSegment> {
        if (segments.isEmpty()) return emptyList()
        val out = ArrayList<CaptionSegment>(segments.size * 2)
        for (seg in segments.sortedBy { it.startMs }) {
            val words = seg.text.trim().split(whitespaceRegex).filter { it.isNotBlank() }
            if (words.isEmpty()) continue
            if (words.size <= maxWordsPerChunk) {
                out.add(seg.copy(text = words.joinToString(" ")))
                continue
            }
            val chunks = words.chunked(maxWordsPerChunk).map { it.joinToString(" ") }
            val totalDur = (seg.endMs - seg.startMs).coerceAtLeast(1000L)
            val sliceDur = (totalDur / chunks.size).coerceAtLeast(700L)
            chunks.forEachIndexed { idx, chunkText ->
                val cStart = seg.startMs + idx * sliceDur
                val cEnd = if (idx == chunks.lastIndex) {
                    maxOf(seg.endMs, cStart + 700L)
                } else {
                    cStart + sliceDur
                }
                out.add(CaptionSegment(startMs = cStart, endMs = cEnd, text = chunkText))
            }
        }
        return deduplicateRollingCues(out)
    }

    fun summarizeCaptions(segments: List<CaptionSegment>): String {
        if (segments.isEmpty()) return ""
        return segments
            .take(10)
            .joinToString(separator = "\n") { seg ->
                val sec = seg.startMs / 1000L
                val mm = sec / 60
                val ss = sec % 60
                String.format("[%02d:%02d] %s", mm, ss, seg.text)
            }
    }

    private fun cleanCaptionText(raw: String): String {
        return unescapeXml(raw)
            .replace(htmlTagRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()
    }

    private fun unescapeXml(raw: String): String = raw
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
}
