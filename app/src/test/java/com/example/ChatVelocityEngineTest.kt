package com.example

import com.example.data.prefs.PipelineConfig
import com.example.data.youtube.LiveChatEntry
import com.example.data.youtube.YouTubeRepository
import com.example.engine.ChannelMonitorPhase
import com.example.engine.ChatVelocityTracker
import com.example.engine.ThroughputCalculator
import com.example.engine.VelocityAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatVelocityEngineTest {

    private fun makeMessages(count: Int, timeMs: Long, prefix: String = "msg"): List<LiveChatEntry> =
        List(count) { idx ->
            LiveChatEntry(
                id = "${prefix}_${timeMs}_$idx",
                author = "Viewer$idx",
                text = "Incredible play $idx!",
                receivedAtMillis = timeMs
            )
        }

    @Test
    fun triggersOn3xSpikeAndStopsBetween30And60sWhenSubsided() {
        val tracker = ChatVelocityTracker()
        val config = PipelineConfig(
            spikeMultiplier = 3.0f,
            subsideFactor = 1.5f,
            minClipSeconds = 30,
            maxClipSeconds = 60
        )

        var now = 1_000_000L
        // Seed initial baseline (~10 msg/min)
        val seedAction = tracker.onChatBatch(makeMessages(5, now), now, config, isInitialSeedBatch = true)
        assertTrue(seedAction is VelocityAction.None)

        // Normal baseline traffic for 15 seconds (2 msgs every 5s -> ~24 msg/min baseline settles)
        repeat(4) {
            now += 5_000L
            val act = tracker.onChatBatch(makeMessages(1, now), now, config)
            assertTrue(act is VelocityAction.None)
        }

        // Massive 3x+ surge (12 messages arrive in next 3s -> >70 msg/min)
        now += 3_000L
        val spikeAction = tracker.onChatBatch(makeMessages(14, now), now, config)
        assertTrue("Expected StartRecording on 3x spike", spikeAction is VelocityAction.StartRecording)
        assertEquals(ChannelMonitorPhase.RECORDING, tracker.phase)

        // Activity immediately drops to 0, but clip MUST NOT stop before 30s
        for (sec in 5..25 step 5) {
            now += 5_000L
            val act = tracker.onChatBatch(emptyList(), now, config)
            assertTrue("Should not stop at ${sec}s before 30s min", act is VelocityAction.None)
            assertEquals(ChannelMonitorPhase.RECORDING, tracker.phase)
        }

        // At 30s+ with subsided activity, it stops cleanly inside [30, 60]
        now += 6_000L
        val stopAction = tracker.onChatBatch(emptyList(), now, config)
        assertTrue("Expected StopAndProcessRecording after 30s subside", stopAction is VelocityAction.StopAndProcessRecording)
        val stopped = stopAction as VelocityAction.StopAndProcessRecording
        assertTrue(stopped.durationSeconds in 30..60)
        assertEquals(ChannelMonitorPhase.COOLDOWN, tracker.phase)
    }

    @Test
    fun capsRecordingAt60SecondsDuringSustainedSpike() {
        val tracker = ChatVelocityTracker()
        val config = PipelineConfig(spikeMultiplier = 3.0f, minClipSeconds = 30, maxClipSeconds = 60)

        var now = 2_000_000L
        tracker.onChatBatch(makeMessages(3, now), now, config, isInitialSeedBatch = true)
        now += 5_000L
        tracker.onChatBatch(makeMessages(1, now), now, config)
        now += 5_000L
        tracker.onChatBatch(makeMessages(1, now), now, config)

        // Trigger spike
        now += 3_000L
        val start = tracker.onChatBatch(makeMessages(16, now), now, config)
        assertTrue(start is VelocityAction.StartRecording)

        // Keep chat permanently hot for 60s
        var finalStop: VelocityAction.StopAndProcessRecording? = null
        repeat(15) {
            now += 4_000L
            val act = tracker.onChatBatch(makeMessages(10, now), now, config)
            if (act is VelocityAction.StopAndProcessRecording) {
                finalStop = act
            }
        }
        assertTrue("Expected hard cap stop at 60s", finalStop != null)
        assertEquals(60, finalStop!!.durationSeconds)
    }

    @Test
    fun normalizesYouTubeChannelInputs() {
        assertEquals("@mkbhd", YouTubeRepository.normalizeChannelInput("https://www.youtube.com/@mkbhd/live"))
        assertEquals("UCBJycsmduvYEL83R_U4JriQ", YouTubeRepository.normalizeChannelInput("https://youtube.com/channel/UCBJycsmduvYEL83R_U4JriQ?sub=1"))
        assertEquals("@lofi", YouTubeRepository.normalizeChannelInput("@lofi"))
    }

    @Test
    fun computesFiveMinuteThroughputRate() {
        val start = 1_000_000L
        val now = start + 5 * 60_000L
        val timestamps = listOf(start + 60_000L, start + 180_000L, start + 260_000L)
        val rate = ThroughputCalculator.clipsPerFiveMinutes(timestamps, start, now)
        assertEquals(3.0f, rate, 0.01f)
    }

    @Test
    fun parsesKeylessChannelAndLiveDetectionFromPublicHtml() {
        val channelHtml = """
            <html><head>
            <meta itemprop="identifier" content="UCSJ4gkVC6NrvII8umztf0Ow">
            <meta property="og:title" content="Lofi Girl">
            <meta property="og:image" content="https://yt3.googleusercontent.com/avatar.jpg">
            <link rel="canonical" href="https://www.youtube.com/@LofiGirl">
            </head></html>
        """.trimIndent()

        val resolved = YouTubeRepository.parseChannelMetadataFromHtml(channelHtml, "@LofiGirl")
        assertTrue(resolved != null)
        assertEquals("UCSJ4gkVC6NrvII8umztf0Ow", resolved!!.channelId)
        assertEquals("Lofi Girl", resolved.title)
        assertEquals("@LofiGirl", resolved.handle)

        val liveHtml = """
            <html><head>
            <link rel="canonical" href="https://www.youtube.com/watch?v=jfKfPfyJRdk">
            <meta property="og:title" content="lofi hip hop radio">
            </head><body>
            "isLive":true,"originalViewCount":"24500",
            "INNERTUBE_API_KEY":"AIzaSyPublicWebTestKey123",
            "INNERTUBE_CLIENT_VERSION":"2.20250501.01.00",
            "reloadContinuationData":{"continuation":"0ofMyAO9"}
            </body></html>
        """.trimIndent()

        val live = YouTubeRepository.parseLiveDetectionFromHtml(liveHtml)
        assertTrue(live.isLive)
        assertEquals("jfKfPfyJRdk", live.videoId)
        assertEquals(24500L, live.concurrentViewers)
        assertEquals("0ofMyAO9", live.liveChatSession?.continuation)
    }

    @Test
    fun parsesInnerTubeLiveChatJsonPayload() {
        val sampleJson = """
            {
              "continuationContents": {
                "liveChatContinuation": {
                  "continuations": [
                    { "invalidationContinuationData": { "continuation": "NEXT_TOKEN_99", "timeoutMs": 3200 } }
                  ],
                  "actions": [
                    {
                      "addChatItemAction": {
                        "item": {
                          "liveChatTextMessageRenderer": {
                            "id": "m1",
                            "authorName": { "simpleText": "Aarav" },
                            "message": { "runs": [ { "text": "WAIT FOR IT!!" }, { "text": " INSANE!" } ] }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val session = com.example.data.youtube.LiveChatSession("INIT", "KEY", "2.2025")
        val batch = YouTubeRepository.parseInnerTubeChatResponse(sampleJson, session, 5000L)
        assertEquals(1, batch.messages.size)
        assertEquals("Aarav", batch.messages[0].author)
        assertEquals("WAIT FOR IT!! INSANE!", batch.messages[0].text)
        assertEquals("NEXT_TOKEN_99", batch.nextSession?.continuation)
        assertEquals(3200L, batch.pollingIntervalMillis)
    }

    @Test
    fun selectsOptimalHlsLiveVideoVariantAndParsesSegments() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            https://manifest.example.com/360p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
            https://manifest.example.com/720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
            https://manifest.example.com/1080p.m3u8
        """.trimIndent()

        val chosen = com.example.engine.LiveVideoRecorder.selectBestVariantUrl(
            master,
            "https://manifest.example.com/master.m3u8"
        )
        assertEquals("https://manifest.example.com/1080p.m3u8", chosen)

        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXTINF:2.0,
            seg_101.ts
            #EXTINF:2.0,
            seg_102.ts
        """.trimIndent()

        val parsed = com.example.engine.LiveVideoRecorder.parseMediaPlaylist(
            media,
            "https://manifest.example.com/live/720p.m3u8"
        )
        assertEquals(2, parsed.targetDurationSec)
        assertEquals(2, parsed.segmentUrls.size)
        assertEquals("https://manifest.example.com/live/seg_101.ts", parsed.segmentUrls[0])
    }

    @Test
    fun parsesSpokenTimedTextCaptionsWithoutChat() {
        val timedTextXml = """
            <transcript>
                <text start="10.0" dur="3.0">Watch this final circle play</text>
                <text start="14.0" dur="2.5">He clutched the whole round!</text>
            </transcript>
        """.trimIndent()
        val cues = com.example.engine.AutoCaptionGenerator.parseTimedTextXml(timedTextXml, 30)
        assertEquals(2, cues.size)
        assertEquals("Watch this final circle play", cues[0].text)
        assertEquals("He clutched the whole round!", cues[1].text)
    }

    @Test
    fun selectsDemuxedHlsAudioVideoAndSubtitlePlaylists() {
        val demuxedMaster = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="234",NAME="English",DEFAULT=YES,URI="https://cdn.example.com/audio_aac.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="hi",NAME="Hindi",URI="https://cdn.example.com/subs_hi.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2600000,RESOLUTION=1280x720,CODECS="avc1.64001f",AUDIO="234"
            https://cdn.example.com/video_720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3200000,RESOLUTION=1280x720,CODECS="vp09.00.31.08",AUDIO="234"
            https://cdn.example.com/video_720p_vp9.m3u8
        """.trimIndent()

        val tracks = com.example.engine.LiveVideoRecorder.selectStreamTracks(
            demuxedMaster,
            "https://cdn.example.com/master.m3u8"
        )
        assertTrue(tracks != null)
        assertEquals("https://cdn.example.com/video_720p.m3u8", tracks!!.videoPlaylistUrl)
        assertEquals("https://cdn.example.com/audio_aac.m3u8", tracks.audioPlaylistUrl)
        assertEquals("https://cdn.example.com/subs_hi.m3u8", tracks.subtitlePlaylistUrl)
        assertEquals(false, tracks.isMuxed)
    }

    @Test
    fun parsesSrv3AndLiveWebVttCaptionsIncludingHindi() {
        val srv3Xml = """
            <timedtext format="3"><body>
                <p t="10000" d="3000"><s>अभी देखो क्या होने वाला है</s></p>
                <p t="14000" d="2500"><s>Unbelievable final shot!</s></p>
            </body></timedtext>
        """.trimIndent()
        val srv3Cues = com.example.engine.AutoCaptionGenerator.parseTimedTextXml(srv3Xml, 30)
        assertEquals(2, srv3Cues.size)
        assertEquals("अभी देखो क्या होने वाला है", srv3Cues[0].text)
        assertEquals("Unbelievable final shot!", srv3Cues[1].text)

        val vttSeg1 = """
            WEBVTT

            00:10:00.000 --> 00:10:03.000
            Wait for this clutch moment
        """.trimIndent()
        val vttSeg2 = """
            WEBVTT

            00:10:04.000 --> 00:10:07.500
            He actually pulled it off!
        """.trimIndent()
        val vttCues = com.example.engine.AutoCaptionGenerator.parseLiveWebVttSegments(listOf(vttSeg1, vttSeg2), 30)
        assertEquals(2, vttCues.size)
        assertEquals(0L, vttCues[0].startMs)
        assertEquals(4000L, vttCues[1].startMs)
        assertEquals("He actually pulled it off!", vttCues[1].text)
    }

    @Test
    fun deduplicatesYouTubeSqSegmentKeysAndParsesLiveDashMpd() {
        val urlA = "https://rr3---sn-a5m.googlevideo.com/videoplayback/id/abc/itag/95/sq/4821/goap/clen%3D123456789012345678901234567890123456789012345678901234567890/file/seg.ts"
        val urlB = "https://rr3---sn-a5m.googlevideo.com/videoplayback/id/abc/itag/95/sq/4822/goap/clen%3D123456789012345678901234567890123456789012345678901234567890/file/seg.ts"
        assertEquals("sq:4821", com.example.engine.LiveVideoRecorder.segmentIdentityKey(urlA))
        assertEquals("sq:4822", com.example.engine.LiveVideoRecorder.segmentIdentityKey(urlB))
        assertEquals("sq:901", com.example.engine.LiveVideoRecorder.segmentIdentityKey("https://cdn.example.com/seg?itag=140&sq=901&lmt=1"))

        val mpd = """
            <MPD type="dynamic">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="136" codecs="avc1.4d401f" height="720">
                    <BaseURL>https://rr1.googlevideo.com/videoplayback/itag/136/</BaseURL>
                    <SegmentList>
                      <Initialization sourceURL="sq/0"/>
                      <SegmentURL media="sq/100"/>
                      <SegmentURL media="sq/101"/>
                    </SegmentList>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="140" codecs="mp4a.40.2">
                    <BaseURL>https://rr1.googlevideo.com/videoplayback/itag/140/</BaseURL>
                    <SegmentList>
                      <Initialization sourceURL="sq/0"/>
                      <SegmentURL media="sq/100"/>
                      <SegmentURL media="sq/101"/>
                    </SegmentList>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val parsed = com.example.engine.LiveVideoRecorder.parseDashLiveMpd(mpd, "https://rr1.googlevideo.com/dash.mpd")
        assertTrue(parsed != null)
        assertEquals("https://rr1.googlevideo.com/videoplayback/itag/136/sq/0", parsed!!.videoInitUrl)
        assertEquals(2, parsed.videoSegmentUrls.size)
        assertEquals("https://rr1.googlevideo.com/videoplayback/itag/140/sq/101", parsed.audioSegmentUrls[1])
    }

    @Test
    fun buildsTimedCaptionTimelineFromEmbeddedPlayerCues() {
        val rawCues = listOf(
            1200L to "भाई ये क्या शॉट मारा!",
            1800L to "भाई ये क्या शॉट मारा!", // duplicate should be skipped
            4800L to "No way he clutched that 1v4!",
            9500L to "Chat is going crazy right now"
        )
        val segments = com.example.engine.EmbeddedPlayerManager.buildCaptionSegmentsFromCues(rawCues, 30)
        assertEquals(3, segments.size)
        assertEquals("भाई ये क्या शॉट मारा!", segments[0].text)
        assertEquals(1200L, segments[0].startMs)
        assertEquals(4800L, segments[0].endMs)
        assertEquals("No way he clutched that 1v4!", segments[1].text)
        assertEquals(4800L, segments[1].startMs)
        assertTrue(segments[1].endMs <= 9500L)
        assertEquals("Chat is going crazy right now", segments[2].text)
        assertTrue(segments[2].endMs <= 30_000L)
    }

    @Test
    fun chunksLongSpokenLinesIntoPunchyShortsCaptions() {
        val longSegments = listOf(
            com.example.engine.CaptionSegment(
                startMs = 0L,
                endMs = 6000L,
                text = "Look at that unbelievable final circle clutch across the entire map right now"
            )
        )
        val chunked = com.example.engine.AutoCaptionGenerator.formatForVerticalShorts(longSegments, maxWordsPerChunk = 5)
        assertEquals(3, chunked.size)
        assertEquals("Look at that unbelievable final", chunked[0].text)
        assertEquals("circle clutch across the entire", chunked[1].text)
        assertEquals("map right now", chunked[2].text)
        assertTrue(chunked[0].startMs == 0L && chunked[0].endMs <= chunked[1].startMs)
        assertTrue(chunked[2].endMs >= 6000L)
    }

    @Test
    fun triggersRepeatedlyAcrossMultipleSpikesWithoutBaselineLockout() {
        val tracker = ChatVelocityTracker()
        val config = PipelineConfig() // Default 3.0x trigger, 30s..45s peak window
        assertEquals(30, config.minClipSeconds)
        assertEquals(45, config.maxClipSeconds)

        var now = 5_000_000L
        // 1) Initial seed batch on Start Engine MUST NOT trigger immediately
        assertTrue(
            "Seed batch must not trigger",
            tracker.onChatBatch(makeMessages(25, now, "seed"), now, config, isInitialSeedBatch = true) is VelocityAction.None
        )

        // 2) First poll inside 10s warm-up window MUST NOT false-trigger even on bursty startup traffic
        now += 4_000L
        assertTrue(
            "Warm-up poll #1 must not false-trigger",
            tracker.onChatBatch(makeMessages(20, now, "warmup1"), now, config) is VelocityAction.None
        )
        now += 4_000L
        assertTrue(
            "Warm-up poll #2 (<10s) must not false-trigger",
            tracker.onChatBatch(makeMessages(2, now, "warmup2"), now, config) is VelocityAction.None
        )

        // Settle calm baseline (~10-15 msg/min) past 10s warm-up
        repeat(4) { i ->
            now += 4_000L
            val act = tracker.onChatBatch(makeMessages(1, now, "calm_$i"), now, config)
            assertTrue("Calm baseline poll should not trigger", act is VelocityAction.None)
        }

        // Helper to drive a full spike -> 30..45s recording -> cooldown -> re-arm cycle
        val completedClips = mutableListOf<VelocityAction.StopAndProcessRecording>()
        for (clipIndex in 1..3) {
            // Sudden 3x+ spike triggers immediately
            now += 3_000L
            val start = tracker.onChatBatch(makeMessages(18, now, "spike_$clipIndex"), now, config)
            assertTrue(
                "Clip #$clipIndex should trigger on 3x spike (mult=${tracker.getCurrentMultiplier()}, base=${tracker.baselineVelocityPerMin})",
                start is VelocityAction.StartRecording
            )
            assertEquals(ChannelMonitorPhase.RECORDING, tracker.phase)

            // Sustained hype for 35s then subsides, or hits 45s cap
            var stopped: VelocityAction.StopAndProcessRecording? = null
            for (step in 1..15) {
                now += 3_500L
                val batchSize = if (step <= 6) 10 else 1
                val act = tracker.onChatBatch(makeMessages(batchSize, now, "rec_${clipIndex}_$step"), now, config)
                if (act is VelocityAction.StopAndProcessRecording) {
                    stopped = act
                    break
                }
            }
            assertTrue("Clip #$clipIndex must complete cleanly", stopped != null)
            assertTrue(
                "Clip #$clipIndex duration ${stopped!!.durationSeconds}s should be in 30..45s",
                stopped.durationSeconds in 30..45
            )
            completedClips.add(stopped)
            assertEquals(ChannelMonitorPhase.COOLDOWN, tracker.phase)

            // Residual elevated chat during early cooldown MUST NOT lock out next clip
            now += 4_000L
            tracker.onChatBatch(makeMessages(6, now, "cool_hot_$clipIndex"), now, config)
            // Normal calm chat through rest of cooldown & re-arm
            repeat(4) { j ->
                now += 4_000L
                val act = tracker.onChatBatch(makeMessages(1, now, "cool_calm_${clipIndex}_$j"), now, config)
                assertTrue("Should not trigger while calming after clip #$clipIndex", act is VelocityAction.None)
            }
            assertEquals(ChannelMonitorPhase.MONITORING, tracker.phase)
        }

        assertEquals(3, completedClips.size)
    }

    @Test
    fun detectsMseInitAndMediaBoundariesForMp4AndWebm() {
        val ftyp = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 0, 0, 0, 0)
        val moof = byteArrayOf(0, 0, 0, 32, 'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'f'.code.toByte(), 0, 0, 0, 0)
        val styp = byteArrayOf(0, 0, 0, 24, 's'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 0, 0, 0, 0)
        val mdat = byteArrayOf(0, 0, 0, 64, 'm'.code.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 1, 2, 3, 4)

        assertTrue(com.example.engine.EmbeddedPlayerManager.isMseInitSegment(ftyp, isMp4 = true))
        assertEquals(false, com.example.engine.EmbeddedPlayerManager.isMseInitSegment(moof, isMp4 = true))
        assertTrue(com.example.engine.EmbeddedPlayerManager.isMseMediaBoundary(moof, isMp4 = true))
        assertTrue(com.example.engine.EmbeddedPlayerManager.isMseMediaBoundary(styp, isMp4 = true))
        assertEquals(false, com.example.engine.EmbeddedPlayerManager.isMseMediaBoundary(mdat, isMp4 = true))

        val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val cluster = byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75, 0, 0, 0, 0, 0, 0, 0, 0)
        val randomBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0, 0, 0, 0, 0, 0, 0, 0)

        assertTrue(com.example.engine.EmbeddedPlayerManager.isMseInitSegment(ebml, isMp4 = false))
        assertEquals(false, com.example.engine.EmbeddedPlayerManager.isMseInitSegment(cluster, isMp4 = false))
        assertTrue(com.example.engine.EmbeddedPlayerManager.isMseMediaBoundary(cluster, isMp4 = false))
        assertEquals(false, com.example.engine.EmbeddedPlayerManager.isMseMediaBoundary(randomBytes, isMp4 = false))
    }

    @Test
    fun extractsDirectLiveVideoIdsAndParsesMultiLineLinks() {
        assertEquals("jfKfPfyJRdk", YouTubeRepository.extractVideoIdFromInput("https://www.youtube.com/watch?v=jfKfPfyJRdk&si=abc"))
        assertEquals("jfKfPfyJRdk", YouTubeRepository.extractVideoIdFromInput("https://www.youtube.com/live/jfKfPfyJRdk?feature=share"))
        assertEquals("jfKfPfyJRdk", YouTubeRepository.extractVideoIdFromInput("https://youtu.be/jfKfPfyJRdk?t=12"))
        assertEquals("jfKfPfyJRdk", YouTubeRepository.extractVideoIdFromInput("https://m.youtube.com/watch?app=m&v=jfKfPfyJRdk"))
        assertEquals("jfKfPfyJRdk", YouTubeRepository.extractVideoIdFromInput("jfKfPfyJRdk"))
        assertEquals(null, YouTubeRepository.extractVideoIdFromInput("@IShowSpeed"))
        assertEquals(null, YouTubeRepository.extractVideoIdFromInput("https://www.youtube.com/@LofiGirl"))
        assertEquals(null, YouTubeRepository.extractVideoIdFromInput("UCSJ4gkVC6NrvII8umztf0Ow"))

        val chId = YouTubeRepository.directVideoChannelId("jfKfPfyJRdk")
        assertTrue(YouTubeRepository.isDirectVideoChannelId(chId))
        assertEquals("jfKfPfyJRdk", YouTubeRepository.extractDirectVideoId(chId))
        assertEquals(false, YouTubeRepository.isDirectVideoChannelId("UCSJ4gkVC6NrvII8umztf0Ow"))

        val multi = """
            https://youtube.com/live/AAAAAAAAAAA
            https://www.youtube.com/watch?v=BBBBBBBBBBB
            https://youtu.be/CCCCCCCCCCC
            https://youtube.com/live/DDDDDDDDDDD
            https://youtube.com/live/EEEEEEEEEEE
        """.trimIndent()
        val parsed = YouTubeRepository.parseMultiLineInputs(multi)
        assertEquals(5, parsed.size)
        assertEquals("EEEEEEEEEEE", YouTubeRepository.extractVideoIdFromInput(parsed[4]))
    }

    @Test
    fun sustainsOvernightContinuousMultiSpikeCapture() {
        val tracker = ChatVelocityTracker()
        val config = PipelineConfig() // 3.0x spike, 30-45s clips
        var now = 10_000_000L

        tracker.onChatBatch(makeMessages(8, now, "seed"), now, config, isInitialSeedBatch = true)
        repeat(4) { i ->
            now += 4_000L
            tracker.onChatBatch(makeMessages(1, now, "warm_$i"), now, config)
        }

        var clipsCaptured = 0
        for (cycle in 1..12) {
            now += 3_000L
            val start = tracker.onChatBatch(makeMessages(20, now, "overnight_spike_$cycle"), now, config)
            assertTrue("Overnight cycle #$cycle should trigger StartRecording", start is VelocityAction.StartRecording)

            var stopped: VelocityAction.StopAndProcessRecording? = null
            for (tick in 1..14) {
                now += 3_500L
                val count = if (tick <= 6) 12 else 1
                val act = tracker.onChatBatch(makeMessages(count, now, "rec_${cycle}_$tick"), now, config)
                if (act is VelocityAction.StopAndProcessRecording) {
                    stopped = act
                    break
                }
            }
            assertTrue("Overnight cycle #$cycle should complete clip", stopped != null)
            assertTrue(stopped!!.durationSeconds in 30..45)
            clipsCaptured++

            // Calm period between spikes
            repeat(6) { c ->
                now += 4_000L
                tracker.onChatBatch(makeMessages(1, now, "calm_${cycle}_$c"), now, config)
            }
            assertEquals(ChannelMonitorPhase.MONITORING, tracker.phase)
        }
        assertEquals(12, clipsCaptured)
    }
}
