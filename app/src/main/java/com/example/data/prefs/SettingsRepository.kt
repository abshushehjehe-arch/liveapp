package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pipelineDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pulseclip_pipeline_settings"
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SPIKE_MULTIPLIER = floatPreferencesKey("spike_multiplier")
        val SUBSIDE_FACTOR = floatPreferencesKey("subside_factor")
        val MIN_CLIP_SECONDS = intPreferencesKey("min_clip_seconds")
        val MAX_CLIP_SECONDS = intPreferencesKey("max_clip_seconds")
        val OVERLAY_TEXT = stringPreferencesKey("overlay_text")
        val AUTO_CAPTIONS = booleanPreferencesKey("auto_captions")
        val CAPTION_STYLE = stringPreferencesKey("caption_style")
        val VIDEO_PROFILE = stringPreferencesKey("video_profile")
        val MAX_CHANNELS = intPreferencesKey("max_concurrent_channels")
        val TARGET_CLIPS_5M = intPreferencesKey("target_clips_per_five_min")
        val ADAPTIVE_PACING = booleanPreferencesKey("adaptive_pacing")
        val MANUAL_CLIP_CUT = booleanPreferencesKey("manual_clip_cut_enabled")
        val QUALITY_UPGRADED_1080P_V2 = booleanPreferencesKey("quality_upgraded_1080p_v2")
    }

    val configFlow: Flow<PipelineConfig> = context.pipelineDataStore.data.map { prefs ->
        val style = prefs[Keys.CAPTION_STYLE]?.let { name ->
            runCatching { CaptionStyle.valueOf(name) }.getOrNull()
        } ?: CaptionStyle.DYNAMIC_PILL

        val profile = if (prefs[Keys.QUALITY_UPGRADED_1080P_V2] != true) {
            VideoQualityProfile.HIGH_1080P
        } else {
            prefs[Keys.VIDEO_PROFILE]?.let { name ->
                runCatching { VideoQualityProfile.valueOf(name) }.getOrNull()
            } ?: VideoQualityProfile.HIGH_1080P
        }

        PipelineConfig(
            spikeMultiplier = (prefs[Keys.SPIKE_MULTIPLIER] ?: 3.0f).coerceIn(2.0f, 5.0f),
            subsideFactor = (prefs[Keys.SUBSIDE_FACTOR] ?: 1.5f).coerceIn(1.1f, 2.2f),
            minClipSeconds = (prefs[Keys.MIN_CLIP_SECONDS] ?: 30).coerceIn(30, 45),
            maxClipSeconds = (prefs[Keys.MAX_CLIP_SECONDS] ?: 45).coerceIn(45, 60),
            overlayText = prefs[Keys.OVERLAY_TEXT]?.ifBlank { "Wait for it" } ?: "Wait for it",
            autoCaptionsEnabled = prefs[Keys.AUTO_CAPTIONS] ?: true,
            captionStyle = style,
            videoProfile = profile,
            maxConcurrentChannels = (prefs[Keys.MAX_CHANNELS] ?: 6).coerceIn(1, 12),
            targetClipsPerFiveMin = (prefs[Keys.TARGET_CLIPS_5M] ?: 2).coerceIn(1, 6),
            adaptivePacingEnabled = prefs[Keys.ADAPTIVE_PACING] ?: true,
            manualClipCutEnabled = prefs[Keys.MANUAL_CLIP_CUT] ?: true
        )
    }

    suspend fun updateConfig(transform: (PipelineConfig) -> PipelineConfig) {
        context.pipelineDataStore.edit { prefs ->
            val current = PipelineConfig(
                spikeMultiplier = prefs[Keys.SPIKE_MULTIPLIER] ?: 3.0f,
                subsideFactor = prefs[Keys.SUBSIDE_FACTOR] ?: 1.5f,
                minClipSeconds = prefs[Keys.MIN_CLIP_SECONDS] ?: 30,
                maxClipSeconds = prefs[Keys.MAX_CLIP_SECONDS] ?: 45,
                overlayText = prefs[Keys.OVERLAY_TEXT] ?: "Wait for it",
                autoCaptionsEnabled = prefs[Keys.AUTO_CAPTIONS] ?: true,
                captionStyle = prefs[Keys.CAPTION_STYLE]?.let {
                    runCatching { CaptionStyle.valueOf(it) }.getOrNull()
                } ?: CaptionStyle.DYNAMIC_PILL,
                videoProfile = if (prefs[Keys.QUALITY_UPGRADED_1080P_V2] != true) {
                    VideoQualityProfile.HIGH_1080P
                } else {
                    prefs[Keys.VIDEO_PROFILE]?.let {
                        runCatching { VideoQualityProfile.valueOf(it) }.getOrNull()
                    } ?: VideoQualityProfile.HIGH_1080P
                },
                maxConcurrentChannels = prefs[Keys.MAX_CHANNELS] ?: 6,
                targetClipsPerFiveMin = prefs[Keys.TARGET_CLIPS_5M] ?: 2,
                adaptivePacingEnabled = prefs[Keys.ADAPTIVE_PACING] ?: true,
                manualClipCutEnabled = prefs[Keys.MANUAL_CLIP_CUT] ?: true
            )
            val updated = transform(current)
            prefs[Keys.SPIKE_MULTIPLIER] = updated.spikeMultiplier.coerceIn(2.0f, 5.0f)
            prefs[Keys.SUBSIDE_FACTOR] = updated.subsideFactor.coerceIn(1.1f, 2.2f)
            prefs[Keys.MIN_CLIP_SECONDS] = updated.minClipSeconds.coerceIn(30, 45)
            prefs[Keys.MAX_CLIP_SECONDS] = updated.maxClipSeconds.coerceIn(updated.minClipSeconds, 60)
            prefs[Keys.OVERLAY_TEXT] = updated.overlayText.take(36)
            prefs[Keys.AUTO_CAPTIONS] = updated.autoCaptionsEnabled
            prefs[Keys.CAPTION_STYLE] = updated.captionStyle.name
            prefs[Keys.VIDEO_PROFILE] = updated.videoProfile.name
            prefs[Keys.QUALITY_UPGRADED_1080P_V2] = true
            prefs[Keys.MAX_CHANNELS] = updated.maxConcurrentChannels.coerceIn(1, 12)
            prefs[Keys.TARGET_CLIPS_5M] = updated.targetClipsPerFiveMin.coerceIn(1, 6)
            prefs[Keys.ADAPTIVE_PACING] = updated.adaptivePacingEnabled
            prefs[Keys.MANUAL_CLIP_CUT] = updated.manualClipCutEnabled
        }
    }
}
