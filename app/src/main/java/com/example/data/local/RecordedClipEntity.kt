package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

object ClipStatus {
    const val QUEUED = "QUEUED"
    const val ENCODING = "ENCODING"
    const val SAVED = "SAVED"
    const val FAILED = "FAILED"
}

@Entity(tableName = "recorded_clips")
data class RecordedClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val channelId: String,
    val channelTitle: String,
    val videoId: String,
    val streamTitle: String,
    val filePath: String = "",
    val contentUri: String = "",
    val thumbnailPath: String = "",
    val durationSeconds: Int,
    val fileSizeBytes: Long = 0L,
    val width: Int = 720,
    val height: Int = 1280,
    val aspectRatio: String = "9:16",
    val overlayText: String = "Wait for it",
    val captionsSummary: String = "",
    val captionCount: Int = 0,
    val peakVelocityPerMin: Float = 0f,
    val baselineVelocityPerMin: Float = 0f,
    val spikeMultiplier: Float = 3.0f,
    val status: String = ClipStatus.QUEUED,
    val progressPercent: Int = 0,
    val errorMessage: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val processingTimeMs: Long = 0L
)
