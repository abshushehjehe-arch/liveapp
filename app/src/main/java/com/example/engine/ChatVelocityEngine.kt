package com.example.engine

import com.example.data.prefs.PipelineConfig
import com.example.data.youtube.LiveChatEntry
import kotlin.math.max

enum class ChannelMonitorPhase {
    OFFLINE,
    CONNECTING,
    MONITORING,
    RECORDING,
    COOLDOWN,
    ERROR
}

data class VelocitySample(
    val timestampMs: Long,
    val velocityPerMin: Float,
    val baselinePerMin: Float,
    val isRecording: Boolean
)

sealed interface VelocityAction {
    data object None : VelocityAction
    data class StartRecording(
        val triggeredAtMs: Long,
        val baselineVelocity: Float,
        val triggerVelocity: Float,
        val spikeMultiplier: Float
    ) : VelocityAction

    data class StopAndProcessRecording(
        val startedAtMs: Long,
        val endedAtMs: Long,
        val durationSeconds: Int,
        val baselineVelocity: Float,
        val peakVelocity: Float,
        val spikeMultiplier: Float
    ) : VelocityAction
}

/**
 * Deterministic per-channel chat velocity tracker and repeatable spike trigger.
 * - Strictly measures chat message arrival velocity — NEVER records or stores chat text.
 * - Uses a 10s warm-up window so Start Engine establishes a true ambient baseline instead of
 *   false-triggering immediately on the first poll.
 * - Uses an asymmetric anti-inflation baseline EMA so normal chat speed recovers quickly after
 *   a hype wave, guaranteeing repeated clips (Clip #1, #2, #3, ...) trigger on every future spike!
 * - Captures 30s–45s (configurable up to 60s) peak-moment clips automatically.
 */
