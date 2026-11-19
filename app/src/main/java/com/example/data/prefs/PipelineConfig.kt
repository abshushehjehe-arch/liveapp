package com.example.data.prefs

enum class CaptionStyle(val displayName: String) {
    DYNAMIC_PILL("Dynamic Pill"),
    BOLD_OUTLINE("Bold Outline"),
    BROADCAST_BAR("Broadcast Bar")
}

enum class VideoQualityProfile(
    val displayName: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitRate: Int,
    val footprintLabel: String
) {
    ECO_540P(
        displayName = "Eco 540×960",
        width = 540,
        height = 960,
        fps = 24,
        bitRate = 4_500_000,
        footprintLabel = "Compact 540p • 4.5 Mbps"
    ),
    BALANCED_720P(
        displayName = "HD 720×1280",
        width = 720,
        height = 1280,
        fps = 30,
        bitRate = 8_000_000,
        footprintLabel = "Sharp 720p 30fps • 8 Mbps"
    ),
    HIGH_1080P(
        displayName = "Studio 1080×1920",
        width = 1080,
        height = 1920,
        fps = 30,
        bitRate = 14_000_000,
        footprintLabel = "Crystal-Clear 1080p • 14 Mbps"
    )
}

data class PipelineConfig(
    val spikeMultiplier: Float = 3.0f,
    val subsideFactor: Float = 1.5f,
    val minClipSeconds: Int = 30,
    val maxClipSeconds: Int = 45,
    val overlayText: String = "Wait for it",
    val autoCaptionsEnabled: Boolean = true,
    val captionStyle: CaptionStyle = CaptionStyle.DYNAMIC_PILL,
    val videoProfile: VideoQualityProfile = VideoQualityProfile.HIGH_1080P,
    val maxConcurrentChannels: Int = 6,
    val targetClipsPerFiveMin: Int = 2,
    val adaptivePacingEnabled: Boolean = true,
    val manualClipCutEnabled: Boolean = true
)