class ChatVelocityTracker(
    private val maxHistoryPoints: Int = 36
) {
    private val recentMessageTimestamps = ArrayDeque<Long>(512)
    private val history = ArrayDeque<VelocitySample>(maxHistoryPoints + 2)

    var phase: ChannelMonitorPhase = ChannelMonitorPhase.MONITORING
        private set

    var baselineVelocityPerMin: Float = 0f
        private set

    var currentVelocityPerMin: Float = 0f
        private set

    var peakRecordingVelocity: Float = 0f
        private set

    var recordingStartedAtMs: Long = 0L
        private set

    private var recordingBaselineVelocity: Float = 0f
    private var subsideStartedAtMs: Long = 0L
    private var cooldownUntilMs: Long = 0L
    private var baselineSampleCount: Int = 0
    private var trackingStartedAtMs: Long = 0L
    private var lastPollAtMs: Long = 0L

    fun reset() {
        recentMessageTimestamps.clear()
        history.clear()
        phase = ChannelMonitorPhase.MONITORING
        baselineVelocityPerMin = 0f
        currentVelocityPerMin = 0f
        peakRecordingVelocity = 0f
        recordingStartedAtMs = 0L
        recordingBaselineVelocity = 0f
        subsideStartedAtMs = 0L
        cooldownUntilMs = 0L
        baselineSampleCount = 0
        trackingStartedAtMs = 0L
        lastPollAtMs = 0L
    }

    fun getHistory(): List<VelocitySample> = history.toList()

    fun getRecordingElapsedSeconds(nowMs: Long): Int {
        if (phase != ChannelMonitorPhase.RECORDING || recordingStartedAtMs <= 0L) return 0
        return ((nowMs - recordingStartedAtMs) / 1000L).toInt().coerceAtLeast(0)
    }

    fun getCurrentMultiplier(): Float {
        val base = max(baselineVelocityPerMin, MIN_BASELINE_FLOOR)
        return currentVelocityPerMin / base
    }

    /**
     * Ingests a batch of newly arrived live chat events strictly for velocity measurement
     * and evaluates whether to start or stop background VIDEO recording (30s–45s/60s).
     */
    fun onChatBatch(
        messages: List<LiveChatEntry>,
        nowMs: Long,
        config: PipelineConfig,
        isInitialSeedBatch: Boolean = false
    ): VelocityAction {
        if (trackingStartedAtMs == 0L) {
            trackingStartedAtMs = nowMs
        }

        if (isInitialSeedBatch) {
            // Seed starting estimate from initial history batch, but require real live polls
            // to warm up before arming triggers so Start Engine never false-fires on poll #1.
            val seedCount = messages.size
            val estimatedPerMin = (seedCount * 3f).coerceAtLeast(MIN_BASELINE_FLOOR)
            baselineVelocityPerMin = estimatedPerMin
            currentVelocityPerMin = estimatedPerMin
            baselineSampleCount = 0
            lastPollAtMs = nowMs
            appendHistory(nowMs)
            return VelocityAction.None
        }

        // Distribute arriving batch timestamps smoothly across (prevPollMs..nowMs] to prevent poll jitter
        val prevPoll = if (lastPollAtMs > 0L && nowMs > lastPollAtMs) lastPollAtMs else (nowMs - 3000L)
        val spanMs = (nowMs - prevPoll).coerceIn(500L, 10_000L)
        val count = messages.size
        if (count > 0) {
            for (i in 0 until count) {
                val offset = if (count == 1) spanMs else ((i + 1) * spanMs) / count
                recentMessageTimestamps.addLast(prevPoll + offset)
            }
        }
        lastPollAtMs = nowMs

        val windowStart = nowMs - SHORT_WINDOW_MS
        while (recentMessageTimestamps.isNotEmpty() && recentMessageTimestamps.first() < windowStart) {
            recentMessageTimestamps.removeFirst()
        }

        val elapsedWindowSec = ((nowMs - trackingStartedAtMs) / 1000f)
            .coerceIn(5f, SHORT_WINDOW_MS / 1000f)
        currentVelocityPerMin = (recentMessageTimestamps.size.toFloat() / elapsedWindowSec) * 60f

        var action: VelocityAction = VelocityAction.None

        when (phase) {
            ChannelMonitorPhase.COOLDOWN -> {
                // Only pull baseline DOWN during cooldown (never let residual post-spike hype inflate baseline!)
                if (currentVelocityPerMin <= baselineVelocityPerMin * 1.25f) {
                    updateBaselineEma(currentVelocityPerMin)
                }
                if (nowMs >= cooldownUntilMs) {
                    phase = ChannelMonitorPhase.MONITORING
                }
            }

            ChannelMonitorPhase.MONITORING -> {
                val effectiveBaseline = max(baselineVelocityPerMin, MIN_BASELINE_FLOOR)
                val multiplier = currentVelocityPerMin / effectiveBaseline
                val warmedUp = baselineSampleCount >= WARMUP_POLL_COUNT &&
                    (nowMs - trackingStartedAtMs) >= WARMUP_MIN_MS

                if (warmedUp &&
                    multiplier >= config.spikeMultiplier &&
                    currentVelocityPerMin >= MIN_TRIGGER_VELOCITY_PER_MIN
                ) {
                    phase = ChannelMonitorPhase.RECORDING
                    recordingStartedAtMs = nowMs
                    recordingBaselineVelocity = effectiveBaseline
                    peakRecordingVelocity = currentVelocityPerMin
                    subsideStartedAtMs = 0L

                    action = VelocityAction.StartRecording(
                        triggeredAtMs = nowMs,
                        baselineVelocity = effectiveBaseline,
                        triggerVelocity = currentVelocityPerMin,
                        spikeMultiplier = multiplier
                    )
                } else {
                    updateBaselineEma(currentVelocityPerMin)
                }
            }

            ChannelMonitorPhase.RECORDING -> {
                if (currentVelocityPerMin > peakRecordingVelocity) {
                    peakRecordingVelocity = currentVelocityPerMin
                }
                val elapsedSec = ((nowMs - recordingStartedAtMs) / 1000L).toInt()
                val subsideThreshold = max(recordingBaselineVelocity * config.subsideFactor, MIN_BASELINE_FLOOR)

                if (currentVelocityPerMin <= subsideThreshold) {
                    if (subsideStartedAtMs == 0L) {
                        subsideStartedAtMs = nowMs
                    }
                } else {
                    subsideStartedAtMs = 0L
                }

                val hasSubsided = subsideStartedAtMs > 0L && (nowMs - subsideStartedAtMs) >= SUBSIDE_SETTLE_MS
                val reachedMin = elapsedSec >= config.minClipSeconds
                val reachedMax = elapsedSec >= config.maxClipSeconds

                if ((reachedMin && hasSubsided) || reachedMax) {
                    val finalDuration = elapsedSec.coerceIn(config.minClipSeconds, config.maxClipSeconds)
                    val peak = peakRecordingVelocity
                    val base = max(recordingBaselineVelocity, MIN_BASELINE_FLOOR)
                    val startMs = recordingStartedAtMs

                    phase = ChannelMonitorPhase.COOLDOWN
                    cooldownUntilMs = nowMs + COOLDOWN_MS
                    recordingStartedAtMs = 0L
                    subsideStartedAtMs = 0L

                    action = VelocityAction.StopAndProcessRecording(
                        startedAtMs = startMs,
                        endedAtMs = nowMs,
                        durationSeconds = finalDuration,
                        baselineVelocity = base,
                        peakVelocity = peak,
                        spikeMultiplier = peak / base
                    )
                }
            }

            else -> {
                phase = ChannelMonitorPhase.MONITORING
            }
        }

        appendHistory(nowMs)
        return action
    }

    /**
     * Asymmetric EMA:
     * - Adapts rapidly downward (alpha=0.60 when <45% of baseline, 0.28 otherwise) whenever
     *   the 12s rolling window genuinely calms down after startup bursts or hype waves.
     * - Adapts slowly upward (alpha=0.08) and caps single-step jump so pre-spike buildup never
     *   inflates the baseline out of reach of future triggers!
     */
    private fun updateBaselineEma(sample: Float) {
        baselineSampleCount++
        if (baselineSampleCount == 1) {
            baselineVelocityPerMin = max(sample, MIN_BASELINE_FLOOR)
            return
        }
        val cappedSample = if (sample > baselineVelocityPerMin * 1.8f) {
            baselineVelocityPerMin * 1.8f
        } else {
            sample
        }
        val alpha = when {
            cappedSample < baselineVelocityPerMin * 0.45f -> 0.60f
            cappedSample < baselineVelocityPerMin -> 0.28f
            else -> 0.08f
        }
        val next = (1f - alpha) * baselineVelocityPerMin + alpha * cappedSample
        baselineVelocityPerMin = max(next, MIN_BASELINE_FLOOR)
    }

    private fun appendHistory(nowMs: Long) {
        if (history.size >= maxHistoryPoints) {
            history.removeFirst()
        }
        history.addLast(
            VelocitySample(
                timestampMs = nowMs,
                velocityPerMin = currentVelocityPerMin,
                baselinePerMin = max(baselineVelocityPerMin, MIN_BASELINE_FLOOR),
                isRecording = phase == ChannelMonitorPhase.RECORDING
            )
        )
    }

    companion object {
        const val SHORT_WINDOW_MS = 12_000L
        const val WARMUP_POLL_COUNT = 2
        const val WARMUP_MIN_MS = 10_000L
        const val SUBSIDE_SETTLE_MS = 3_000L
        const val COOLDOWN_MS = 12_000L
        const val MIN_BASELINE_FLOOR = 2.0f
        const val MIN_TRIGGER_VELOCITY_PER_MIN = 6.0f
    }
}

/**
 * Computes rolling processed clip rate normalized per 5 minutes of monitoring.
 */
object ThroughputCalculator {
    fun clipsPerFiveMinutes(
        completedClipTimestampsMs: List<Long>,
        monitoringStartedAtMs: Long,
        nowMs: Long
    ): Float {
        if (monitoringStartedAtMs <= 0L || nowMs <= monitoringStartedAtMs) return 0f
        val windowMs = 5 * 60_000L
        val windowStart = max(monitoringStartedAtMs, nowMs - windowMs)
        val recentCount = completedClipTimestampsMs.count { it >= windowStart }
        if (recentCount == 0) return 0f
        val elapsedMinutes = ((nowMs - windowStart).toFloat() / 60_000f).coerceAtLeast(1f)
        val effectiveWindowMin = elapsedMinutes.coerceAtMost(5f)
        return (recentCount.toFloat() / effectiveWindowMin) * 5f
    }
}
